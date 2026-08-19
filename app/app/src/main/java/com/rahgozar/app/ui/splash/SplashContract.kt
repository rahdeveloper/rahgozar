package com.rahgozar.app.ui.splash

import com.rahgozar.app.panel.PanelSync

/**
 * What the splash is showing right now.
 *
 * The design specifies three messages that fade into one another. They are not
 * on a timer: each one is a real state of the sync, so the screen finishes when
 * the work does. A device on a fast network sees the middle message for a
 * moment or skips it; a device on a struggling one sits on it — which is
 * exactly the information the user wants at that moment.
 */
enum class SplashPhase {
    /** 5b: the mark, the wordmark, the sliding bar. Shown while work starts. */
    BRAND,

    /** «در حال دریافت سرورها» — resolving, registering, requesting. */
    FETCHING,

    /** «لطفاً صبر کنید» — still working after [SplashViewModel.PATIENCE_MS]. */
    PATIENCE,

    /**
     * The sync is over and the ad scenario has the screen: a tunnel is coming
     * up and an ad is being fetched through it.
     *
     * Its own phase rather than a second use of [PATIENCE], because the two
     * waits are not the same wait and the user can act on the difference. This
     * one asks them to stay put — leaving cancels the flow — and it is the only
     * phase where saying anything about ads is true.
     */
    AD_WAITING,

    /** «سرورها با موفقیت دریافت شد» — held briefly so it can be read. */
    SUCCESS,

    /**
     * The panel could not be reached, but this device already had a
     * configuration and carries on with it.
     *
     * Its own phase because it is its own truth. It used to borrow
     * [SUCCESS] — green tick, "servers loaded" — for a launch where nothing
     * had been loaded and no server had answered, which is the one thing a
     * status message must never do.
     */
    CACHED,

    /** The panel refused this device, or could not be reached at all. */
    FAILED,
}

/**
 * The two-line message for a phase.
 *
 * The design pairs a Persian headline with a Latin subtitle. In English that
 * pairing would be the same sentence twice, so the headline carries the chosen
 * language and the subtitle is only drawn when it says something different.
 */
data class SplashMessage(
    val headline: String,
    val subtitle: String,
    /** The success tick is only drawn for the one phase that earns it. */
    val showTick: Boolean = false,
)

/** Why the splash stopped without reaching the app. */
sealed interface SplashOutcome {
    /** Go to the main screen. */
    data object Continue : SplashOutcome

    /**
     * The sync is done and the panel has the splash ad slot switched on: run
     * the ad scenario first, then go to the main screen. The Activity owns
     * this because the scenario needs things a ViewModel must not hold — the
     * VPN consent sheet and a foreground Activity for the ad to cover.
     */
    data object AdFlow : SplashOutcome

    /** The panel will not serve this build or this device. */
    data class Refused(val decision: PanelGate) : SplashOutcome

    data object Unreachable : SplashOutcome
}

/** Alias kept narrow so the UI does not import the whole panel package. */
typealias PanelGate = com.rahgozar.app.panel.PanelGate.Decision

/** Maps a sync stage onto the phase the design draws for it. */
fun PanelSync.Stage.toPhase(): SplashPhase = when (this) {
    PanelSync.Stage.RESOLVING,
    PanelSync.Stage.REGISTERING,
    PanelSync.Stage.FETCHING,
    PanelSync.Stage.APPLYING -> SplashPhase.FETCHING
    PanelSync.Stage.DONE -> SplashPhase.SUCCESS
    PanelSync.Stage.FAILED -> SplashPhase.FAILED
}
