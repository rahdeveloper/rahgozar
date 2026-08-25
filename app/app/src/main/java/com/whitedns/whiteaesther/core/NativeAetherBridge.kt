package com.whitedns.whiteaesther.core

// Vendored verbatim from WhiteAestherMobile (AGPL-3.0). The JNI symbols exported
// by libwhiteaesther_core.so are bound to this exact package + class name
// (Java_com_whitedns_whiteaesther_core_NativeAetherBridge_*), so the class must
// keep this package for System.loadLibrary linkage to resolve. Rahgozar-side code
// talks to it through com.rahgozar.app.service.AetherVpnService only.

import org.json.JSONObject

fun interface NativeSocketProtector {
    fun protectSocket(fd: Int): Boolean
}

fun interface NativeEngineListener {
    fun onNativeReady()
}

data class PreparedEngine(
    val ipv4: String,
    val ipv6: String,
    val peer: String,
)

data class NativeResult(
    val ok: Boolean,
    val error: String? = null,
)

data class EndpointScanResult(
    val peer: String,
    val rttMillis: Long,
)

object NativeAetherBridge {
    val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("whiteaesther_core") }
    }

    val isLoaded: Boolean
        get() = loadResult.isSuccess

    fun versionOrNull(): String? = loadResult.mapCatching { nativeVersion() }.getOrNull()

    fun exportIdentity(configPath: String): Result<String> =
        call { nativeExportIdentity(configPath) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Nothing to export") }
            json.getString("payload")
        }

    fun importIdentity(configPath: String, payload: String): NativeResult =
        call { nativeImportIdentity(configPath, payload) }.toResult()

    fun drainLog(): List<String> {
        if (!isLoaded) return emptyList()
        return runCatching { nativeDrainLog()?.filterNotNull().orEmpty() }.getOrDefault(emptyList())
    }

    fun validate(configJson: String): NativeResult = call { validateConfig(configJson) }.toResult()

    fun prepare(configJson: String): Result<PreparedEngine> = call { nativePrepare(configJson) }
        .fold(
            onSuccess = { raw ->
                runCatching {
                    val json = JSONObject(raw)
                    check(json.optBoolean("ok")) { json.optString("error", "Preparation failed") }
                    PreparedEngine(
                        ipv4 = json.getString("ipv4"),
                        ipv6 = json.getString("ipv6"),
                        peer = json.getString("peer"),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )

    fun scan(configJson: String): Result<List<EndpointScanResult>> = call { nativeScan(configJson) }
        .mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Endpoint scan failed") }
            val results = json.getJSONArray("results")
            List(results.length()) { index -> results.getJSONObject(index).toEndpointScanResult() }
        }

    fun testEndpoint(configJson: String): Result<EndpointScanResult> = call {
        nativeTestEndpoint(configJson)
    }.mapCatching { raw ->
        val json = JSONObject(raw)
        check(json.optBoolean("ok")) { json.optString("error", "Endpoint test failed") }
        json.toEndpointScanResult()
    }

    fun cancelScan(): Boolean = isLoaded && nativeCancelScan()

    fun run(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): NativeResult = call {
        nativeRun(configJson, preparedPeer, tunFd, listener)
    }.toResult()

    fun stop(): Boolean = isLoaded && nativeStop()

    fun setSocketProtector(protector: NativeSocketProtector?) {
        loadResult.getOrThrow()
        nativeSetSocketProtector(protector)
    }

    private inline fun call(block: () -> String): Result<String> = loadResult.fold(
        onSuccess = { runCatching(block) },
        onFailure = { Result.failure(it) },
    )

    private fun Result<String>.toResult(): NativeResult = fold(
        onSuccess = { raw ->
            runCatching {
                val json = JSONObject(raw)
                NativeResult(
                    ok = json.optBoolean("ok"),
                    error = json.optString("error").takeIf(String::isNotBlank),
                )
            }.getOrElse { NativeResult(false, "Invalid native response: ${it.message}") }
        },
        onFailure = { NativeResult(false, "Native core unavailable: ${it.message}") },
    )

    private fun JSONObject.toEndpointScanResult() = EndpointScanResult(
        peer = getString("peer"),
        rttMillis = getLong("rttMs"),
    )

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun validateConfig(configJson: String): String
    private external fun nativeDrainLog(): Array<String?>?
    private external fun nativeExportIdentity(configPath: String): String
    private external fun nativeImportIdentity(configPath: String, payload: String): String

    @JvmStatic
    private external fun nativePrepare(configJson: String): String

    @JvmStatic
    private external fun nativeScan(configJson: String): String

    @JvmStatic
    private external fun nativeTestEndpoint(configJson: String): String

    @JvmStatic
    private external fun nativeCancelScan(): Boolean

    @JvmStatic
    private external fun nativeRun(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): String

    @JvmStatic
    private external fun nativeStop(): Boolean

    @JvmStatic
    private external fun nativeSetSocketProtector(protector: NativeSocketProtector?)
}
