package com.rahgozar.app.panel

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * The SHA-256 of the certificate this build is signed with — what
 * /v1/device/register sends as `cert_sha256` and the panel checks against its
 * allowlist.
 *
 * This is not a security boundary on its own. Anyone who repacks the app signs
 * it with their own key and can read this code; what the check buys is that a
 * repack cannot talk to the panel with the real app's identity, so it cannot
 * hand out the server list. The real protection is that configs are encrypted
 * to a key derived per registration.
 */
object AppSignature {

    /**
     * Every certificate the installed APK is signed with, as base64url SHA-256.
     *
     * A list rather than one value because signing certificates can be rotated:
     * during a rotation an install may present the old certificate, the new
     * one, or both, and the panel's allowlist is a table precisely so both can
     * be registered while the old builds are still in the field.
     *
     * Empty when the package cannot be read, which should not happen for our
     * own package — treated as "cannot attest" rather than as a reason to send
     * something made up.
     */
    fun certificateHashes(context: Context): List<String> =
        signatures(context).map { hash(it.toByteArray()) }.distinct()

    /** The certificate to register with, or null when it cannot be read. */
    fun primaryCertificateHash(context: Context): String? = certificateHashes(context).firstOrNull()

    @Suppress("DEPRECATION")
    private fun signatures(context: Context): List<Signature> {
        val pm = context.packageManager
        val name = context.packageName
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                // apkContentsSigners, not signingCertificateHistory: what matters
                // is who signed the bytes running right now, not the lineage.
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                pm.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        }
    }

    private fun hash(der: ByteArray): String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(der))

    /**
     * Converts a `keytool`/`apksigner` fingerprint ("A1:B2:…") to the base64url
     * form, so a value read off a terminal can be compared with what the app
     * would send. The panel accepts either; this exists for the app side and
     * for tests.
     */
    fun fromColonHex(fingerprint: String): String? {
        val hex = fingerprint.replace(":", "").replace(" ", "").trim()
        if (hex.length != 64) return null
        val out = ByteArray(32)
        for (i in out.indices) {
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = byte.toByte()
        }
        return Base64Url.encode(out)
    }
}
