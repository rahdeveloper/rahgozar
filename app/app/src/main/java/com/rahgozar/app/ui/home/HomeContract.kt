package com.rahgozar.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.rahgozar.app.ui.brand.BrandPalette

/** Where the tunnel is, as the dial draws it. */
enum class LinkState { OFF, CONNECTING, ON }

/** Why the home screen is showing a warning. */
enum class HomeNotice {
    /** The tunnel came up, but nothing came back through it. */
    SERVER_NOT_RESPONDING,

    /** A minute left before the panel's session limit cuts the tunnel. */
    SESSION_ENDING,

    /** The user closed the rewarded ad before it counted. */
    REWARD_NOT_EARNED,

    /** This connection has had all the extensions the panel allows. */
    EXTENSIONS_USED_UP,

    /** The extend ad was asked for and nothing came back. */
    AD_NOT_AVAILABLE,
}

/**
 * The home screen's whole state.
 *
 * Deliberately not the raw service state: the screen needs a handful of derived
 * values (the clock string, the quality word, the tape heights) and computing
 * them in the composable would recompute them on every frame of an animation.
 */
@Immutable
data class HomeUiState(
    val link: LinkState = LinkState.OFF,
    /** Seconds since the tunnel came up. */
    val elapsed: Long = 0,

    val serverName: String = "",
    /**
     * Held but never rendered. Host and port are the server's identity: put
     * them on a screen and a screenshot becomes a working address, which is the
     * leak this whole design exists to avoid.
     */
    val serverAddress: String = "",
    val serverProtocol: String = "",
    /** ISO country code from the panel, for the flag. Empty when unknown. */
    val serverCountry: String = "",
    /**
     * Latest measured latency in milliseconds.
     *
     * Three distinct values, and the screen must not blur them: positive is a
     * measurement, zero means never tested, negative means the test ran and the
     * server did not answer.
     */
    val pingMs: Long = 0,
    val hasServer: Boolean = false,

    val upBytesPerSec: Long = 0,
    val downBytesPerSec: Long = 0,
    /** Cumulative bytes this session, split by direction. */
    val sessionUpBytes: Long = 0,
    val sessionDownBytes: Long = 0,

    /**
     * The last [TAPE_SAMPLES] speed readings, oldest first.
     *
     * The design generated its tape from a sine wave — decorative, and it lies
     * the moment anyone looks twice. These are the real samples, so a flat tape
     * means nothing is moving and a spike means a burst actually happened.
     */
    val downHistory: List<Long> = emptyList(),
    val upHistory: List<Long> = emptyList(),

    /** True while a latency test is running, so the row can say so. */
    val testing: Boolean = false,

    /**
     * Seconds left before the panel's session limit ends this connection, or
     * null when nothing is limiting it. Null and zero mean different things,
     * so the screen must not collapse them.
     */
    val remainingSeconds: Long? = null,

    /** Minutes one rewarded ad adds, or 0 when the offer is not available. */
    val extendMinutes: Int = 0,

    /** True while the rewarded ad for an extension is being fetched or shown. */
    val extending: Boolean = false,

    /**
     * Something the user needs told, or null.
     *
     * Carried as a reason rather than a finished sentence. Every other screen
     * in the redesign holds both languages next to each other and picks with
     * LocalLanguage; a string resolved here would come from the Application
     * context instead, which follows the phone's language and not the app's —
     * which is exactly how this line ended up stuck in English.
     */
    val notice: HomeNotice? = null,
) {
    val isOn get() = link == LinkState.ON
    val isConnecting get() = link == LinkState.CONNECTING
    val isIdle get() = link == LinkState.OFF

    /** hh:mm:ss while connected; the design's placeholder otherwise. */
    val clock: String
        get() = if (isOn) {
            "%02d:%02d:%02d".format(elapsed / 3600, (elapsed / 60) % 60, elapsed % 60)
        } else {
            "--:--:--"
        }

    /**
     * What used to be «IP · mode».
     *
     * The address is gone by decision: reading it needs either a third party
     * that then knows this device, or a round trip that says nothing the user
     * cannot already see. What is left is the part that answers the actual
     * question — is my traffic going through the tunnel or not.
     */
    val routeToken: String get() = if (isOn) "tunnel" else "direct"

    val sessionTotalBytes get() = sessionUpBytes + sessionDownBytes

    /** Whether to offer the user more time in exchange for an ad. */
    val canExtend get() = isOn && extendMinutes > 0

    /** «۵۹:۳۰ مانده» — the remaining time, or empty when unlimited. */
    val remainingClock: String
        get() = remainingSeconds?.let {
            if (it >= 3600) {
                "%d:%02d:%02d".format(it / 3600, (it / 60) % 60, it % 60)
            } else {
                "%02d:%02d".format(it / 60, it % 60)
            }
        }.orEmpty()

    /**
     * The figure inside the dial.
     *
     * A limited session counts **down**, in the same place the unlimited one
     * counts up. Two clocks on one screen was the alternative, and it cost a
     * whole line of height on a layout that already reached the server bar —
     * on shorter phones the throughput tapes were pushed off the screen
     * entirely. It is also the better of the two numbers: when a connection has
     * a deadline, how long it has been running is trivia.
     */
    val dialClock: String get() = when {
        !isOn -> "--:--:--"
        remainingSeconds != null -> remainingClock
        else -> clock
    }

    /** True when the dial is showing a deadline, and should say so in red. */
    val dialClockEnding get() = isOn && (remainingSeconds ?: Long.MAX_VALUE) <= 60L
}

