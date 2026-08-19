package com.rahgozar.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import com.tencent.mmkv.MMKV
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key that encrypts everything worth stealing, held where a file copy
 * cannot reach it.
 *
 * The threat this exists for is narrow and real. Everything else in the design
 * — the signature allowlist, per-device content keys, signed responses — stops
 * an attacker who is on the *network* or who *repackaged the app*. None of it
 * stops the simplest attack there is: root the phone, `adb pull` the app's
 * data directory, and walk away with the device's 32-byte X25519 private key,
 * its panel token, and the decrypted server configs. Worse than the one-time
 * theft, the key and token together are a *subscription*: a short script can
 * then ask the panel for the current server list forever, from anywhere, and
 * the panel cannot tell it from the phone it was copied off.
 *
 * So the stores are encrypted, and their key is not in them. It lives in the
 * Android Keystore, which on any modern device means the TEE or a dedicated
 * secure element: the app can ask that key to decrypt, but neither the app nor
 * root can read the key material out. Copying the files now yields ciphertext
 * and a wrapped key that only that one phone's hardware can open. The attack
 * stops being "copy a directory" and becomes "run code on this specific
 * unlocked device" — which is a different tool, a different skill, and cannot
 * be done in bulk or after the fact from a stolen backup.
 *
 * What this deliberately does **not** claim: it is not a defence against an
 * attacker running code on the device right now. They can ask the same
 * Keystore key to decrypt, because the app must be able to. That attacker is
 * the panel's problem, not this file's — see the harvest detection that ends
 * the "one extraction, permanent access" trade.
 */
object SecureStore {

    /**
     * Where the wrapped key lives. Deliberately *not* encrypted: it holds
     * ciphertext, and a store that needed the key to find the key would not
     * work.
     */
    private const val ID_KEYRING = "KEYRING"

    private const val KEY_WRAPPED = "wrapped_store_key"
    private const val KEY_MIGRATED_PREFIX = "migrated_"

    /** Keystore alias. Stable for the life of the install; losing it costs a re-registration. */
    private const val KEYSTORE_ALIAS = "rahgozar.store.v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12

    /** MMKV takes at most 16 bytes of key material. */
    private const val CRYPT_KEY_BYTES = 16

    /**
     * The stores that hold something an attacker wants, and nothing that must
     * be readable before this class is up.
     *
     * `SETTING` and `ASSET` are excluded on purpose: they carry preferences and
     * cache paths, not credentials, and every store added here is one more
     * migration that can go wrong on a device we will never see.
     */
    val SENSITIVE_IDS = listOf(
        // The device's private key and its panel token — the subscription.
        "PANEL",
        // Full server configurations, decrypted: addresses, UUIDs, passwords.
        "PROFILE_FULL_CONFIG",
        // The same again in their original text form.
        "SERVER_RAW",
        // The server list and which one is selected or running.
        "MAIN",
    )

    @Volatile
    private var cryptKey: String? = null

    @Volatile
    private var unavailable = false

    /**
     * Prepares the key and migrates any plaintext stores, once per process.
     *
     * Must run before anything opens one of [SENSITIVE_IDS], which is why
     * [com.rahgozar.app.AngApplication] calls it immediately after
     * `MMKV.initialize` — in *every* process, because the tunnel processes read
     * the same stores and would otherwise open them plaintext and see nothing.
     */
    @Synchronized
    fun initialise() {
        if (cryptKey != null || unavailable) return

        val keyring = MMKV.mmkvWithID(ID_KEYRING, MMKV.MULTI_PROCESS_MODE)

        // Across processes, not just threads. Three of ours start at once, and
        // two of them generating a Keystore key under the same alias would
        // leave whichever lost the race holding a blob nothing can open.
        keyring.lock()
        try {
            val key = loadOrCreateKey(keyring)
            if (key == null) {
                // Nothing here is worth failing to start over: an app that
                // cannot open its own stores is an app that cannot connect.
                // Falling back to plaintext is a weaker position, not a broken
                // one, and it is loud in the log.
                LogUtil.e(AppConfig.TAG, "secure: no keystore-backed key; stores stay in the clear")
                unavailable = true
                return
            }
            cryptKey = key
            migrate(keyring, key)
        } finally {
            keyring.unlock()
        }
    }

