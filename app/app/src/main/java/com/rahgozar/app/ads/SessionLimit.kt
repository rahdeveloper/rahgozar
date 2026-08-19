package com.rahgozar.app.ads

import android.content.Context
import android.os.SystemClock
import com.rahgozar.app.AppConfig
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.panel.AdsConfig
import com.rahgozar.app.panel.PanelSettings
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How long one connection is allowed to run, and how the user buys more.
 *
 * The panel decides both numbers: `session_duration_minutes` (zero means no
 * limit at all, which is what a panel that has never heard of this sends) and
 * `session_max_extensions`. Each extension is worth another full duration.
 *
 * The deadline is kept on the **boot clock**, not the wall clock, so changing
 * the phone's time cannot buy free hours, and it is kept in multi-process
 * storage because the two halves of this live apart: the screen sets it and
 * reads it, while the tunnel's own process is what counts it down.
 *
 * There is no alarm anywhere in here, and that is deliberate. Exact alarms
 * need a permission Google grants to clocks and calendars, not to VPN apps,
 * and an inexact one would cut a connection minutes late. The countdown
 * instead runs inside the tunnel's foreground service — a process that by
 * definition exists exactly as long as there is a connection to cut.
 */
object SessionLimit {

    private var countdown: Job? = null

    /** The panel's current settings, read fresh — they change on every sync. */
    private val settings: PanelSettings
        get() = PanelSettings.parse(PanelStore.settingsJson)

    /** True when the panel is running timed sessions at all. */
    val enabled: Boolean get() = settings.sessionLimited

    /**
     * Whether the panel offers an ad that buys more time.
     *
     * Read from the stored configuration rather than from [AdManager], because
     * this question is also asked in the tunnel's process, where nothing ever
     * called `AdManager.apply` and its in-memory copy is empty.
     */
    val extendOffered: Boolean
        get() = AdsConfig.parse(PanelStore.adsJson).placement(AdSlot.EXTEND_SESSION)
            .let { it.enabled && it.format.isRewarded }

    /**
     * Whether this app's own traffic is carried by the user's tunnel.
     *
     * True exactly when the extend offer is live, because that is when the ad
     * has to reach Google over the connection the user is on. Two very
     * different pieces of the app turn on this: which packages the tun
     * carries, and how a new connection is verified — a probe cannot judge a
     * tunnel from inside it. Stated once here so they cannot drift apart.
     */
    val ridesUserTunnel: Boolean
        get() = wantsRideUserTunnel && PanelStore.sessionRideActive

    /**
     * Whether the next tunnel *should* carry this app, settings permitting.
     *
     * This is the intent; [ridesUserTunnel] is the fact. The split exists
     * because the intent alone cannot decide: carrying this app inside a tun
     * means excluding the core's own uplink from it — or the tunnel eats
     * itself — and whether that exclusion is even possible is only known at
     * build time, from the addresses the config generator pinned. The tun
     * builder consults this, decides, and records the outcome; everyone else
     * reads the outcome.
     *
     * No Android version floor. On 13+ the exclusion is one excludeRoute
     * call; below that the tun builder carves the same holes out of the route
     * table by hand (RouteCarver) — either way the exception exists, so the
     * offer does too.
     */
    val wantsRideUserTunnel: Boolean
        get() = enabled && extendOffered

    /** One session's worth of time, in minutes. Also what one ad buys. */
    val durationMinutes: Int get() = settings.sessionDurationMinutes

