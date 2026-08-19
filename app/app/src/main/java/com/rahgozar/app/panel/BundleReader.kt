package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Turns the bytes a mirror served into a bundle the app is willing to act on —
 * or into nothing at all.
 *
 * Deliberately free of I/O so the rules below are unit-testable against a real
 * panel-signed document (see PanelDiscoveryTest). Everything here is a rule
 * about what to trust; nothing here decides where to fetch from.
 *
 * The only measure of success is that the Ed25519 signature verified. A 200,
 * well-formed JSON, even a document that looks exactly right — all of them are
 * failures without that. It is what makes a censor's block page, an ISP's
 * cached response and a poisoned DNS answer identical to the app: rejected,
 * try the next source. No cleverness about detecting block pages, which is
 * brittle and obsolete within months.
 */
object BundleReader {
    private val gson = Gson()

    sealed interface Result {
        data class Valid(val bundle: EndpointBundle) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param nowSeconds unix time, passed in so validity is testable and so the
     *   caller can decide what "now" means on a device with a broken clock.
     */
    fun read(signPublicKey: ByteArray, document: String, nowSeconds: Long): Result {
        val envelope = try {
            gson.fromJson(document, Envelope::class.java)
        } catch (e: JsonSyntaxException) {
            // The overwhelmingly common case on a censored network: a 200 with
            // an HTML block page in it.
            return Result.Rejected("not a signed document")
        } ?: return Result.Rejected("empty document")

        val payload = try {
            PanelEnvelope.open(signPublicKey, PanelEnvelope.CTX_ENDPOINTS, envelope)
        } catch (e: EnvelopeException) {
            return Result.Rejected(e.message ?: "signature rejected")
        }

        val bundle = try {
            gson.fromJson(String(payload, Charsets.UTF_8), EndpointBundle::class.java)
        } catch (e: JsonSyntaxException) {
            return Result.Rejected("payload is not a bundle")
        } ?: return Result.Rejected("payload is not a bundle")

        // A copy nobody refreshes has to go stale eventually — that is the whole
        // point of the expiry, and it is why a retired address cannot linger on
        // an abandoned mirror forever.
        if (nowSeconds < bundle.notBefore) return Result.Rejected("not valid yet")
        if (nowSeconds > bundle.notAfter) return Result.Rejected("expired")

        if (bundle.endpoints.none { it.url.startsWith("https://") }) {
            return Result.Rejected("no usable endpoint")
        }
        return Result.Valid(bundle)
    }

    /**
     * Picks the bundle to act on out of everything that verified.
     *
     * Highest version wins, and the app never moves down. Rolling back is
     * therefore impossible by republishing an older version — a device that has
     * seen v8 ignores v7 forever — which is exactly why the panel makes you
     * build a new version carrying the old contents instead.
     */
    fun best(bundles: List<EndpointBundle>, knownVersion: Long): EndpointBundle? =
        bundles.filter { it.version > knownVersion }.maxByOrNull { it.version }

    /**
     * The source list to try next time: what the bundle carries, with the baked
     * list kept behind it.
     *
     * A bundle can retire a mirror, but it can never leave an install with
     * nowhere to look — which would be an unrecoverable state on a phone whose
     * only other option is a domain that is already blocked.
     */
    fun mergeSources(fromBundle: List<DiscoverySource>, baked: List<DiscoverySource>): List<DiscoverySource> {
        val seen = HashSet<String>()
        val out = ArrayList<DiscoverySource>(fromBundle.size + baked.size)
        for (source in fromBundle + baked) {
            if (source.key.isNotEmpty() && seen.add(source.key)) out.add(source)
        }
        return out
    }

    /**
     * Reassembles a DNS TXT value.
     *
     * DNS transmits TXT values as 255-character strings that resolvers hand
     * back separately; Google's and Cloudflare's DoH JSON also wrap each in
     * quotes. Both are undone here rather than at each call site.
     */
    fun joinTxtParts(parts: List<String>): String =
        parts.joinToString("") { it.trim().removeSurrounding("\"") }
}
