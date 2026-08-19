package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery rules, checked against a bundle the panel actually signed
 * (`endpoint_bundle` in the generated vectors) rather than a fixture written
 * here — a hand-made document would only prove this file agrees with itself.
 */
class PanelDiscoveryTest {

    private data class Server(@SerializedName("sign_public_key") val signPublicKey: String)

    private data class BundleCase(
        @SerializedName("ctx") val ctx: String,
        @SerializedName("document") val document: String,
        @SerializedName("version") val version: Long,
        @SerializedName("first_endpoint_url") val firstEndpointUrl: String,
        @SerializedName("source_count") val sourceCount: Int,
    )

    private data class Vectors(
        @SerializedName("server") val server: Server,
        @SerializedName("endpoint_bundle") val endpointBundle: BundleCase,
        @SerializedName("envelope") val envelope: EnvelopeCase,
    )

    private data class EnvelopeCase(@SerializedName("envelope") val envelope: Envelope)

    private val vectors: Vectors by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("client-vectors.json")
        assertNotNull("client-vectors.json is not on the test classpath", stream)
        stream!!.bufferedReader().use { Gson().fromJson(it, Vectors::class.java) }
    }

    private val signKey get() = Base64Url.decode(vectors.server.signPublicKey)

    /** Inside the bundle's validity window. */
    private val now = 1_780_000_100L

    @Test
    fun `accepts a bundle the panel signed`() {
        val result = BundleReader.read(signKey, vectors.endpointBundle.document, now)
        assertTrue("rejected: $result", result is BundleReader.Result.Valid)

        val bundle = (result as BundleReader.Result.Valid).bundle
        assertEquals(vectors.endpointBundle.version, bundle.version)
        assertEquals(vectors.endpointBundle.firstEndpointUrl, bundle.endpoints.first().url)
        // The mirror list travels inside the signed payload — that is what makes
        // discovery self-repairing, so its absence is a real regression.
        assertEquals(vectors.endpointBundle.sourceCount, bundle.sources.size)
        assertTrue(bundle.sources.any { it.isDohTxt })
    }

    @Test
    fun `rejects a tampered document`() {
        // Flip one character inside the base64 payload. The JSON stays
        // well-formed, which is the point: only the signature catches this.
        val doc = vectors.endpointBundle.document
        val marker = "\"payload\": \""
        val at = doc.indexOf(marker) + marker.length
        val swapped = if (doc[at] == 'e') 'f' else 'e'
        val tampered = doc.substring(0, at) + swapped + doc.substring(at + 1)

        val result = BundleReader.read(signKey, tampered, now)
        assertTrue("tampered document was accepted", result is BundleReader.Result.Rejected)
    }

    @Test
    fun `rejects what a censored network actually returns`() {
        val notOurs = listOf(
            "<html><head><title>Blocked</title></head><body>…</body></html>",
            "",
            "{}",
            """{"v":1,"alg":"Ed25519","kid":"AAAAAAAAAAA","ctx":"brandvpn/v1/endpoints","ts":1,"payload":"e30","sig":"AA"}""",
        )
        for (body in notOurs) {
            val result = BundleReader.read(signKey, body, now)
            assertTrue("accepted: $body", result is BundleReader.Result.Rejected)
        }
    }

    @Test
    fun `rejects a bundle signed for another context`() {
        // A real, valid signature — over a bootstrap response. Replaying it as
        // a bundle must fail, or a mirror could serve one and redirect nothing.
        val bootstrapDoc = Gson().toJson(vectors.envelope.envelope)
        val result = BundleReader.read(signKey, bootstrapDoc, now)
        assertTrue("bootstrap envelope accepted as a bundle", result is BundleReader.Result.Rejected)
    }

    @Test
    fun `honours the validity window`() {
        val valid = BundleReader.read(signKey, vectors.endpointBundle.document, now)
        val bundle = (valid as BundleReader.Result.Valid).bundle

        assertTrue(BundleReader.read(signKey, vectors.endpointBundle.document, bundle.notBefore - 1)
            is BundleReader.Result.Rejected)
        assertTrue(BundleReader.read(signKey, vectors.endpointBundle.document, bundle.notAfter + 1)
            is BundleReader.Result.Rejected)
    }

    // -------------------------------------------------------------- version --

    @Test
    fun `never moves to a lower version`() {
        val v7 = EndpointBundle(version = 7)
        val v9 = EndpointBundle(version = 9)

        assertEquals(9L, BundleReader.best(listOf(v7, v9), knownVersion = 0)?.version)
        assertEquals(9L, BundleReader.best(listOf(v9, v7), knownVersion = 8)?.version)
        // Already on 9: an older copy still being served by a stale mirror
        // must not pull the app back.
        assertEquals(null, BundleReader.best(listOf(v7), knownVersion = 9))
        assertEquals(null, BundleReader.best(listOf(v9), knownVersion = 9))
    }

    // -------------------------------------------------------------- sources --

    @Test
    fun `bundle sources win but the baked list survives behind them`() {
        val fromBundle = listOf(
            DiscoverySource(type = "http", url = "https://new.example/e.json", priority = 10),
        )
        val baked = listOf(
            DiscoverySource(type = "http", url = "https://new.example/e.json", priority = 99),
            DiscoverySource(type = "http", url = "https://baked.example/e.json", priority = 20),
            DiscoverySource(type = "doh_txt", domain = "seed.example.com", name = "_cfg"),
        )

        val merged = BundleReader.mergeSources(fromBundle, baked)

        assertEquals(3, merged.size)
        assertEquals("https://new.example/e.json", merged[0].url)
        assertEquals(10, merged[0].priority) // the bundle's copy, not the baked one
        // A bundle may retire a mirror, but an install is never left with
        // nowhere to look.
        assertTrue(merged.any { it.url == "https://baked.example/e.json" })
        assertTrue(merged.any { it.isDohTxt })
    }

    @Test
    fun `reassembles a TXT record from its 255-character pieces`() {
        val document = vectors.endpointBundle.document
        val parts = document.chunked(255).map { "\"$it\"" } // as a DoH JSON answer arrives
        assertEquals(document, BundleReader.joinTxtParts(parts))

        val result = BundleReader.read(signKey, BundleReader.joinTxtParts(parts), now)
        assertTrue("a TXT-delivered bundle must verify like any other", result is BundleReader.Result.Valid)
    }

    @Test
    fun `txt source names default the record label`() {
        assertEquals("_cfg.seed.example.com",
            DiscoverySource(type = "doh_txt", domain = "seed.example.com").txtName)
        assertEquals("x.seed.example.com",
            DiscoverySource(type = "doh_txt", domain = "seed.example.com", name = "x").txtName)
    }
}