    /** Milliseconds left, or null when nothing is limiting this connection. */
    fun remainingMillis(): Long? {
        val deadline = PanelStore.sessionDeadline
        if (deadline <= 0L) return null
        return (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /** Whether another ad may be watched for more time on this connection. */
    fun canExtend(): Boolean {
        if (!enabled || PanelStore.sessionDeadline <= 0L) return false
        val max = settings.sessionMaxExtensions
        return max == 0 || PanelStore.sessionExtensions < max
    }

    /**
     * Starts the clock for a connection that is about to begin.
     *
     * Called from the screen that asked for the connection, before the tunnel
     * is up, so the countdown covers the whole of it. A panel with no limit
     * clears the deadline instead, which is what makes turning the feature off
     * take effect on the next connect rather than the next release.
     */
    fun begin() {
        if (!enabled) {
            clear()
            return
        }
        PanelStore.sessionExtensions = 0
        PanelStore.sessionDeadline = SystemClock.elapsedRealtime() + durationMinutes * 60_000L
        LogUtil.i(AppConfig.TAG, "session: limited to $durationMinutes min")
    }

    /**
     * Adds another duration to the running connection.
     *
     * @return false when the panel's cap has been reached, so the caller can
     *   say so rather than showing an ad it cannot pay for.
     */
    fun extend(): Boolean {
        if (!canExtend()) return false
        PanelStore.sessionExtensions += 1
        PanelStore.sessionDeadline = maxOf(PanelStore.sessionDeadline, SystemClock.elapsedRealtime()) +
            durationMinutes * 60_000L
        LogUtil.i(
            AppConfig.TAG,
            "session: extended by $durationMinutes min (${PanelStore.sessionExtensions} used)",
        )
        return true
    }

    /** Forgets the limit. Called whenever a connection ends, however it ended. */
    fun clear() {
        PanelStore.sessionDeadline = 0L
        PanelStore.sessionExtensions = 0
        PanelStore.extendHoldUntil = 0L
    }

    /**
     * Keeps the tunnel alive while the user is buying more time.
     *
     * The rewarded ad has one network to load through, and it is the tunnel
     * the countdown is about to cut. Without this, a tap in the last seconds —
     * which is exactly when someone taps it — killed its own request: the
     * deadline arrived, the tunnel went down, and the ad came back "Internal
     * error" a moment later. Seen twice on the device, and from the user's
     * side the button simply did nothing.
     *
     * Bounded, so a flow that never finishes cannot hand out free time
     * forever: the hold outlives the ad's own load timeout and no more.
     */
    fun holdForExtend() {
        PanelStore.extendHoldUntil = SystemClock.elapsedRealtime() + EXTEND_HOLD_MS
    }

    /** Ends that hold, whatever came of the ad. */
    fun releaseExtendHold() {
        PanelStore.extendHoldUntil = 0L
    }

    /** Whether the countdown must wait rather than cut. */
    private fun extendHoldActive(): Boolean {
        val until = PanelStore.extendHoldUntil
        return until > 0L && SystemClock.elapsedRealtime() < until
    }

    /**
     * Runs the countdown inside the tunnel's own process and cuts the tunnel
     * when it reaches zero.
     *
     * Armed by each service once its tunnel is up. Re-reads the deadline every
     * tick rather than sleeping to a fixed time, because an extension bought
     * on the screen moves the deadline in a different process; polling a
     * shared value is what lets that arrive without any IPC of its own.
     */
    fun arm(context: Context) {
        disarm()

        // Never the ad flow's tunnel — it gets its own clock instead.
        //
        // This limit belongs to the user's connection. The smart tunnel is
        // the app's own, lives for seconds, and ends when the ad does — and
        // arming it here killed it forty milliseconds after it came up,
        // because a deadline left over from an earlier connection had already
        // passed. The two clocks have nothing to do with each other, which is
        // exactly why the ad flow's tunnel needs one of its own: nothing else
        // in this process would ever end it.
        if (PanelStore.smartSessionActive) {
            armSmartWatchdog(context)
            return
        }

        if (PanelStore.sessionDeadline <= 0L) return

        val app = context.applicationContext
        countdown = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val deadline = PanelStore.sessionDeadline
                // Cleared from elsewhere — the user disconnected, or the panel
                // turned the limit off. Nothing left to enforce.
                if (deadline <= 0L) return@launch

                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) {
                    // The user is watching an ad for more time right now. Its
                    // request is travelling through this very tunnel, so
                    // cutting here would destroy the thing that pays for the
                    // extension — see [holdForExtend].
                    if (extendHoldActive()) {
                        delay(TICK_MS)
                        continue
                    }
                    LogUtil.i(AppConfig.TAG, "session: time is up, stopping the tunnel")
                    clear()
                    LauncherManager.stopService(app)
                    return@launch
                }
                delay(left.coerceAtMost(TICK_MS))
            }
        }
    }

    /**
     * The last thing standing between a stranded ad session and a phone that
     * routes its owner's browsing through the operator's server all night.
     *
     * The ad flow is supposed to end its own tunnel, and on every ordinary
     * path it does. But the flow lives in the UI process and the tunnel does
     * not, so every way that process can go away — a back press on the
     * splash, the task swiped from recents, a crash, the system reclaiming
     * it — leaves a tunnel running with nobody left to close it. Nothing else
     * would: the user's session countdown deliberately skips these tunnels,
     * the home screen reports "disconnected" while one is up, and its
     * notification carries no Stop button.
     *
     * So the tunnel's own process holds a cap. It is deliberately generous —
     * far longer than any ad needs, because cutting a live one is the failure
     * this same file has already caused once — and its only job is to make
     * "forever" impossible.
     */
    private fun armSmartWatchdog(context: Context) {
        val app = context.applicationContext
        countdown = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(SMART_IDLE_TICK_MS)
                // Ended normally in the meantime, or replaced by a different
                // tunnel: either way this watchdog no longer has a subject.
                if (!PanelStore.smartSessionActive) return@launch

                val touched = PanelStore.smartSessionTouchedAt
                val now = SystemClock.elapsedRealtime()
                // `touched` ahead of `now` means the boot clock restarted under
                // it: the session is from before a reboot and there is nothing
                // left of it to protect.
                val idleFor = if (touched > now) Long.MAX_VALUE else now - touched
                if (idleFor < SMART_IDLE_MAX_MS) continue

                LogUtil.w(
                    AppConfig.TAG,
                    "ads: smart session idle for ${idleFor / 1000}s with no flow — stopping it",
                )
                // The whole session, not just the flag.
                //
                // This used to clear `smartSessionActive` by hand and leave the
                // run-guid override behind — which is the half that decides
                // *what starts next*. A reaped session therefore left every
                // later start pointing at the panel's smart profile: the
                // quick-settings tile, the widget, both shortcuts, and on a
                // phone with start-on-boot, the next reboot. The tunnel came
                // up, said "connected", and carried nothing, because that
                // profile's tun is scoped to this app alone.
                SmartTunnel.clearSession()
                LauncherManager.stopService(app)
                return@launch
            }
        }
    }

    /** Stops the countdown. The deadline itself is left alone. */
    fun disarm() {
        countdown?.cancel()
        countdown = null
    }

    /**
     * How often the countdown wakes to re-read the deadline.
     *
     * A second is precise enough for a cut the user was warned about, and
     * cheap: it is one memory read in a process that is already awake serving
     * the tunnel.
     */
    private const val TICK_MS = 1_000L

    /**
     * How long a smart session may sit with nothing happening.
     *
     * Idle, not total. The first version of this capped the session's whole
     * length and was caught on the device doing exactly what its own comment
     * warned against: it cut a live flow five seconds after an ad went up,
     * because the user had spent two minutes in the Play Store after tapping
     * the previous one. Nothing about that session was stranded — every part
     * of it was working — and the cap could not tell the difference.
     *
     * Measured from the last thing the flow did, so a session stays alive for
     * as long as it is being used and dies three minutes after it stops being
     * used. There is no legitimate flow that goes three minutes without
     * showing an ad, opening a session or being ended.
     */
    private const val SMART_IDLE_MAX_MS = 3 * 60_000L

    /** How often the watchdog re-reads that. Cheap: one memory read. */
    private const val SMART_IDLE_TICK_MS = 15_000L

    /**
     * How long the tunnel may outlive its deadline while an extension is
     * being bought.
     *
     * Comfortably longer than the rewarded ad's own load timeout plus the time
     * a user spends watching it, and short enough that a flow which dies
     * silently costs one minute of free connection, not an evening of it.
     */
    private const val EXTEND_HOLD_MS = 60_000L
}
