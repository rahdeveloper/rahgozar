package com.rahgozar.app.service

import android.content.Context
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * Process-wide setup for the sing-box core, shared by the tunnel and the
 * delay test.
 *
 * Both live in their own process — two Go runtimes cannot share one, see
 * docs/SINGBOX-INTEGRATION.md — and both need exactly this done once before
 * any other libbox call, so it lives here rather than being written twice.
 */
internal object SingBoxNative {

    private const val TAG = "SingBox-Native"

    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var setUp = false

    /**
     * Loaded explicitly rather than through go.Seq's static initialiser, for
     * the same reason OpenVpnService loads libovpncli explicitly: a device
     * whose ABI we did not ship should see "this server cannot run here", not
     * a process crash.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("box") }
            .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: cannot load libbox", it) }
            .isSuccess
        loaded = ok
        return ok
    }

    /**
     * Libbox.setup fixes paths and redirects stderr for the whole process, so
     * it runs once per process — and each process passes its own [dirName], so
     * the tunnel and a test running beside it never share a working directory.
     *
     * @param dirName directory under filesDir/cacheDir this process owns
     * @param crashSource names the crash log this process writes
     */
    /** Where the tunnel writes its core log on a debug build. */
    @Volatile
    private var basePath: String? = null

    fun tunnelLogPath(): String = "${basePath.orEmpty()}/core.log"

    @Synchronized
    fun ensureSetUp(context: Context, dirName: String, crashSource: String) {
        if (setUp) return
        // In these processes the shared go.Seq binds libbox (see go/Seq.java),
        // so this hands the app context to the sing-box runtime.
        go.Seq.setContext(context.applicationContext)
        val base = File(context.filesDir, dirName).apply { mkdirs() }
        basePath = base.path
        Libbox.setup(SetupOptions().apply {
            basePath = base.path
            workingPath = File(base, "run").path
            tempPath = File(context.cacheDir, dirName).path
            // Works around golang/go#68760: a Go callback landing on a
            // Java-created thread can crash unwinding the stack.
            fixAndroidStack = true
            crashReportSource = crashSource
            logMaxLines = 300L
        })
        setUp = true
    }
}