/** How many samples a tape shows. One per speed broadcast. */
const val TAPE_SAMPLES = 32

/** Same as [pingColor], exposed for the server list. */
fun pingColorPublic(pingMs: Long, palette: BrandPalette): Color = pingColor(pingMs, palette)

/**
 * Latency bands.
 *
 * The design's thresholds were 50/110 ms, which belong to a speed test. This is
 * a **real delay** measurement — a full round trip through the tunnel to a test
 * endpoint — and on a mobile network in a censored country 200 ms is an
 * ordinary, perfectly usable connection. Calling that "poor" in red would train
 * users to distrust servers that work fine.
 *
 * So: usable up to 300 ms, strained to 600, and only past that is it a problem.
 */
const val PING_GOOD_MS = 300L
const val PING_FAIR_MS = 600L

fun pingColor(pingMs: Long, palette: BrandPalette): Color = when {
    pingMs < 0L -> palette.danger
    pingMs == 0L -> palette.dim
    pingMs <= PING_GOOD_MS -> palette.accent
    pingMs <= PING_FAIR_MS -> if (palette.isDark) Color(0xFFDCC069) else Color(0xFFA8760F)
    else -> palette.danger
}

/**
 * Bar heights in dp for a tape, from real samples.
 *
 * Scaled against the tallest sample in the window rather than an absolute
 * ceiling: a 200 KB/s connection and a 20 MB/s one should both fill the tape,
 * because what the tape communicates is the *shape* of the traffic, not its
 * magnitude — the number beside it already gives the magnitude.
 *
 * A window with nothing in it draws the 2dp resting line the design uses.
 */
fun tapeBars(history: List<Long>): List<Float> {
    val window = List(TAPE_SAMPLES) { i ->
        val offset = history.size - TAPE_SAMPLES + i
        if (offset >= 0) history[offset] else 0L
    }
    val peak = window.maxOrNull() ?: 0L
    if (peak <= 0L) return List(TAPE_SAMPLES) { 2f }
    return window.map { 2f + 20f * (it.toFloat() / peak.toFloat()) }
}

/** Bytes per second in the design's "0.00 MB/S" form. */
fun formatSpeed(bytesPerSec: Long): String =
    "%.2f".format(bytesPerSec / 1_000_000.0)

/** A byte total in the shortest unit that keeps it readable. */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 KB"
    bytes < 1_000_000 -> "%.1f KB".format(bytes / 1000.0)
    bytes < 1_000_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.2f GB".format(bytes / 1_000_000_000.0)
}

/** Session total as the design writes it: "SESSION 18.7 MB". */
fun formatSession(bytes: Long): String = "SESSION " + formatBytes(bytes)
