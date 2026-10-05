package com.rahgozar.app.util

import android.util.Log
import com.rahgozar.app.AppConfig
import com.rahgozar.app.BuildConfig

object LogUtil {

    /**
     * The log level, decided by the build and by nothing at runtime.
     *
     * A release build logs warnings and errors: logs from a VPN client carry
     * addresses and destinations, and a device in the field has nobody reading
     * them — but the warnings stay, because they are what a field device's log
     * is read for (why a sync failed, why the panel refused). A debug build is
     * ours, being watched, and logs everything; starting it quieter means every
     * diagnosis begins with a rebuild.
     *
     * It used to be the panel's to set (`tunnel_log_level`), and a panel left
     * on "debug" after a test turned every release install up to debug with
     * it — the core's destinations and, before 2.4.5, every decrypted server
     * configuration, readable over USB without root.
     *
     * Also the Xray core's level ([com.rahgozar.app.core.CoreConfigManager]),
     * and sing-box's in its own spelling ([com.rahgozar.app.service.SingBoxConfig]).
     */
    val LEVEL: String = if (BuildConfig.DEBUG) "debug" else "warning"

    private val MIN_PRIORITY = if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (priority < MIN_PRIORITY) return

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
