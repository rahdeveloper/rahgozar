package com.rahgozar.app

import com.rahgozar.app.service.RouteCarver
import java.math.BigInteger
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route table with holes must be *exactly* the whole minus the holes:
 * every excluded address outside it, every other address still inside, and
 * nothing double-covered. Below Android 13 this arithmetic is the only thing
 * standing between a smart session and a tunnel that eats its own uplink, so
 * the checks here are set arithmetic, not spot checks.
 */
class RouteCarverTest {

    private fun value(address: String): BigInteger =
        BigInteger(1, InetAddress.getByName(address).address)

    private fun covers(routes: List<RouteCarver.Route>, address: String): Boolean {
        val bits = if (':' in address) 128 else 32
        val target = value(address)
        return routes.filter { (':' in it.address) == (':' in address) }.any { route ->
            val start = value(route.address)
                .shiftRight(bits - route.prefix).shiftLeft(bits - route.prefix)
            val end = start + BigInteger.ONE.shiftLeft(bits - route.prefix) - BigInteger.ONE
            target in start..end
        }
    }

    private fun addressCount(routes: List<RouteCarver.Route>, v6: Boolean): BigInteger =
        routes.filter { (':' in it.address) == v6 }
            .fold(BigInteger.ZERO) { acc, route ->
                acc + BigInteger.ONE.shiftLeft((if (v6) 128 else 32) - route.prefix)
            }

    @Test
    fun one_hole_in_the_full_v4_space() {
        val routes = RouteCarver.carve(listOf("0.0.0.0/0"), listOf("45.128.76.1"))

        assertEquals(32, routes.size)
        assertFalse(covers(routes, "45.128.76.1"))
        assertTrue(covers(routes, "45.128.76.0"))
        assertTrue(covers(routes, "45.128.76.2"))
        assertTrue(covers(routes, "0.0.0.0"))
        assertTrue(covers(routes, "255.255.255.255"))
        // |0.0.0.0/0| - 1
        assertEquals(
            BigInteger.ONE.shiftLeft(32) - BigInteger.ONE,
            addressCount(routes, v6 = false),
        )
    }

    @Test
    fun several_holes_at_once() {
        val holes = listOf("45.128.76.1", "1.2.3.4", "200.1.1.1")
        val routes = RouteCarver.carve(listOf("0.0.0.0/0"), holes)

        for (hole in holes) assertFalse(covers(routes, hole))
        assertTrue(covers(routes, "8.8.8.8"))
        assertEquals(
            BigInteger.ONE.shiftLeft(32) - BigInteger.valueOf(3),
            addressCount(routes, v6 = false),
        )
    }

    @Test
    fun hole_inside_a_narrow_base() {
        // The bypass-LAN table routes fragments, not 0/0. A hole lands inside
        // exactly one fragment; the rest must come through untouched.
        val base = listOf("8.0.0.0/7", "16.0.0.0/4", "64.0.0.0/2")
        val routes = RouteCarver.carve(base, listOf("64.233.161.99"))

        assertFalse(covers(routes, "64.233.161.99"))
        assertTrue(covers(routes, "64.233.161.98"))
        assertTrue(covers(routes, "8.8.8.8"))
        assertTrue(covers(routes, "17.0.0.1"))
        val baseCount = BigInteger.ONE.shiftLeft(25) + // /7
            BigInteger.ONE.shiftLeft(28) + // /4
            BigInteger.ONE.shiftLeft(30) // /2
        assertEquals(baseCount - BigInteger.ONE, addressCount(routes, v6 = false))
    }

    @Test
    fun hole_outside_every_base_changes_nothing() {
        val base = listOf("8.0.0.0/7", "16.0.0.0/4")
        val routes = RouteCarver.carve(base, listOf("192.168.1.1"))

        assertEquals(2, routes.size)
        assertEquals(base.toSet(), routes.map { "${it.address}/${it.prefix}" }.toSet())
    }

    @Test
    fun v6_hole_only_cuts_v6_bases() {
        val routes = RouteCarver.carve(
            listOf("0.0.0.0/0", "2000::/3"),
            listOf("2a06:98c1:52::8"),
        )

        assertFalse(covers(routes, "2a06:98c1:52::8"))
        assertTrue(covers(routes, "2a06:98c1:52::7"))
        assertTrue(covers(routes, "2600::1"))
        // The v4 side must be exactly the untouched base.
        assertEquals(BigInteger.ONE.shiftLeft(32), addressCount(routes, v6 = false))
        assertEquals(
            BigInteger.ONE.shiftLeft(125) - BigInteger.ONE,
            addressCount(routes, v6 = true),
        )
    }

    @Test
    fun malformed_exclusion_degrades_to_no_hole() {
        val routes = RouteCarver.carve(listOf("0.0.0.0/0"), listOf("not-an-address"))
        assertEquals(1, routes.size)
        assertEquals("0.0.0.0", routes.first().address)
        assertEquals(0, routes.first().prefix)
    }

    @Test
    fun no_route_overlaps_another() {
        val routes = RouteCarver.carve(listOf("0.0.0.0/0"), listOf("45.128.76.1", "45.128.76.9"))
        val ranges = routes.map { route ->
            val start = value(route.address)
            start to start + BigInteger.ONE.shiftLeft(32 - route.prefix) - BigInteger.ONE
        }.sortedBy { it.first }
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i - 1].second < ranges[i].first)
        }
    }
}
