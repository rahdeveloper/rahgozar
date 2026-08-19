package com.rahgozar.app.panel

/**
 * base64url without padding — the encoding every binary field in the panel
 * protocol uses (Go's `base64.RawURLEncoding`).
 *
 * Hand-rolled rather than `android.util.Base64` or `java.util.Base64`: the
 * former is a stub in JVM unit tests, which is exactly where the cross-language
 * vectors have to run, and the latter needs API 26 against a minSdk of 24.
 * Thirty lines removes both problems.
 *
 * Decoding is deliberately strict. A permissive decoder that skips unknown
 * characters would let a mangled signature turn into a different-but-valid byte
 * string, and "the signature did not verify" is a far better failure than
 * "something was decoded".
 */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val REVERSE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[n ushr 18 and 0x3F])
            out.append(ALPHABET[n ushr 12 and 0x3F])
            out.append(ALPHABET[n ushr 6 and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                out.append(ALPHABET[n ushr 18 and 0x3F])
                out.append(ALPHABET[n ushr 12 and 0x3F])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                out.append(ALPHABET[n ushr 18 and 0x3F])
                out.append(ALPHABET[n ushr 12 and 0x3F])
                out.append(ALPHABET[n ushr 6 and 0x3F])
            }
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException on padding, whitespace or any character outside the alphabet. */
    fun decode(text: String): ByteArray {
        // A 4n+1 group carries no whole byte, so it cannot be the tail of any
        // valid encoding — reject it rather than dropping the stray character.
        require(text.length % 4 != 1) { "base64url: truncated input" }

        val full = text.length / 4
        val tail = text.length % 4
        val out = ByteArray(full * 3 + if (tail == 0) 0 else tail - 1)

        var o = 0
        var i = 0
        while (i + 3 < text.length) {
            val n = (sextet(text, i) shl 18) or (sextet(text, i + 1) shl 12) or
                (sextet(text, i + 2) shl 6) or sextet(text, i + 3)
            out[o++] = (n ushr 16).toByte()
            out[o++] = (n ushr 8).toByte()
            out[o++] = n.toByte()
            i += 4
        }
        when (tail) {
            2 -> {
                val n = (sextet(text, i) shl 18) or (sextet(text, i + 1) shl 12)
                out[o] = (n ushr 16).toByte()
            }
            3 -> {
                val n = (sextet(text, i) shl 18) or (sextet(text, i + 1) shl 12) or (sextet(text, i + 2) shl 6)
                out[o++] = (n ushr 16).toByte()
                out[o] = (n ushr 8).toByte()
            }
        }
        return out
    }

    /** Returns null instead of throwing, for values that arrived over the wire. */
    fun decodeOrNull(text: String): ByteArray? = runCatching { decode(text) }.getOrNull()

    private fun sextet(text: String, index: Int): Int {
        val c = text[index]
        val v = if (c.code < 128) REVERSE[c.code] else -1
        require(v >= 0) { "base64url: invalid character at $index" }
        return v
    }
}
