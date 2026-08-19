package com.rahgozar.app.panel

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.gson.annotations.SerializedName
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An AES-256-GCM ciphertext with its nonce, as it appears on the wire. */
data class SealedBox(
    @SerializedName("n") val nonce: String = "",
    @SerializedName("c") val ciphertext: String = "",
)

/** The device's X25519 pair. The private half never leaves the device. */
class DeviceKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    companion object {
        fun generate(): DeviceKeyPair {
            val priv = X25519.generatePrivateKey()
            return DeviceKeyPair(priv, X25519.publicFromPrivate(priv))
        }

        /** Rebuilds the pair from a stored private key. */
        fun fromPrivateKey(privateKey: ByteArray): DeviceKeyPair =
            DeviceKeyPair(privateKey, X25519.publicFromPrivate(privateKey))
    }
}

class PanelCryptoException(message: String) : Exception(message)

/**
 * The device side of the panel's content encryption.
 *
 * Server configs are encrypted once per config version under a random content
 * key, and only that 32-byte key is re-wrapped for each device — so opening a
 * bootstrap response is: derive the device key, unwrap the content key, open
 * the blobs. Reference implementation and the specification of every constant:
 * panel/internal/cryptobox/seal.go and docs/CRYPTO.md.
 */
object PanelCrypto {
    private const val HKDF_INFO = "brandvpn/v1/device-key"

    // Different AAD per use, so a wrapped key cannot be substituted for a
    // content blob or the other way round even under the same key.
    private const val AAD_CONTENT = "brandvpn/v1/content"
    private const val AAD_KEY_WRAP = "brandvpn/v1/key-wrap"

    private const val KEY_LEN = 32
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    /**
     * Derives the long-lived key this device shares with the panel.
     *
     * Both halves are static, so the result is stable for the life of the
     * registration and is worth caching. The order inside `info` is part of the
     * specification — server public key first, then the device's — and getting
     * it backwards produces a key that decrypts nothing, with no other symptom.
     */
    @Throws(PanelCryptoException::class)
    fun deviceKey(serverExchPublicKey: ByteArray, device: DeviceKeyPair): ByteArray {
        if (serverExchPublicKey.size != KEY_LEN) {
            throw PanelCryptoException("server exchange key is ${serverExchPublicKey.size} bytes")
        }
        return try {
            // Tink rejects low-order points here, so a tampered server key
            // cannot force a shared secret that is known in advance.
            val shared = X25519.computeSharedSecret(device.privateKey, serverExchPublicKey)
            val info = HKDF_INFO.toByteArray(Charsets.UTF_8) + serverExchPublicKey + device.publicKey
            Hkdf.computeHkdf("HMACSHA256", shared, null, info, KEY_LEN)
        } catch (e: GeneralSecurityException) {
            throw PanelCryptoException("key agreement failed: ${e.message}")
        }
    }

    /** Opens the content key that was wrapped for this device. */
    @Throws(PanelCryptoException::class)
    fun unwrapContentKey(deviceKey: ByteArray, box: SealedBox): ByteArray {
        val key = open(deviceKey, box, AAD_KEY_WRAP)
        if (key.size != KEY_LEN) {
            throw PanelCryptoException("wrapped key is ${key.size} bytes, expected $KEY_LEN")
        }
        return key
    }

    /** Opens a payload encrypted under the content key. */
    @Throws(PanelCryptoException::class)
    fun openContent(contentKey: ByteArray, box: SealedBox): ByteArray =
        open(contentKey, box, AAD_CONTENT)

    /**
     * Encrypts under a content key. Only the device→panel direction needs this
     * today; it exists so the format stays covered by tests on this side too.
     */
    @Throws(PanelCryptoException::class)
    fun sealContent(contentKey: ByteArray, plaintext: ByteArray): SealedBox =
        seal(contentKey, plaintext, AAD_CONTENT)

    private fun seal(key: ByteArray, plaintext: ByteArray, aad: String): SealedBox {
        requireKey(key)
        // The device key is long-lived, so a repeated nonce here is a real
        // failure rather than a theoretical one: every seal draws a fresh one.
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            SealedBox(Base64Url.encode(nonce), Base64Url.encode(cipher.doFinal(plaintext)))
        } catch (e: GeneralSecurityException) {
            throw PanelCryptoException("encryption failed: ${e.message}")
        }
    }

    private fun open(key: ByteArray, box: SealedBox, aad: String): ByteArray {
        requireKey(key)
        val nonce = Base64Url.decodeOrNull(box.nonce)
            ?: throw PanelCryptoException("malformed nonce")
        if (nonce.size != NONCE_LEN) throw PanelCryptoException("malformed nonce")
        val ct = Base64Url.decodeOrNull(box.ciphertext)
            ?: throw PanelCryptoException("malformed ciphertext")

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            // Deliberately opaque: a wrong key, a wrong AAD and a tampered tag
            // are one outcome here, and the app does the same thing in all three.
            throw PanelCryptoException("authentication failed")
        }
    }

    private fun requireKey(key: ByteArray) {
        if (key.size != KEY_LEN) throw PanelCryptoException("key is ${key.size} bytes, expected $KEY_LEN")
    }
}