    /**
     * Opens one of the app's stores, encrypted when this class has a key.
     *
     * Every caller goes through here rather than `MMKV.mmkvWithID` so that a
     * store cannot be added later that quietly stays in the clear.
     */
    fun open(id: String): MMKV {
        val key = cryptKey
        return if (key != null && id in SENSITIVE_IDS) {
            MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE, key)
        } else {
            MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE)
        }
    }

    // ------------------------------------------------------------------ key --

    private fun loadOrCreateKey(keyring: MMKV): String? {
        val secret = keystoreKey() ?: return null

        keyring.decodeBytes(KEY_WRAPPED)?.let { blob ->
            unwrap(secret, blob)?.let { return it }
            // The blob is there but will not open: the Keystore entry behind it
            // is gone or has changed. Nothing encrypted with it is readable
            // again, so the honest move is a fresh key and a clean slate —
            // the panel re-registers this device and hands the servers back.
            LogUtil.e(AppConfig.TAG, "secure: stored key will not unwrap — resetting the encrypted stores")
            resetStores(keyring)
        }

        val fresh = ByteArray(CRYPT_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val key = fresh.toHex()
        val wrapped = wrap(secret, key) ?: return null
        keyring.encode(KEY_WRAPPED, wrapped)
        return key
    }

    /**
     * The hardware-held key that wraps the store key.
     *
     * No user-authentication binding: the tunnel runs while the screen is off
     * and starts on boot, so a key that needed an unlocked device would fail
     * exactly when the app is doing its job.
     */
    private fun keystoreKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        // StrongBox is a separate security chip, and the strongest place this
        // key can live — but plenty of devices do not have one, and some claim
        // to and then refuse, so it is an attempt rather than a requirement.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val strongBox = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(true)
                .build()
            runCatching {
                generator.init(strongBox)
                return generator.generateKey()
            }
        }

        generator.init(spec)
        generator.generateKey()
    }.getOrElse {
        LogUtil.e(AppConfig.TAG, "secure: keystore unavailable", it as? Exception ?: Exception(it))
        null
    }

    private fun wrap(secret: SecretKey, key: String): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret) }
        // The nonce is generated by the provider and must travel with the
        // ciphertext; GCM with a repeated nonce is a real break, not a
        // theoretical one.
        cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
    }.getOrNull()

    private fun unwrap(secret: SecretKey, blob: ByteArray): String? = runCatching {
        if (blob.size <= GCM_NONCE_BYTES) return null
        val spec = GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secret, spec) }
        String(
            cipher.doFinal(blob, GCM_NONCE_BYTES, blob.size - GCM_NONCE_BYTES),
            Charsets.UTF_8,
        )
    }.getOrNull()

    // ------------------------------------------------------------ migration --

    /**
     * Turns each plaintext store into an encrypted one, in place.
     *
     * `reKey` is MMKV's own migration and keeps the contents, which matters:
     * the alternative is every installed device losing its registration on
     * upgrade, and re-registration is exactly the load the panel's own limiter
     * is there to survive.
     *
     * Recorded per store rather than once for all of them. A process killed
     * halfway through would otherwise resume believing it had finished, and
     * open a still-plaintext store with a key — which reads as empty, and for
     * `PANEL` means a device that silently forgets who it is.
     */
    private fun migrate(keyring: MMKV, key: String) {
        for (id in SENSITIVE_IDS) {
            val marker = KEY_MIGRATED_PREFIX + id
            if (keyring.decodeBool(marker, false)) continue
            val migrated = runCatching {
                MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE).reKey(key)
            }.getOrDefault(false)
            if (migrated) {
                keyring.encode(marker, true)
                LogUtil.i(AppConfig.TAG, "secure: $id is now encrypted at rest")
            } else {
                LogUtil.w(AppConfig.TAG, "secure: could not encrypt $id")
            }
        }
    }

    /**
     * Drops every encrypted store and the markers that say they were migrated.
     *
     * Only reached when the wrapping key is gone, which makes the contents
     * unreadable anyway. Clearing them means the next launch registers again
     * and the panel returns the servers; leaving them would strand the app on
     * bytes nothing can decrypt.
     */
    private fun resetStores(keyring: MMKV) {
        for (id in SENSITIVE_IDS) {
            runCatching { MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE).clearAll() }
            keyring.removeValueForKey(KEY_MIGRATED_PREFIX + id)
        }
        keyring.removeValueForKey(KEY_WRAPPED)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }.take(CRYPT_KEY_BYTES)
}
