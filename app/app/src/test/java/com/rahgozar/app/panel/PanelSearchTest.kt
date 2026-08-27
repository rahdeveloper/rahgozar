package com.rahgozar.app.panel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the app looks for its panel in, when the address it had stops
 * working.
 *
 * [PanelDiscoveryFetchTest] proves the mirrors are walked one by one past every
 * shape of block. [PanelSyncFallbackTest] proves when one address may be
 * replaced by the next. Neither of them proves the two are *joined up* — that
 * exhausting the known addresses is what sends the app to the mirrors, and that
 * what the mirrors hand back is then actually dialled.
 *
 * That join used to live inline inside `run()`, wrapped in a Context, a device
 * key and a network, where no unit test could reach it. Deleting the `if` would
 * have left every test in this suite green and the app permanently stuck on a
 * blocked domain. [PanelSync.search] exists so that cannot happen quietly.
 *
 * The effects are faked here on purpose: what is under test is the sequence,
 * not the fetching.
 */
class PanelSearchTest {

    private val ready = PanelSync.Result.Ready(
        changed = true, PanelSettings.parse(""), AdsConfig.parse(""),
    )

    private fun unavailable(reason: String = "panel unreachable", fatal: Boolean = false) =
        PanelSync.Result.Unavailable(reason, fatal = fatal)

    /** Records every address list handed to it, in order. */
    private class Dialled(private val answers: MutableList<PanelSync.Result>) {
        val calls = mutableListOf<List<String>>()
        var mirrorsAsked = 0

        suspend fun tryAddresses(urls: List<String>): PanelSync.Result {
            calls.add(urls)
            return if (answers.isEmpty()) PanelSync.Result.Unavailable("panel unreachable")
            else answers.removeAt(0)
        }
    }

    @Test
    fun `an address that works ends it and the mirrors are never asked`() {
        val dialled = Dialled(mutableListOf(ready))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://a.example", "https://b.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { dialled.mirrorsAsked++; emptyList() },
            )
        }

        assertSame(ready, result)
        assertEquals(1, dialled.calls.size)
        assertEquals("nothing failed, so nothing needed discovering", 0, dialled.mirrorsAsked)
    }

    @Test
    fun `every known address failing is what sends the app to the mirrors`() {
        // The whole point of the mechanism. The domain is filtered, none of the
        // addresses in hand answer, and the way out is a mirror.
        val dialled = Dialled(mutableListOf(unavailable(), ready))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://blocked.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { dialled.mirrorsAsked++; listOf("https://new.example") },
            )
        }

        assertSame(ready, result)
        assertEquals(1, dialled.mirrorsAsked)
        assertEquals(
            "the address the mirrors named must actually be dialled",
            listOf(listOf("https://blocked.example"), listOf("https://new.example")),
            dialled.calls,
        )
    }

    @Test
    fun `an answer that only looks conclusive still sends the app to the mirrors`() {
        // Deliberate, and the surprising half of the rule. A 401 or an error
        // code arrives over plain HTTP that no signature covers, so anything
        // holding a certificate for a hijacked address could produce one.
        // Believing it would let one intercepted address pin the app for good.
        val dialled = Dialled(mutableListOf(unavailable("this build is not registered", fatal = true), ready))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://intercepted.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { dialled.mirrorsAsked++; listOf("https://real.example") },
            )
        }

        assertSame(ready, result)
        assertEquals(1, dialled.mirrorsAsked)
    }

    @Test
    fun `a signed refusal is believed and the mirrors are left alone`() {
        // Blocked came out of a document signed with the panel's key. That is
        // the panel speaking, and asking a mirror where the panel lives would
        // not change what it said.
        val blocked = PanelSync.Result.Blocked(PanelGate.Decision.Allow, PanelSettings.parse(""))
        val dialled = Dialled(mutableListOf(blocked))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://a.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { dialled.mirrorsAsked++; listOf("https://new.example") },
            )
        }

        assertSame(blocked, result)
        assertEquals(0, dialled.mirrorsAsked)
    }

    @Test
    fun `an address that just failed is not dialled a second time`() {
        // Mirrors normally serve a list that includes addresses we already had
        // — the panel's own endpoint is usually the first entry. Re-dialling
        // one that failed seconds ago cannot succeed, and on a censored network
        // it means hammering a blocked host twice per sync.
        val dialled = Dialled(mutableListOf(unavailable()))

        runBlocking {
            PanelSync.search(
                known = listOf("https://blocked.example", "https://also-blocked.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { listOf("https://blocked.example", "https://also-blocked.example") },
            )
        }

        assertEquals("nothing new came back, so there was nothing to dial", 1, dialled.calls.size)
    }

    @Test
    fun `only the addresses that are new get dialled`() {
        val dialled = Dialled(mutableListOf(unavailable(), ready))

        runBlocking {
            PanelSync.search(
                known = listOf("https://old.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { listOf("https://old.example", "https://new.example") },
            )
        }

        assertEquals(listOf("https://new.example"), dialled.calls[1])
    }

    @Test
    fun `mirrors that hand back nothing leave the original failure standing`() {
        val failure = unavailable("panel unreachable")
        val dialled = Dialled(mutableListOf(failure))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://blocked.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { emptyList() },
            )
        }

        // The reason the user is shown is the one that actually happened, not
        // a later "no addresses" that would describe our own search instead.
        assertSame(failure, result)
    }

    @Test
    fun `discovery blowing up costs the way out and nothing more`() {
        // Discovery is a lookup, not the sync. A fault inside it must not take
        // the process with it — and Throwable rather than Exception is the
        // point: the last bug found on this path raised NoSuchMethodError, an
        // Error, which an `Exception` catch steps straight over.
        val dialled = Dialled(mutableListOf(unavailable("panel unreachable")))

        val result = runBlocking {
            PanelSync.search(
                known = listOf("https://blocked.example"),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { throw NoSuchMethodError("java.io.InputStream.readNBytes") },
            )
        }

        assertTrue(result is PanelSync.Result.Unavailable)
        assertEquals("the sync must end normally, not by propagating", 1, dialled.calls.size)
    }

    @Test
    fun `nothing known and a mirror with an address still finds the panel`() {
        // A fresh install whose baked-in address is already blocked ends up
        // here with nothing to try. The mirrors are the only route in.
        val dialled = Dialled(mutableListOf(unavailable("no panel address to try", fatal = true), ready))

        val result = runBlocking {
            PanelSync.search(
                known = emptyList(),
                tryAddresses = dialled::tryAddresses,
                askMirrors = { listOf("https://found.example") },
            )
        }

        assertSame(ready, result)
        assertEquals(listOf("https://found.example"), dialled.calls[1])
    }
}
