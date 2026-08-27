package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The part of discovery that talks to the network: walking a list of mirrors
 * until one of them serves a bundle that verifies.
 *
 * [PanelDiscoveryTest] covers what may be trusted; this covers what happens
 * when the first places asked are down, blocked, or answering with something
 * that is not ours — which is the entire point of the mechanism and the only
 * state a censored user is ever in.
 *
 * Real HTTP against a local server, and the real panel-signed bundle from the
 * generated vectors: a mirror that a hand-written fake would have satisfied
 * proves nothing about a mirror behind a block page.
 */
class PanelDiscoveryFetchTest {

    private data class Server(@SerializedName("sign_public_key") val signPublicKey: String)
    private data class BundleCase(@SerializedName("document") val document: String)
    private data class Vectors(
        @SerializedName("server") val server: Server,
        @SerializedName("endpoint_bundle") val endpointBundle: BundleCase,
    )

    private val vectors: Vectors by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("client-vectors.json")
        assertNotNull("client-vectors.json is not on the test classpath", stream)
        stream!!.bufferedReader().use { Gson().fromJson(it, Vectors::class.java) }
    }

    private val signKey get() = Base64Url.decode(vectors.server.signPublicKey)
    private val bundle get() = vectors.endpointBundle.document

    /** The real document with one character of it changed. */
    private val tampered: String
        get() {
            val at = bundle.length / 2
            val ch = if (bundle[at] == 'a') 'b' else 'a'
            return bundle.substring(0, at) + ch + bundle.substring(at + 1)
        }

    /** Inside the vector bundle's validity window. */
    private val now = 1_780_000_100L

    /** Stands in for what the device would remember between launches. */
    private class FakeMemory(override val panelUrl: String = "") : PanelDiscovery.Memory {
        override var bundleVersion = 0L
        override var endpointsJson = ""
        override var sourcesJson = ""
    }

    private lateinit var memory: FakeMemory
    private lateinit var dead: MockWebServer
    private lateinit var blocked: MockWebServer
    private lateinit var mirror: MockWebServer

    @Before
    fun start() {
        memory = FakeMemory()
        dead = MockWebServer().apply { start() }
        blocked = MockWebServer().apply { start() }
        mirror = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        dead.close()
        blocked.close()
        mirror.close()
    }

    private fun source(server: MockWebServer, priority: Int) = DiscoverySource(
        type = DiscoverySource.TYPE_HTTP,
        url = server.url("/e.json").toString(),
        priority = priority,
    )

    private fun document(sources: List<DiscoverySource>) = DiscoveryDocument(
        signPublicKey = vectors.server.signPublicKey,
        directEndpoints = listOf(PanelEndpoint(url = "https://panel.example.com", weight = 100)),
        sources = sources,
        timeoutMs = 2_000,
    )

    @Test
    fun `walks past a dead mirror and a block page to one that serves the bundle`() {
        dead.enqueue(MockResponse.Builder().code(502).build())
        blocked.enqueue(
            MockResponse.Builder().code(200).body("<html><head><title>Blocked</title></head></html>").build()
        )
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val discovery = document(
            listOf(source(dead, 10), source(blocked, 20), source(mirror, 30))
        )

        val endpoints = PanelDiscovery.refresh(discovery, signKey, now, memory)

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        // Every mirror ahead of the good one was actually tried.
        assertEquals(1, dead.requestCount)
        assertEquals(1, blocked.requestCount)
        assertEquals(1, mirror.requestCount)
    }

    @Test
    fun `returns nothing when no mirror serves something signed`() {
        dead.enqueue(MockResponse.Builder().code(403).body("nope").build())
        blocked.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val discovery = document(listOf(source(dead, 10), source(blocked, 20)))

        assertTrue(PanelDiscovery.refresh(discovery, signKey, now, memory).isEmpty())
    }

    @Test
    fun `a body served with an error status still counts if it is signed`() {
        // Some CDNs answer 403 with the object anyway. The signature decides,
        // not the status line.
        mirror.enqueue(MockResponse.Builder().code(403).body(bundle).build())

        val endpoints = PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory)

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
    }

    @Test
    fun `an expired bundle is refused like any other unusable answer`() {
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        // Far past not_after.
        val endpoints = PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, 2_000_000_000L, memory)

        assertTrue(endpoints.isEmpty())
    }

    @Test
    fun `reads a bundle that arrives in many small chunks`() {
        // The reason this test exists.
        //
        // The body used to be read with `InputStream.readNBytes`, which is API
        // 33 — below that the method does not exist and the call raised
        // NoSuchMethodError, an *Error*, which the catch around it could not
        // catch. Discovery died instead of moving to the next mirror, on every
        // device before Android 13, on the one path that runs when the panel's
        // own domain is blocked.
        //
        // The replacement is a hand-written loop, and the classic way to get
        // one of those wrong is to treat the first short read as the end. A
        // chunked response makes that failure certain rather than occasional:
        // eight bytes at a time, so the loop has to come back for more a few
        // hundred times to see the whole document.
        mirror.enqueue(MockResponse.Builder().code(200).chunkedBody(bundle, 8).build())

        val endpoints = PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory)

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
    }

    @Test
    fun `a mirror answering with far too much is cut off, not followed`() {
        // A mirror is a stranger, and the signature is not checked until the
        // body is already in memory. So the read is capped, and a mirror that
        // answers with a megabyte gets its answer truncated — which then fails
        // to verify, which is the correct outcome for a mirror behaving like
        // that. What must not happen is the app buffering whatever it is sent.
        val flood = "x".repeat(1_024 * 1_024)
        mirror.enqueue(MockResponse.Builder().code(200).body(flood).build())

        assertTrue(PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory).isEmpty())
    }

    @Test
    fun `asks mirrors in priority order`() {
        blocked.enqueue(MockResponse.Builder().code(200).body("nope").build())
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        // Declared out of order: the low number must still go first.
        PanelDiscovery.refresh(document(listOf(source(mirror, 90), source(blocked, 10))), signKey, now, memory)

        assertEquals(1, blocked.requestCount)
        assertEquals(1, mirror.requestCount)
    }

    @Test
    fun `reads a bundle out of a DNS TXT record`() {
        // A DoH resolver's JSON, with the document split the way DNS forces.
        val parts = bundle.chunked(255).joinToString(",") { "{\"type\":16,\"data\":\"${it.replace("\"", "\\\"").replace("\n", "\\n")}\"}" }
        mirror.enqueue(MockResponse.Builder().code(200).body("""{"Status":0,"Answer":[$parts]}""").build())

        val discovery = DiscoveryDocument(
            signPublicKey = vectors.server.signPublicKey,
            sources = listOf(
                DiscoverySource(
                    type = DiscoverySource.TYPE_DOH_TXT,
                    domain = "seed.example.com",
                    name = "_cfg",
                    priority = 10,
                )
            ),
            dohResolvers = listOf(mirror.url("/resolve").toString()),
            timeoutMs = 2_000,
        )

        val endpoints = PanelDiscovery.refresh(discovery, signKey, now, memory)

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        val asked = mirror.takeRequest().url.toString()
        assertTrue("the record name must be asked for: $asked", asked.contains("_cfg.seed.example.com"))
        assertTrue("TXT must be asked for: $asked", asked.contains("type=TXT"))
    }

    // ------------------------------------------------------------------
    // What a blocked mirror actually looks like.
    //
    // The tests above use a 502 and a block page, which are the polite shapes.
    // Filtering where this app is used is rarely polite: connections are reset
    // on the SNI before a byte of response exists, cut part-way through a body
    // that had already started arriving, or dropped into a hole where nothing
    // ever comes back.
    //
    // Each fails in a different layer of OkHttp, and each has to mean the same
    // thing here — ask the next mirror. A walk that survives a 502 and dies on
    // a reset is one that passes testing and fails where it matters.
    // ------------------------------------------------------------------

    @Test
    fun `a mirror whose connection is reset before it answers does not end the walk`() {
        // Killed the instant the request lands: no status line, no body. What
        // an SNI filter does.
        blocked.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val endpoints = PanelDiscovery.refresh(
            document(listOf(source(blocked, 10), source(mirror, 20))), signKey, now, memory,
        )

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        assertEquals("the mirror behind the reset one must still be asked", 1, mirror.requestCount)
    }

    @Test
    fun `a mirror cut off mid-body does not end the walk`() {
        // The nastiest shape, because it half-works: a real 200, real bytes,
        // then the connection dies. What reaches the reader is a genuine prefix
        // of a genuine bundle, so the signature is the only thing that can tell
        // it apart from the whole document — and a partial answer must not be
        // mistaken for an answer.
        blocked.enqueue(
            MockResponse.Builder().code(200)
                .chunkedBody(bundle, 64)
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        )
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val endpoints = PanelDiscovery.refresh(
            document(listOf(source(blocked, 10), source(mirror, 20))), signKey, now, memory,
        )

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        assertEquals(1, mirror.requestCount)
    }

    @Test
    fun `a mirror that never answers is given up on and the next one is asked`() {
        // A blackhole: the connection is accepted and then nothing happens.
        // Without a timeout the walk would stop here for good, which on a
        // censored network is the difference between a slow app and a dead one.
        blocked.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val started = System.nanoTime()
        val endpoints = PanelDiscovery.refresh(
            document(listOf(source(blocked, 10), source(mirror, 20))), signKey, now, memory,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        // The document asks for 2s and the call timeout is twice that. Beyond
        // it, the walk is not bounded by the timeout it declares.
        assertTrue("the stalled mirror held the walk for ${elapsedMs}ms", elapsedMs < 6_000)
    }

    @Test
    fun `walks through every shape of block to the one mirror that works`() {
        // All of them at once, with the only good mirror last. This is the
        // whole mechanism in one test: no single failure shape may end the
        // search.
        val reset = MockWebServer().apply { start() }
        val cut = MockWebServer().apply { start() }
        val hostile = MockWebServer().apply { start() }
        try {
            dead.enqueue(MockResponse.Builder().code(502).build())
            blocked.enqueue(MockResponse.Builder().code(200).body("<html>Blocked</html>").build())
            reset.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            cut.enqueue(
                MockResponse.Builder().code(200).chunkedBody(bundle, 64)
                    .onResponseBody(SocketEffect.CloseSocket()).build()
            )
            // Up, and serving a well-formed document that simply is not ours.
            // Being reachable is not being trusted.
            hostile.enqueue(MockResponse.Builder().code(200).body(tampered).build())
            mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

            val endpoints = PanelDiscovery.refresh(
                document(
                    listOf(
                        source(dead, 10), source(blocked, 20), source(reset, 30),
                        source(cut, 40), source(hostile, 50), source(mirror, 60),
                    )
                ),
                signKey, now, memory,
            )

            assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
            for ((name, server) in listOf(
                "dead" to dead, "blocked" to blocked, "reset" to reset,
                "cut" to cut, "hostile" to hostile, "good" to mirror,
            )) {
                assertEquals("$name was never asked", 1, server.requestCount)
            }
        } finally {
            reset.close()
            cut.close()
            hostile.close()
        }
    }

    @Test
    fun `the address discovery found leads the next launch`() {
        // The mechanism seen from the app's side rather than the mirror's: the
        // address in hand is gone, a mirror hands over a newer bundle, and what
        // the next launch reaches for is the new address — not the dead one.
        memory.bundleVersion = 8
        memory.endpointsJson = """["https://blocked-and-gone.example"]"""
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory)

        val next = PanelDiscovery.candidates(document(emptyList()), memory)
        assertTrue("the discovered address must be there to try", next.contains("https://a2.example.net"))
        assertFalse("the dead address must not be carried forward", next.contains("https://blocked-and-gone.example"))
    }

    @Test
    fun `a stale mirror does not hide a newer bundle behind it`() {
        // The state that matters: the address in hand is gone, the first
        // mirror is a CDN still serving the copy the app already has, and the
        // only way out is the mirror behind it. Stopping at the stale one
        // would leave the app with nowhere to go.
        memory.bundleVersion = 9   // the vector bundle's version
        blocked.enqueue(MockResponse.Builder().code(200).body(bundle).build())
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        PanelDiscovery.refresh(
            document(listOf(source(blocked, 10), source(mirror, 20))), signKey, now, memory,
        )

        assertEquals("the mirror behind the stale one must still be asked", 1, mirror.requestCount)
    }

    @Test
    fun `a bundle no newer than the one in hand changes nothing`() {
        memory.bundleVersion = 9
        memory.endpointsJson = """["https://kept.example"]"""
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val endpoints = PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory)

        assertEquals(listOf("https://kept.example"), endpoints)
        assertEquals("the stored version must not move", 9L, memory.bundleVersion)
    }

    @Test
    fun `a newer bundle replaces the addresses in hand`() {
        memory.bundleVersion = 8
        memory.endpointsJson = """["https://old.example"]"""
        mirror.enqueue(MockResponse.Builder().code(200).body(bundle).build())

        val endpoints = PanelDiscovery.refresh(document(listOf(source(mirror, 10))), signKey, now, memory)

        assertEquals(listOf("https://panel.example.com", "https://a2.example.net"), endpoints)
        assertEquals(9L, memory.bundleVersion)
    }
}
