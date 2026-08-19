package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The panel client against a real HTTP server.
 *
 * The point is the failure cases. A network that is being censored does not
 * return errors — it returns 200s full of something else, and the only thing
 * standing between that and the app acting on it is the signature check. Each
 * test below is a shape that has to be rejected.
 */
class PanelClientTest {

    private data class Server(
        @SerializedName("sign_public_key") val signPublicKey: String,
        @SerializedName("exch_public_key") val exchPublicKey: String,
    )

    private data class EnvelopeCase(
        @SerializedName("envelope") val envelope: Envelope,
    )

    private data class BundleCase(@SerializedName("document") val document: String)

    private data class Vectors(
        @SerializedName("server") val server: Server,
        @SerializedName("envelope") val envelope: EnvelopeCase,
        @SerializedName("endpoint_bundle") val endpointBundle: BundleCase,
    )

    private val gson = Gson()

    private val vectors: Vectors by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("client-vectors.json")
        assertNotNull("client-vectors.json is not on the test classpath", stream)
        stream!!.bufferedReader().use { gson.fromJson(it, Vectors::class.java) }
    }

    private lateinit var server: MockWebServer
    private lateinit var client: PanelClient

    /** https, so the client's transport rule is exercised and not bypassed. */
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()

        client = PanelClient(
            signPublicKey = Base64Url.decode(vectors.server.signPublicKey),
            exchPublicKey = Base64Url.decode(vectors.server.exchPublicKey),
            client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                )
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------- refusals --

    @Test
    fun `refuses a panel address that is not https`() {
        val plain = base.replaceFirst("https://", "http://")
        val failure = runCatching {
            client.bootstrap(plain, "bv1.test", DeviceKeyPair.generate())
        }.exceptionOrNull()
        assertTrue(failure is PanelException)
        assertTrue(failure!!.message!!.contains("https"))
        // Refused before any socket was opened, not after a failed handshake.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects a block page served with 200`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("<html><body>This site is blocked</body></html>")
                .build()
        )
        assertRejected()
    }

    @Test
    fun `rejects a bundle signature replayed as a bootstrap response`() {
        // A genuine, valid signature — for the wrong context. Without the
        // context check this would parse as an empty-but-trusted bootstrap.
        server.enqueue(MockResponse.Builder().code(200).body(vectors.endpointBundle.document).build())
        assertRejected()
    }

    @Test
    fun `rejects a tampered payload`() {
        val envelope = vectors.envelope.envelope
        val raw = Base64Url.decode(envelope.payload)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val tampered = envelope.copy(payload = Base64Url.encode(raw))

        server.enqueue(MockResponse.Builder().code(200).body(gson.toJson(tampered)).build())
        assertRejected()
    }

    @Test
    fun `rejects an empty body`() {
        server.enqueue(MockResponse.Builder().code(200).body("").build())
        assertRejected()
    }

    private fun assertRejected() {
        val failure = runCatching {
            client.bootstrap(
                baseUrl = base,
                token = "bv1.test",
                device = DeviceKeyPair.generate(),
            )
        }.exceptionOrNull()
        assertTrue("expected a PanelException, got $failure", failure is PanelException)
    }

    // -------------------------------------------------------------- errors --

    @Test
    fun `surfaces the panel's own error codes`() {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"error":{"code":"device_blocked","message":"blocked"}}""")
                .build()
        )
        val failure = runCatching {
            client.bootstrap(
                base, "bv1.test", DeviceKeyPair.generate()
            )
        }.exceptionOrNull() as PanelException

        assertTrue(failure.isBlocked)
        assertEquals(403, failure.httpStatus)
    }

    @Test
    fun `a 429 carries the panel's named wait`() {
        // The launch-day failure shape: a carrier-NAT address that has spent
        // its shared registration budget. The panel names the wait; losing
        // that number turns a client that comes back at the right moment into
        // one that guesses.
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "3")
                .body("""{"error":{"code":"rate_limited","message":"too many registration attempts"}}""")
                .build()
        )
        val failure = runCatching {
            client.bootstrap(
                base, "bv1.test", DeviceKeyPair.generate()
            )
        }.exceptionOrNull() as PanelException

        assertTrue(failure.isRateLimited)
        assertEquals(3L, failure.retryAfterSeconds)
    }

    @Test
    fun `a 429 without a named wait stays a 429 with nothing to wait for`() {
        // An older panel, or something else on the path speaking 429. Still
        // rate-limited — the address walk must not amplify it — but there is
        // no number to retry against, and inventing one is how storms start.
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .body("""{"error":{"code":"rate_limited","message":"slow down"}}""")
                .build()
        )
        val failure = runCatching {
            client.bootstrap(
                base, "bv1.test", DeviceKeyPair.generate()
            )
        }.exceptionOrNull() as PanelException

        assertTrue(failure.isRateLimited)
        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `a 401 asks for re-registration rather than reporting a network fault`() {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"code":"unauthorized","message":"register again"}}""")
                .build()
        )
        val failure = runCatching {
            client.bootstrap(
                base, "bv1.test", DeviceKeyPair.generate()
            )
        }.exceptionOrNull() as PanelException

        assertTrue(failure.needsRegistration)
    }

    @Test
    fun `304 means what we already have is current`() {
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = client.bootstrap(
            base,
            "bv1.test",
            DeviceKeyPair.generate(),
            etag = """W/"7.IR"""",
        )
        assertTrue(result.notModified)
        assertNull(result.bootstrap)
        assertEquals("""W/"7.IR"""", result.etag)

        val recorded = server.takeRequest()
        assertEquals("""W/"7.IR"""", recorded.headers["If-None-Match"])
        assertEquals("Bearer bv1.test", recorded.headers["Authorization"])
    }

    @Test
    fun `does not follow a redirect away from the discovered address`() {
        // Discovery decides where the panel is. A 302 from something in the
        // path must not be able to move the conversation somewhere else.
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .setHeader("Location", "https://elsewhere.example/v1/bootstrap")
                .build()
        )
        val failure = runCatching {
            client.bootstrap(
                base, "bv1.test", DeviceKeyPair.generate()
            )
        }.exceptionOrNull() as PanelException

        assertEquals(302, failure.httpStatus)
        assertEquals(1, server.requestCount)
    }
}
