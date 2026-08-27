package com.rahgozar.app.root

/**
 * `Process.waitFor(timeout, unit)` for the Androids this app supports.
 *
 * The timed overload arrived in API 26 and this app's minSdk is 24, so on
 * Android 7.0 and 7.1 calling it raises `NoSuchMethodError`. That is an
 * **Error**, not an Exception, so the `catch (e: Exception)` around both call
 * sites did not catch it: instead of "no root available" the probe would take
 * the whole app down, on the one kind of device — rooted, old — most likely to
 * have `su` on the path in the first place.
 *
 * Polling rather than a watchdog thread because that is all the timeout is for.
 * Both callers drain the process's output to EOF first, which already blocks
 * until it exits; this only stops a process that closed its pipes and then hung
 * from doing so forever.
 *
 * @return true if the process exited within the timeout
 */
internal fun Process.waitForCompat(timeoutMillis: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            // Still running — the only way to ask before API 26.
        }
        if (System.nanoTime() >= deadline) return false
        try {
            Thread.sleep(POLL_INTERVAL_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
}

/** Short enough that a fast command is not held up, long enough not to spin. */
private const val POLL_INTERVAL_MS = 20L
