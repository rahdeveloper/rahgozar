package com.rahgozar.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdFormat
import com.rahgozar.app.panel.AdPlacement
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.util.LogUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A full-screen ad that has been fetched and is waiting to be shown.
 *
 * Wraps the two SDK types behind one shape so the callers — which only ever
 * want "show it, tell me when it is gone" — never branch on format.
 */
class LoadedAd internal constructor(
    val placement: AdPlacement,
    private val appOpen: AppOpenAd? = null,
    private val interstitial: InterstitialAd? = null,
    private val rewarded: RewardedAd? = null,
    private val rewardedInterstitial: RewardedInterstitialAd? = null,
    /**
     * The smart session this ad was fetched in, or -1 when it was fetched
     * with no tunnel because none was required. Showing an ad fires an
     * impression the same way fetching it fired a request, so an ad that
     * outlived its session cannot be shown — see [AdInventory.takePending].
     */
    internal val sessionEpoch: Int = -1,
    private val loadedAt: Long = System.currentTimeMillis(),
) {
    /**
     * Google's own shelf lives: an app-open ad may be shown for four hours
     * after loading, an interstitial for about one. A stale ad still shows,
     * but earns nothing and can be flagged, so it is treated as absent.
     */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean {
        val shelfMs = if (appOpen != null) 4 * 3600_000L else 3600_000L
        return now - loadedAt < shelfMs
    }

    /**
     * Shows the ad.
     *
     * Always in immersive mode. The ad does not draw into the caller's window —
     * the SDK opens its own Activity, declared with a plain translucent theme,
     * and that window's own request is what the system bars follow. So a screen
     * that hid them (the splash does, see `SplashActivity.goFullScreen`) would
     * get them back for the length of the ad, together with the VPN key of the
     * very tunnel the ad is being fetched through. `setImmersiveMode` is
     * Google's own answer to that, and it costs nothing on the screens that
     * show their bars: a full-screen ad covers them either way, and they return
     * when it is dismissed.
     *
     * @param onReward called if the user watches a rewarded ad through to the
     *   point where the SDK says they have earned it. Never called for the
     *   formats that promise the user nothing.
     */
    internal fun show(
        activity: Activity,
        callback: FullScreenContentCallback,
        onReward: () -> Unit = {},
    ) {
        when {
            appOpen != null -> {
                appOpen.setImmersiveMode(true)
                appOpen.fullScreenContentCallback = callback
                appOpen.show(activity)
            }

            interstitial != null -> {
                interstitial.setImmersiveMode(true)
                interstitial.fullScreenContentCallback = callback
                interstitial.show(activity)
            }

            rewarded != null -> {
                rewarded.setImmersiveMode(true)
                rewarded.fullScreenContentCallback = callback
                rewarded.show(activity) { onReward() }
            }

            rewardedInterstitial != null -> {
                rewardedInterstitial.setImmersiveMode(true)
                rewardedInterstitial.fullScreenContentCallback = callback
                rewardedInterstitial.show(activity) { onReward() }
            }
        }
    }
}

/**
 * Loads and holds full-screen ads.
 *
 * The splash scenario needs exactly two: the first is loaded and shown on the
 * splash, the second is loaded behind it and *held* — [pending] — until the
 * user's first action on the home screen (connect, or opening the server
 * list) claims it with [takePending]. The pending slot is process-wide state
 * on purpose: the splash starts that load and a different Activity consumes
 * it.
 */
object AdInventory {

    /** Ad #2, parked between the splash that loaded it and the tap that shows it. */
    @Volatile
    private var pending: LoadedAd? = null

    /** Survives the splash Activity, which is gone before ad #2 finishes loading. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Requests the SDK has not answered yet.
     *
     * The GMA SDK cannot be told to cancel, so a load whose wait timed out is
     * still on the wire. This is what lets [SmartTunnel.stop] hold the tunnel
     * until those finish rather than pulling it out from under them.
     */
    private val inFlight = AtomicInteger(0)

