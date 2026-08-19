package com.rahgozar.app.core

import android.content.Context
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object CoreNativeManager {
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                LogUtil.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            LogUtil.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }

    fun reconcileBrowserDialer(dialerAddr: String) {
        try {
            Libv2ray.reconcileBrowserDialer(dialerAddr)
            LogUtil.i(AppConfig.TAG, "Browser dialer reconciled successfully with address: $dialerAddr")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to reconcile browser dialer with address: $dialerAddr", e)
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * A server that does not answer is this function's **result**, not a fault
     * in the app — finding that out is what a measurement is for, and a sweep
     * of sixty servers is expected to produce a pile of them. It was logged as
     * an error with a full stack trace anyway: eleven lines per dead server,
     * ten of which were our own call path, repeated for every cancelled smart
     * probe. Real errors were hard to find among them, which for a log this
     * design depends on reading is a cost of its own.
     *
     * [go.error] is the discriminator, and an exact one: gobind raises it only
     * when the Go side *returns* an error, so it means the probe ran and came
     * back with a verdict. Its message is the part worth keeping — "TLS
     * handshake timeout", "context canceled", "connection refused" — and it
     * says more about the server than the trace ever did. Anything else really
     * is the native call breaking and keeps its error and its trace.
     *
     * Tested for after the catch rather than caught directly: `go.error` is an
     * interface and the class gobind actually throws (`go.Universe.proxyerror`)
     * is private, so it cannot appear in a catch clause.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            if (e is go.error) {
                LogUtil.i(AppConfig.TAG, "probe: no answer — ${e.message}")
            } else {
                LogUtil.e(AppConfig.TAG, "Failed to measure outbound delay", e)
            }
            -1L
        }
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}