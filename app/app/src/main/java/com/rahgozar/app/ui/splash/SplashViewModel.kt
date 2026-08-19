package com.rahgozar.app.ui.splash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rahgozar.app.AppConfig
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.panel.AdsConfig
import com.rahgozar.app.panel.PanelSync
import com.rahgozar.app.panel.TunnelSettings
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Drives the splash from the sync, not from a stopwatch.
 *
 * Written as one sequential run rather than a set of flags, because the screen
 * is a sequence: mark, then what is happening, then what happened. Reading it
 * top to bottom should tell you exactly what the user sees.
 *
 * Two forces are balanced here. The messages must be **true** — each one is a
 * real state of the sync, so a stalled network sits on "please wait" for as
 * long as it is actually stalled. And each one must be **readable** — a cached
 * configuration answers in about 300 ms, and without a floor the whole splash
 * would be a flicker nobody could parse. Hence a minimum dwell per message; the
 * network can make the splash longer, never shorter.
 */
class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _phase = MutableStateFlow(SplashPhase.BRAND)
    val phase: StateFlow<SplashPhase> = _phase.asStateFlow()

    private val _outcome = MutableStateFlow<SplashOutcome?>(null)
    val outcome: StateFlow<SplashOutcome?> = _outcome.asStateFlow()

    /** Set when the sync failed, so the screen can say what went wrong. */
    private val _failureReason = MutableStateFlow("")
    val failureReason: StateFlow<String> = _failureReason.asStateFlow()

    /** When the current message went up, so each one gets its full dwell. */
    private var phaseSetAt = 0L

    init {
        start()
    }

    private fun show(next: SplashPhase) {
        _phase.value = next
        phaseSetAt = System.currentTimeMillis()
    }

    /**
     * Keeps the current message on screen for at least [ms].
     *
     * This is the piece that was missing: awaiting a sync that has *already*
     * finished returns instantly, so on a cached launch the first message was
     * replaced in the same frame it appeared — two crossfades ran at once and
     * the screen went blank between them.
     */
    private suspend fun holdCurrent(ms: Long) {
        val shown = System.currentTimeMillis() - phaseSetAt
        if (shown < ms) delay(ms - shown)
    }

    private fun start() {
        viewModelScope.launch {
            // The geo files, unpacked here rather than on the home screen.
            //
            // A core cannot start without them — its routing rules name
            // `geoip:`/`geosite:` categories that live in those files — and
            // the splash now starts one for the ad flow, before the home
            // screen has ever run. On a fresh install that meant the first
            // launch's smart tunnel failed and only the second worked. Copies
            // nothing when the files are already current.
            val assets = async(Dispatchers.IO) {
                runCatching {
                    SettingsManager.initAssets(getApplication(), getApplication<Application>().assets)
                }.onFailure { LogUtil.e(AppConfig.TAG, "splash: could not unpack geo assets", it) }
            }

            // The sync starts immediately and runs alongside the presentation:
            // the screen never delays the work, it only refuses to race ahead
            // of what the user can read.
            val sync: Deferred<PanelSync.Result> = async {
                PanelSync.run(getApplication()) { stage ->
                    if (stage == PanelSync.Stage.FAILED) show(SplashPhase.FAILED)
                }
            }

            // 5b — the mark. Nothing true to say yet, so this is the one beat
            // that is purely brand.
            show(SplashPhase.BRAND)
            holdCurrent(BRAND_MS)

            // 5c, first message — «در حال دریافت سرورها».
            show(SplashPhase.FETCHING)
            var settled = sync.awaitAtMost(MESSAGE_MS)
            holdCurrent(MESSAGE_MS)

            // Second message — «لطفاً صبر کنید». Only reached when the sync is
            // genuinely still going, which is the whole point of it existing.
            if (settled == null) {
                show(SplashPhase.PATIENCE)
                settled = sync.await()
                holdCurrent(MESSAGE_MS)
            }

            // Nothing that follows may start a core, and a core without these
            // files does not start. In practice they are long since copied by
            // the time the sync returns.
            assets.await()
            finish(settled)
        }
    }

    /** Awaits the sync, giving up after [timeoutMs] so the screen can move on. */
    private suspend fun Deferred<PanelSync.Result>.awaitAtMost(
        timeoutMs: Long,
    ): PanelSync.Result? = select {
        onAwait { it }
        onTimeout(timeoutMs) { null }
    }

    private suspend fun finish(result: PanelSync.Result) {
        when (result) {
            is PanelSync.Result.Ready -> {
                // Applied on a 304 too: the settings are the last response
                // replayed, so re-applying them is a no-op, and skipping it
                // would mean a device that never sees a change keeps whatever
                // it had before the panel started sending these at all.
                TunnelSettings.apply(result.settings)
                AdManager.apply(getApplication(), result.ads)
                succeed()
            }

            is PanelSync.Result.Blocked -> {
                AdManager.apply(getApplication(), AdsConfig.disabled())
                show(SplashPhase.FAILED)
                _outcome.value = SplashOutcome.Refused(result.decision)
            }

            is PanelSync.Result.Unavailable -> {
                _failureReason.value = result.reason
                if (result.fatal) {
                    show(SplashPhase.FAILED)
                    _outcome.value = SplashOutcome.Unreachable
                } else {
                    // The device already has a configuration from a previous
                    // launch. Refusing to open the app because one request
                    // failed would be worse than opening it with what it has —
                    // but it is *not* a success, and saying "servers loaded"
                    // here was a plain lie: nothing was loaded, and the user
                    // had just been looking at a "try again" button.
                    proceed(SplashPhase.CACHED)
                }
            }
        }
    }

    /** Third message — «سرورها با موفقیت دریافت شد» — then into the app. */
    private suspend fun succeed() = proceed(SplashPhase.SUCCESS)

    /**
     * Shows the closing message and opens the app.
     *
     * The phase is a parameter because there are two ways to arrive here and
     * they are not the same news: the panel answered, or it did not and this
     * device is running on what it already had. Everything after the message
     * is identical, which is exactly why the message must not be.
     */
    private suspend fun proceed(closing: SplashPhase) {
        show(closing)
        holdCurrent(SUCCESS_MS)
        // Remembered for as long as this process lives, which is what decides
        // whether a later launch replays all of this. See [SplashSession].
        SplashSession.markSynced()
        // Whether the ad scenario runs is decided here, but *how* is the
        // Activity's problem — see [SplashOutcome.AdFlow]. Deeper checks
        // (smart profile, consent, fills) live in the flow itself; this is
        // only "is the slot switched on at all".
        _outcome.value = if (AdManager.isActive(AdSlot.SPLASH)) {
            SplashOutcome.AdFlow
        } else {
            SplashOutcome.Continue
        }
    }

    /**
     * The ad flow is about to make the user wait — smart tunnel coming up, or
     * a fill on its way. «لطفاً صبر کنید» is the honest thing to show: it is
     * the same true statement it is during a slow sync.
     */
    fun showAdWaiting() {
        show(SplashPhase.AD_WAITING)
    }

    /** Lets the failure screen try again without restarting the process. */
    fun retry() {
        _failureReason.value = ""
        _outcome.value = null
        start()
    }

    companion object {
        /** How long the mark holds before the first message. */
        const val BRAND_MS = 1_900L

        /**
         * The least time a message stays up.
         *
         * Also the point at which a sync that has not answered is called slow,
         * because those are the same moment: the first message has had its say
         * and there is still nothing new to report.
         */
        const val MESSAGE_MS = 2_300L

        /** Long enough to read "servers loaded" and see the tick. */
        const val SUCCESS_MS = 1_900L
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SplashViewModel(app) as T
    }
}