    /**
     * When the user last tapped an ad, on the boot clock, or 0.
     *
     * A click is the one piece of ad traffic that leaves this process: the
     * SDK hands the click-through URL to a browser or the Play Store, and the
     * redirect chain starts there. Nothing here can count it, so the teardown
     * settles for knowing it happened and how long ago.
     */
    @Volatile
    private var lastClickAt = 0L

    /** Milliseconds since the last tapped ad, or [Long.MAX_VALUE] if none. */
    fun millisSinceLastClick(): Long =
        if (lastClickAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastClickAt

    /**
     * Waits for the SDK to have nothing outstanding.
     *
     * @return true when it went quiet, false when the wait ran out with work
     *   still on the wire — a distinction the caller needs, because tearing
     *   the tunnel down in the second case puts that work on the user's real
     *   address.
     */
    suspend fun awaitQuiet(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (inFlight.get() > 0) delay(100)
            true
        } ?: false

    /**
     * Loads one ad, or returns null after [timeoutMs].
     *
     * The timeout abandons the *wait*, not the request: if the fill arrives
     * after the caller has moved on it is parked in [pending] instead of
     * being dropped, because a paid-for fill that arrived late is exactly
     * what the home screen's slot wants.
     */
    suspend fun load(context: Context, placement: AdPlacement, timeoutMs: Long): LoadedAd? {
        if (!placement.enabled) return null
        val started = SystemClock.elapsedRealtime()
        val ad = requestWithin(context, placement, timeoutMs)
        if (ad == null) {
            // Said out loud, because the alternative is what happened before:
            // an ad the user never saw and a log with nothing in it. Google's
            // own failure callback can arrive a minute later — its HTTP budget
            // is 60s against our 12 — so by the time anything is written down
            // the launch it belonged to is long over.
            LogUtil.i(
                AppConfig.TAG,
                "ads: ${placement.slot.wire} did not fill in " +
                    "${SystemClock.elapsedRealtime() - started}ms — skipping it",
            )
        }
        return ad
    }

    private suspend fun requestWithin(
        context: Context,
        placement: AdPlacement,
        timeoutMs: Long,
    ): LoadedAd? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    requestLoad(context.applicationContext, placement) { ad ->
                        if (cont.isActive) {
                            cont.resume(ad)
                        } else if (ad != null) {
                            // A fill that arrived after the wait was abandoned.
                            // Worth keeping only if it is still showable — an
                            // ad whose session has ended cannot be, because
                            // its impression would leave outside the tunnel.
                            if (showable(ad)) {
                                pending = ad
                                LogUtil.i(AppConfig.TAG, "ads: late fill parked for later")
                            } else {
                                LogUtil.w(AppConfig.TAG, "ads: late fill dropped — its session ended")
                            }
                        }
                    }
                }
            }
        }
    }

    /** Fire-and-forget load into the pending slot. */
    fun preload(context: Context, placement: AdPlacement, timeoutMs: Long) {
        scope.launch {
            val ad = load(context, placement, timeoutMs)
            if (ad != null) {
                pending = ad
                LogUtil.i(AppConfig.TAG, "ads: second ad ready")
            } else {
                LogUtil.i(AppConfig.TAG, "ads: second ad did not fill in ${timeoutMs}ms")
            }
        }
    }

    /**
     * Whether a parked ad is fresh **and** still safe to show. The peek half
     * of [takePending], for callers deciding whether a tap owes an ad at all.
     */
    fun hasPending(): Boolean = pending?.let { it.isFresh() && showable(it) } == true

    /**
     * Claims the parked ad, if there is one that can still be shown.
     *
     * A claim, not a peek: the caller is about to show it, and a full-screen
     * ad object must never be shown twice.
     */
    fun takePending(): LoadedAd? {
        val ad = pending ?: return null
        pending = null
        return ad.takeIf { it.isFresh() && showable(it) }
    }

    /**
     * Whether showing this ad now would keep its traffic inside the tunnel
     * that fetched it.
     *
     * Showing is not passive: it fires an impression, and a click fires more.
     * So an ad that needed a tunnel may only be shown while *its* session is
     * still up — not merely while some session is. An ad that needed none is
     * always showable; that is the case in countries the panel exempts.
     */
    private fun showable(ad: LoadedAd, context: Context? = null): Boolean {
        if (ad.sessionEpoch < 0) return true
        if (SmartTunnel.epoch != ad.sessionEpoch) return false
        // With a context, the real question: is this app's traffic inside the
        // tunnel *now*. Without one — the late-fill park, which only decides
        // whether to keep an ad, not whether to show it — the session flag is
        // the best available answer and is re-checked at show time anyway.
        return if (context != null) SmartTunnel.isCarrying(context) else SmartTunnel.isActive
    }

    /**
     * Shows a loaded ad and reports when the screen is the caller's again —
     * dismissed by the user or refused by the SDK, the caller does the same
     * thing either way.
     */
    fun show(activity: Activity, ad: LoadedAd, onFinished: () -> Unit) {
        // The SDK will not fire both callbacks, but nothing in its contract
        // says so out loud; the continuation of the ad flow must run exactly
        // once or the app navigates twice.
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) onFinished()
        }

        // The last gate, and until now the only ad entry point that had none.
        //
        // Showing is not passive: it fires an impression, and a tap fires a
        // click-through. Both are ad traffic, and both happen *here* — long
        // after the fill that this ad's session was checked for. If the
        // tunnel died in between, showing sends them on the user's real
        // address. The caller is told the screen is free so the scenario
        // carries on exactly as it does when an ad simply fails to show.
        if (!showable(ad, activity)) {
            LogUtil.w(AppConfig.TAG, "ads: not showing — its tunnel is no longer carrying us")
            finish()
            return
        }

        PanelStore.setAdLastShownAt(ad.placement.slot.wire, System.currentTimeMillis())
        // The session is demonstrably in use; keep its watchdog off it.
        SmartTunnel.touch()
        ad.show(activity, object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish()

            // The teardown reads this: a click hands the rest of the work to
            // another app, which nothing here can wait on.
            override fun onAdClicked() {
                lastClickAt = SystemClock.elapsedRealtime()
                SmartTunnel.touch()
                LogUtil.i(AppConfig.TAG, "ads: ad tapped — the tunnel stays up for the redirect")
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                LogUtil.w(AppConfig.TAG, "ads: failed to show — ${error.message}")
                finish()
            }
        })
    }

    /** [show], as a suspend call that returns when the screen is free again. */
    suspend fun showAndAwait(activity: Activity, ad: LoadedAd) =
        suspendCancellableCoroutine { cont ->
            show(activity, ad) { if (cont.isActive) cont.resume(Unit) }
        }

    /**
     * Shows a rewarded ad and answers whether the user earned it.
     *
     * The two things are separate on purpose. Dismissal ends the ad; the
     * reward is a different event that only fires if the ad was watched far
     * enough, and a user who closes it early must not be paid. So the reward
     * is recorded when it arrives and reported when the screen comes back.
     */
    suspend fun showForReward(activity: Activity, ad: LoadedAd): Boolean {
        // Same gate as [show], for the same reason. In practice this ad is
        // fetched with no smart session — it rides the user's own tunnel —
        // so its epoch is -1 and this passes; it is here so that a rewarded
        // placement the operator switches to `require_smart` does not quietly
        // become the one unprotected show path in the app.
        if (!showable(ad, activity)) {
            LogUtil.w(AppConfig.TAG, "ads: not showing the rewarded ad — its tunnel is gone")
            return false
        }
        val earned = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        PanelStore.setAdLastShownAt(ad.placement.slot.wire, System.currentTimeMillis())
        suspendCancellableCoroutine { cont ->
            val done = {
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
            }
            ad.show(
                activity,
                object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() = done()

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        LogUtil.w(AppConfig.TAG, "ads: rewarded failed to show — ${error.message}")
                        done()
                    }
                },
                onReward = { earned.set(true) },
            )
        }
        return earned.get()
    }

    /**
     * True once this launch has carried a real user connection.
     *
     * The entry sequence — splash ad, second splash ad, the connect tap's own
     * ad — is one designed run of ads, and `min_interval_sec` must not carve
     * pieces out of it: with a 30-second interval the connect ad would be
     * skipped purely because the splash showed one moments earlier, which is
     * the sequence working as intended, not an ad shown too often. So the
     * interval is waived until the user's own tunnel has come up once, and
     * enforced from then on, where it means what the panel intends: how often
     * a *connected* user gets interrupted.
     *
     * In memory on purpose. A fresh process is a fresh entry — that is what
     * makes the waiver per-launch — and a process relaunched over a live
     * tunnel is told so by the home screen the moment it binds.
     */
    @Volatile
    var userConnectedThisLaunch = false

    /**
     * Whether `min_interval_sec` says this slot showed an ad too recently.
     * An interval of zero — the panel's default — never blocks, and nothing
     * blocks during the entry sequence: see [userConnectedThisLaunch].
     */
    fun tooSoon(placement: AdPlacement, now: Long = System.currentTimeMillis()): Boolean {
        if (placement.minIntervalSec <= 0) return false
        if (!userConnectedThisLaunch) {
            LogUtil.i(AppConfig.TAG, "ads: ${placement.slot.wire} interval waived — entry sequence")
            return false
        }
        val last = PanelStore.adLastShownAt(placement.slot.wire)
        return now - last < placement.minIntervalSec * 1000L
    }

    // ------------------------------------------------------------- loading --

    private fun requestLoad(
        context: Context,
        placement: AdPlacement,
        onResult: (LoadedAd?) -> Unit,
    ) {
        val request = AdRequest.Builder().build()
        // Stamped from the session that is up *now*, at the moment the request
        // goes out — which is the session this ad's traffic belongs to.
        val epoch = if (SmartTunnel.isActive) SmartTunnel.epoch else -1
        inFlight.incrementAndGet()
        val settled = AtomicBoolean(false)
        val finish = { ad: LoadedAd? ->
            if (settled.compareAndSet(false, true)) {
                inFlight.decrementAndGet()
                onResult(ad)
            }
        }

        when (placement.format) {
            AdFormat.APP_OPEN -> AppOpenAd.load(
                context, placement.adUnitId, request,
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        finish(LoadedAd(placement, appOpen = ad, sessionEpoch = epoch))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        LogUtil.w(AppConfig.TAG, "ads: app_open failed — ${error.message}")
                        finish(null)
                    }
                },
            )

            AdFormat.INTERSTITIAL -> InterstitialAd.load(
                context, placement.adUnitId, request,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        finish(LoadedAd(placement, interstitial = ad, sessionEpoch = epoch))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        LogUtil.w(AppConfig.TAG, "ads: interstitial failed — ${error.message}")
                        finish(null)
                    }
                },
            )

            AdFormat.REWARDED -> RewardedAd.load(
                context, placement.adUnitId, request,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        finish(LoadedAd(placement, rewarded = ad, sessionEpoch = epoch))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        LogUtil.w(AppConfig.TAG, "ads: rewarded failed — ${error.message}")
                        finish(null)
                    }
                },
            )

            AdFormat.REWARDED_INTERSTITIAL -> RewardedInterstitialAd.load(
                context, placement.adUnitId, request,
                object : RewardedInterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        finish(LoadedAd(placement, rewardedInterstitial = ad, sessionEpoch = epoch))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        LogUtil.w(AppConfig.TAG, "ads: rewarded interstitial failed — ${error.message}")
                        finish(null)
                    }
                },
            )

            // The panel can be set to formats this flow has no renderer for
            // yet (banner, native, rewarded). They are not errors — they are a
            // panel ahead of the build — but they cannot fill a full-screen
            // slot.
            else -> {
                LogUtil.w(
                    AppConfig.TAG,
                    "ads: ${placement.slot.wire} is ${placement.format.wire}, " +
                        "which the splash flow cannot show",
                )
                finish(null)
            }
        }
    }
}
