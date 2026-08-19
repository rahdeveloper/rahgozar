package com.rahgozar.app.util

import android.util.Log
import com.rahgozar.app.AppConfig
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.handler.MmkvManager
import java.util.Locale

object LogUtil {

    // A release build stays quiet: logs from a VPN client can carry addresses,
    // and a device in the field has nobody reading them. A debug build is by
    // definition being watched, and starting it silent means every diagnosis
    // begins with a rebuild.
    private val DEFAULT_LEVEL = if (BuildConfig.DEBUG) "debug" else "warning"
    private const val CACHE_UNSET = Int.MIN_VALUE

    @Volatile
    private var cachedMinPriority: Int = CACHE_UNSET

    private fun parsePriority(level: String?): Int {
        return when ((level ?: DEFAULT_LEVEL).lowercase(Locale.US)) {
            "verbose" -> Log.VERBOSE
            "debug" -> Log.DEBUG
            "info" -> Log.INFO
            "warn", "warning" -> Log.WARN
            "error" -> Log.ERROR
            "none", "off" -> Int.MAX_VALUE
            else -> Log.WARN
        }
    }

    @Suppress("unused")
    fun refreshLogLevel() {
        cachedMinPriority = parsePriority(MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL))
    }

    private fun minPriority(): Int {
        val cached = cachedMinPriority
        if (cached != CACHE_UNSET) {
            return cached
        }

        return synchronized(this) {
            val current = cachedMinPriority
            if (current != CACHE_UNSET) {
                current
            } else {
                // The stored level is read through MMKV, which throws until
                // MMKV.initialize() has run. A logger must never be the thing
                // that takes its caller down — and this one is called from catch
                // blocks, so it would replace a handled error with a crash.
                //
                // The app also runs a ":bg" process, and initialisation order
                // across processes is exactly where this bites.
                //
                // The fallback is deliberately not cached: MMKV is simply not up
                // yet, and freezing the default forever would ignore the level
                // the panel sends a moment later.
                val stored = runCatching {
                    MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL, DEFAULT_LEVEL)
                }
                if (stored.isSuccess) {
                    parsePriority(stored.getOrNull()).also { cachedMinPriority = it }
                } else {
                    parsePriority(DEFAULT_LEVEL)
                }
            }
        }
    }

    private fun isEnabled(priority: Int): Boolean {
        return priority >= minPriority()
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(priority)) return

        when {
            throwable == null -> Log.println(priority, tag, message)
            priority >= Log.ERROR -> Log.e(tag, message, throwable)
            priority == Log.WARN -> Log.w(tag, message, throwable)
            priority == Log.INFO -> Log.i(tag, message, throwable)
            priority == Log.DEBUG -> Log.d(tag, message, throwable)
            else -> Log.v(tag, message, throwable)
        }
    }

    fun d(tag: String = AppConfig.TAG, message: String) = log(Log.DEBUG, tag, message)
    fun i(tag: String = AppConfig.TAG, message: String) = log(Log.INFO, tag, message)
    fun w(tag: String = AppConfig.TAG, message: String) = log(Log.WARN, tag, message)
    fun e(tag: String = AppConfig.TAG, message: String) = log(Log.ERROR, tag, message)

    fun d(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String = AppConfig.TAG, message: String, throwable: Throwable) = log(Log.ERROR, tag, message, throwable)
}

