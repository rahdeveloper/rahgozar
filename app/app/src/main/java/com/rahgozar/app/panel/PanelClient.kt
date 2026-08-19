package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the panel: register once, then bootstrap on every launch.
 *
 * Every response is verified against the signing key baked into the app before
 * a single field is read, so a mirror, a proxy holding a valid certificate for
 * whatever domain we reached, or a captive portal can all fail this call but
 * none of them can steer it.
 */
class PanelClient(
    private val signPublicKey: ByteArray,
    private val exchPublicKey: ByteArray,
    timeoutMs: Long = 15_000,
    client: OkHttpClient? = null,
) {
    private val gson = Gson()

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        // The panel's address is discovered, not configured, so a redirect
        // would mean following something we did not choose. Discovery decides
        // where to talk; this client only talks there.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // ------------------------------------------------------------- register --

    data class RegisterRequest(
        @SerializedName("public_key") val publicKey: String,
        @SerializedName("package") val packageName: String,
        @SerializedName("cert_sha256") val certSha256: String,
        @SerializedName("version_code") val versionCode: Int,
        @SerializedName("version_name") val versionName: String,
        @SerializedName("android_sdk") val androidSdk: Int,
    )

    data class ServerKeys(
        @SerializedName("key_id") val keyId: String = "",
        @SerializedName("sign_public_key") val signPublicKey: String = "",
        @SerializedName("exch_public_key") val exchPublicKey: String = "",
        @SerializedName("envelope_version") val envelopeVersion: Int = 0,
    )

    data class Registration(
        @SerializedName("device_id") val deviceId: String = "",
        @SerializedName("token") val token: String = "",
        @SerializedName("expires_at") val expiresAt: Long = 0,
        @SerializedName("keys") val keys: ServerKeys? = null,
    )

    /**
     * Registers this device and returns its token.
     *
     * Idempotent at the panel: registering again with the same key returns the
     * same device id, which is what makes re-registering the app's recovery
     * path when a token is rejected.
     */
    @Throws(PanelException::class)
    fun register(baseUrl: String, device: DeviceKeyPair, request: RegisterRequest): Registration {
        val body = gson.toJson(request.copy(publicKey = Base64Url.encode(device.publicKey)))
        val http = Request.Builder()
            .url(endpoint(baseUrl, "/v1/device/register"))
            .post(body.toRequestBody(JSON))
            .build()

        val payload = exchange(http, PanelEnvelope.CTX_REGISTER)
        val registration = parse(payload, Registration::class.java)
        if (registration.token.isEmpty() || registration.deviceId.isEmpty()) {
            throw PanelException("registration response is missing a token")
        }

        // The panel echoes the key set it signed with. A mismatch means this
        // build is talking to a panel whose keys have rotated past it: the
        // signature still verified, so this is not an attack, it is an app that
        // needs updating — and saying so beats looking like a network fault.
        registration.keys?.let { keys ->
            if (keys.signPublicKey.isNotEmpty() &&
                keys.signPublicKey != Base64Url.encode(signPublicKey)
            ) {
                throw PanelException("this build is too old for the panel's current keys")
            }
        }
        return registration
    }

    // ------------------------------------------------------------ bootstrap --

    data class Geo(
        @SerializedName("country") val country: String = "",
        @SerializedName("smart_required") val smartRequired: Boolean = false,
    )

    data class Bootstrap(
        @SerializedName("config_version") val configVersion: Long = 0,
        @SerializedName("issued_at") val issuedAt: Long = 0,
        @SerializedName("geo") val geo: Geo = Geo(),
        /**
         * Every row of the panel's settings table, verbatim. Deliberately a
         * raw element and not a fixed class: the panel can add a setting and
         * every installed device reads it on the next launch, needing app code
         * only when the app has to act on it. See [PanelSettings].
         */
        @SerializedName("settings") val settings: JsonElement? = null,
        @SerializedName("ads") val ads: JsonElement? = null,
        @SerializedName("servers") val servers: SealedBox? = null,
        @SerializedName("smart") val smart: SealedBox? = null,
        @SerializedName("content_key") val contentKey: SealedBox? = null,
    )

    /** [Bootstrap] plus whatever this device could decrypt out of it. */
    class BootstrapResult(
        val bootstrap: Bootstrap?,
        val serversJson: String?,
        val smartJson: String?,
        val etag: String?,
        /** True when the panel answered 304: what we already have is current. */
        val notModified: Boolean,
    ) {
        /** The panel's switches for this launch. Defaults when nothing arrived. */
        val settings: PanelSettings
            get() = bootstrap?.settings?.let { PanelSettings.from(it) } ?: PanelSettings.empty()

        /** The ad configuration for this device. Disabled when nothing arrived. */
        val ads: AdsConfig
            get() = bootstrap?.ads?.let { AdsConfig.from(it) } ?: AdsConfig.disabled()

        /**
         * Whether this device should bring up the Smart tunnel before asking
         * for a fill. Decided by the panel, which sees the real address — a
         * client-side country check would be trivial to switch off.
         */
        val smartRequired: Boolean get() = bootstrap?.geo?.smartRequired ?: false
    }

    /**
     * Fetches the bootstrap document and opens the parts addressed to this
     * device.
     *
     * @param etag the value from the previous call, so an unchanged
     *   configuration costs one round trip and no decryption.
     */
    @Throws(PanelException::class)
    fun bootstrap(
        baseUrl: String,
        token: String,
        device: DeviceKeyPair,
        etag: String? = null,
    ): BootstrapResult {
        val builder = Request.Builder()
            .url(endpoint(baseUrl, "/v1/bootstrap"))
            .header("Authorization", "Bearer $token")
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw PanelException("cannot reach the panel: ${e.message}")
        }

        response.use {
            if (it.code == 304) {
                return BootstrapResult(null, null, null, etag, notModified = true)
            }
            val body = readOrThrow(it)
            val payload = verify(body, PanelEnvelope.CTX_BOOTSTRAP)
            val bootstrap = parse(payload, Bootstrap::class.java)

            val deviceKey = PanelCrypto.deviceKey(exchPublicKey, device)
            val wrapped = bootstrap.contentKey
                ?: throw PanelException("bootstrap carried no content key")
            val contentKey = PanelCrypto.unwrapContentKey(deviceKey, wrapped)

            val servers = bootstrap.servers?.let {
                String(PanelCrypto.openContent(contentKey, it), Charsets.UTF_8)
            }
            val smart = bootstrap.smart?.let {
                String(PanelCrypto.openContent(contentKey, it), Charsets.UTF_8)
            }
            return BootstrapResult(
                bootstrap = bootstrap,
                serversJson = servers,
                smartJson = smart,
                etag = it.header("ETag"),
                notModified = false,
            )
        }
    }

    // ---------------------------------------------------------------- inner --

    private fun exchange(request: Request, context: String): ByteArray {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PanelException("cannot reach the panel: ${e.message}")
        }
        response.use { return verify(readOrThrow(it), context) }
    }

    private fun readOrThrow(response: okhttp3.Response): String {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) return body

        // The panel's error codes are the only part of a failure worth acting
        // on; the message is for logs. Anything that is not our envelope shape
        // is something else answering, and reads as a plain failure.
        val code = runCatching {
            gson.fromJson(body, ErrorEnvelope::class.java)?.error?.code
        }.getOrNull()
        throw PanelException(
            "panel returned ${response.code}",
            code = code.orEmpty(),
            httpStatus = response.code,
            // Integer delta-seconds only. The header's other legal form is an
            // HTTP date, which the panel never sends; from anything else on
            // the path it is not worth acting on.
            retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
        )
    }

    private fun verify(body: String, context: String): ByteArray {
        val envelope = try {
            gson.fromJson(body, Envelope::class.java)
        } catch (e: JsonSyntaxException) {
            throw PanelException("response is not a signed document")
        } ?: throw PanelException("empty response")

        return try {
            PanelEnvelope.open(signPublicKey, context, envelope)
        } catch (e: EnvelopeException) {
            throw PanelException("signature rejected: ${e.message}")
        }
    }

    private fun <T> parse(payload: ByteArray, type: Class<T>): T = try {
        gson.fromJson(String(payload, Charsets.UTF_8), type)
            ?: throw PanelException("empty payload")
    } catch (e: JsonSyntaxException) {
        throw PanelException("malformed payload")
    }

    /**
     * Builds a panel URL, refusing anything that is not https.
     *
     * Discovery already rejects non-https addresses and so does the panel when
     * an endpoint is stored, which is exactly why this third check is cheap:
     * the one that matters is the one closest to the socket.
     */
    private fun endpoint(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        if (!base.startsWith("https://")) throw PanelException("refusing a non-https panel address")
        return base + path
    }

    private data class ErrorEnvelope(@SerializedName("error") val error: ErrorBody?) {
        data class ErrorBody(
            @SerializedName("code") val code: String = "",
            @SerializedName("message") val message: String = "",
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * A failed exchange with the panel.
 *
 * [code] is the panel's own error code when it sent one — `device_blocked` and
 * `attestation_failed` mean something different to the app than a timeout does.
 */
class PanelException(
    message: String,
    val code: String = "",
    val httpStatus: Int = 0,
    /** From the Retry-After header, in seconds, when the panel named a wait. */
    val retryAfterSeconds: Long? = null,
) : Exception(message) {

    /** The token is gone or invalid: register again. */
    val needsRegistration get() = httpStatus == 401

    /** An admin blocked this device. Registering again will not help. */
    val isBlocked get() = code == "device_blocked"

    /** This build is not in the panel's allowlist — a repack, or an unregistered signing key. */
    val isAttestationFailure get() = code == "attestation_failed"

    /**
     * The panel is shedding load — which is the panel *working*, from an
     * address shared with too many other installs at once. The one wrong
     * response is the reflexive one: retrying through every other address
     * reaches the same limiter with the same verdict, spending more of the
     * shared budget on the way.
     */
    val isRateLimited get() = httpStatus == 429
}
