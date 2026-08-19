package com.rahgozar.app.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Does anything actually get through from here" — asked the simplest way there
 * is.
 *
 * One plain HTTP request from this process, on whatever network Android has
 * decided this app's packets take. That last part is the whole point: when the
 * app is inside a tun, this measures the tun, and it does so without any of the
 * machinery that makes the alternative unreliable.
 *
 * The alternative is a second sing-box core in another process, and it does not
 * work while this app rides a tunnel. That core auto-detects an interface and
 * binds its sockets to it — on the device it picked `rmnet_data4` — while the
 * kernel routes this app's uid into `tun0`. A socket bound to one and routed by
 * the other goes nowhere: the core's own log shows the DNS lookup for the
 * server's hostname hanging until something cancels it, eight seconds later,
 * every single time. Restarting the core does not help, because the binding is
 * re-decided the same way. There is nothing to fix in the core; the arrangement
 * is wrong.
 *
 * A bare [HttpURLConnection] has no such opinion. It is routed by uid like any
 * other socket in the app, which is exactly the question being asked.
 */
object Reachability {

    /**
     * Google's connectivity check: a 204 with no body and nothing identifying
     * in the request. Chosen over a service of our own because it is reachable
     * from everywhere a working tunnel reaches, and because an app that already
     * shows Google's ads is not telling a censor anything new by touching it.
     */
    private const val URL_204 = "https://www.gstatic.com/generate_204"

    /** One attempt's budget. Several fit inside a caller's deadline. */
    private const val ATTEMPT_MS = 3_000

    /** Long enough that a failed attempt is not immediately repeated. */
    private const val RETRY_MS = 400L

    /**
     * Keeps trying until something comes back or [timeoutMs] runs out.
     *
     * @return the round trip in milliseconds, or -1 if nothing answered. The
     *   number is a real measurement of the path, so a caller that wants to
     *   show a latency can use it rather than inventing one.
     */
    suspend fun measure(timeoutMs: Long): Long =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val at = SystemClock.elapsedRealtime()
                if (attempt()) return@withTimeoutOrNull SystemClock.elapsedRealtime() - at
                delay(RETRY_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            -1L
        } ?: -1L

    /** True as soon as anything answers at all. */
    suspend fun await(timeoutMs: Long): Boolean = measure(timeoutMs) >= 0

    private suspend fun attempt(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(URL_204).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = ATTEMPT_MS
                readTimeout = ATTEMPT_MS
                instanceFollowRedirects = false
                useCaches = false
            }
            try {
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
