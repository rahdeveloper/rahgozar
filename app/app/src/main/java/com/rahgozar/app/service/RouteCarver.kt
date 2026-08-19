package com.rahgozar.app.service

import java.math.BigInteger
import java.net.InetAddress

/**
 * Builds a tun's route table with holes in it.
 *
 * Android 13 gave VpnService.Builder.excludeRoute, which is "route everything
 * except this address" in one call. Below 13 no such call exists — but the
 * same shape can be *constructed*: instead of one route covering everything,
 * the covering is handed over as the set of CIDR blocks that together cover
 * everything except the excluded addresses. The kernel treats the two
 * identically; one is just more lines.
 *
 * This is what lets a smart session — which needs this app inside the tun and
 * the core's own uplink outside it — run on the half of the fleet that is
 * still on Android 10–12, instead of showing those users no ads at all.
 *
 * The arithmetic is done in [BigInteger] so IPv4 and IPv6 share one
 * implementation; a /32 hole in `0.0.0.0/0` comes out as 32 blocks, a /128
 * hole in `::/0` as 128, and holes that miss the base ranges change nothing.
 */
object RouteCarver {

    /** One route as the Builder wants it: an address string and its prefix. */
    data class Route(val address: String, val prefix: Int)

    /**
     * The [base] CIDRs with every address in [exclude] cut out.
     *
     * Families are handled independently: an IPv4 hole is only cut from IPv4
     * bases, an IPv6 hole from IPv6 ones. Malformed entries on either side
     * are skipped rather than fatal — a bad exclusion must degrade to "that
     * hole was not cut", never to a tun with no routes.
     */
    fun carve(base: List<String>, exclude: List<String>): List<Route> {
        val holes4 = exclude.mapNotNull { addressOf(it, v6 = false) }
        val holes6 = exclude.mapNotNull { addressOf(it, v6 = true) }

        val out = mutableListOf<Route>()
        for (cidr in base) {
            val parsed = parseCidr(cidr) ?: continue
            val holes = if (parsed.v6) holes6 else holes4
            var ranges = listOf(parsed.start to parsed.end)
            for (hole in holes) {
                ranges = ranges.flatMap { (s, e) ->
                    when {
                        hole < s || hole > e -> listOf(s to e)
                        else -> buildList {
                            if (hole > s) add(s to hole - BigInteger.ONE)
                            if (hole < e) add(hole + BigInteger.ONE to e)
                        }
                    }
                }
            }
            for ((s, e) in ranges) out += toCidrs(s, e, parsed.v6)
        }
        return out
    }

    // ------------------------------------------------------------ parsing --

    private data class Cidr(val start: BigInteger, val end: BigInteger, val v6: Boolean)

    private fun parseCidr(cidr: String): Cidr? = runCatching {
        val (addr, prefixText) = if ('/' in cidr) {
            cidr.substringBeforeLast('/') to cidr.substringAfterLast('/')
        } else {
            cidr to null
        }
        val address = InetAddress.getByName(addr)
        val v6 = address.address.size == 16
        val bits = if (v6) 128 else 32
        val prefix = prefixText?.toInt() ?: bits
        if (prefix < 0 || prefix > bits) return null
        val start = BigInteger(1, address.address)
            .shiftRight(bits - prefix).shiftLeft(bits - prefix)
        val end = start + BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE
        Cidr(start, end, v6)
    }.getOrNull()

    /** [value] as a single address of the wanted family, or null. */
    private fun addressOf(value: String, v6: Boolean): BigInteger? = runCatching {
        val address = InetAddress.getByName(value)
        if ((address.address.size == 16) != v6) return null
        BigInteger(1, address.address)
    }.getOrNull()

    // ------------------------------------------------------ range → cidrs --

    /** Greedy decomposition of an inclusive range into aligned CIDR blocks. */
    private fun toCidrs(startIn: BigInteger, end: BigInteger, v6: Boolean): List<Route> {
        val bits = if (v6) 128 else 32
        val routes = mutableListOf<Route>()
        var start = startIn
        while (start <= end) {
            // The widest block that is aligned at `start` …
            var size = start.lowestSetBit.let { if (it == -1) bits else minOf(it, bits) }
            // … and does not overshoot `end`.
            while (size > 0 && start + BigInteger.ONE.shiftLeft(size) - BigInteger.ONE > end) {
                size--
            }
            routes += Route(textOf(start, v6), bits - size)
            start += BigInteger.ONE.shiftLeft(size)
        }
        return routes
    }

    private fun textOf(value: BigInteger, v6: Boolean): String {
        val length = if (v6) 16 else 4
        val raw = value.toByteArray().let { bytes ->
            when {
                bytes.size == length -> bytes
                bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
                else -> ByteArray(length - bytes.size) + bytes
            }
        }
        return InetAddress.getByAddress(raw).hostAddress ?: value.toString()
    }
}
