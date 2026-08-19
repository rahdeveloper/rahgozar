package com.rahgozar.app.panel

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The signed wrapper around everything the app consumes from the panel.
 *
 * Wire format and the exact signed byte string are specified in docs/CRYPTO.md;
 * the authority is panel/internal/cryptobox/sign.go, and both sides are held to
 * the same generated vectors (see PanelCryptoTest).
 */
data class Envelope(
    @SerializedName("v") val version: Int = 0,
    @SerializedName("alg") val alg: String = "",
    @SerializedName("kid") val keyId: String = "",
    @SerializedName("ctx") val context: String = "",
    @SerializedName("ts") val issuedAt: Long = 0,
    @SerializedName("payload") val payload: String = "",
    @SerializedName("sig") val sig: String = "",
)

/** Thrown for every rejected envelope. The reason is for logs, never for a decision. */
class EnvelopeException(message: String) : Exception(message)

object PanelEnvelope {
    /** Bumped only when the signing input layout changes. */
    const val VERSION = 1

    // Signing contexts. Each signature commits to one, so a bundle signature can
    // never be replayed as a bootstrap response.
    const val CTX_BOOTSTRAP = "brandvpn/v1/bootstrap"
    const val CTX_SERVERS = "brandvpn/v1/servers"
    const val CTX_ENDPOINTS = "brandvpn/v1/endpoints"
    const val CTX_REGISTER = "brandvpn/v1/register"

    private const val KEY_ID_LEN = 8
    private const val SIGNATURE_LEN = 64
    private const val PUBLIC_KEY_LEN = 32

    private val MAGIC = byteArrayOf(
        'b'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 'n'.code.toByte(),
        'd'.code.toByte(), 'v'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(),
        '-'.code.toByte(), 's'.code.toByte(), 'i'.code.toByte(), 'g'.code.toByte(),
        '-'.code.toByte(), 'v'.code.toByte(), '1'.code.toByte(), 0,
    )

    /**
     * Verifies an envelope and returns the payload bytes.
     *
     * [expectedContext] comes from the caller's own expectation, never from the
     * envelope: trusting `ctx` off the wire would defeat the domain separation
     * that stops one valid signature being reused as a different response.
     *
     * The signature is checked over the raw payload bytes and the caller parses
     * only what is returned here, so there is no canonical-JSON problem to get
     * wrong — and nothing inside the payload is ever looked at before the
     * signature holds.
     */
    @Throws(EnvelopeException::class)
    fun open(signPublicKey: ByteArray, expectedContext: String, envelope: Envelope): ByteArray {
        if (signPublicKey.size != PUBLIC_KEY_LEN) {
            throw EnvelopeException("signing key is ${signPublicKey.size} bytes, expected $PUBLIC_KEY_LEN")
        }
        if (envelope.version != VERSION) {
            throw EnvelopeException("unsupported envelope version ${envelope.version}")
        }
        if (envelope.alg != "Ed25519") {
            throw EnvelopeException("unsupported algorithm ${envelope.alg}")
        }
        if (envelope.context != expectedContext) {
            throw EnvelopeException("context ${envelope.context}, expected $expectedContext")
        }

        val keyId = Base64Url.decodeOrNull(envelope.keyId)
            ?: throw EnvelopeException("malformed key id")
        if (keyId.size != KEY_ID_LEN) throw EnvelopeException("malformed key id")

        val payload = Base64Url.decodeOrNull(envelope.payload)
            ?: throw EnvelopeException("malformed payload")

        val sig = Base64Url.decodeOrNull(envelope.sig)
            ?: throw EnvelopeException("malformed signature")
        if (sig.size != SIGNATURE_LEN) throw EnvelopeException("malformed signature")

        val input = signingInput(envelope.context, keyId, envelope.issuedAt, payload)
        try {
            Ed25519Verify(signPublicKey).verify(sig, input)
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("signature verification failed")
        }
        return payload
    }

    /**
     * The exact bytes covered by the signature.
     *
     *     magic         16 bytes  "brandvpn-sig-v1\0"
     *     version       uint16 BE
     *     len(ctx)      uint16 BE
     *     ctx           UTF-8
     *     keyId          8 bytes
     *     issuedAt      int64 BE
     *     len(payload)  uint32 BE
     *     payload
     *
     * Every variable-length field is length-prefixed, so no combination of
     * values can produce the byte string of a different combination.
     */
    internal fun signingInput(
        context: String,
        keyId: ByteArray,
        issuedAt: Long,
        payload: ByteArray,
    ): ByteArray {
        val ctxBytes = context.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream(MAGIC.size + 16 + ctxBytes.size + payload.size)
        DataOutputStream(buf).use { out ->
            out.write(MAGIC)
            out.writeShort(VERSION)
            out.writeShort(ctxBytes.size)
            out.write(ctxBytes)
            out.write(keyId)
            out.writeLong(issuedAt)
            out.writeInt(payload.size)
            out.write(payload)
        }
        return buf.toByteArray()
    }
}

private typealias GeneralSecurityException = java.security.GeneralSecurityException
