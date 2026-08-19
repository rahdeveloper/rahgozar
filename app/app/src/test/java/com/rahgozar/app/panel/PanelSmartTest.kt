package com.rahgozar.app.panel

import com.rahgozar.app.panel.PanelSync.pickSmartProfiles
import com.rahgozar.app.panel.PanelSync.smartGuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the Smart tunnel's candidates out of the panel's smart list.
 *
 * The properties that matter: the panel's ordering is respected (the list is
 * the failover order), a broken list can never produce profiles that
 * half-parse, an empty list is a real "there are none" while a malformed one
 * is "keep what you have", and whatever is kept is stamped as panel-owned.
 */
class PanelSmartTest {

    // The same shape /v1/bootstrap's `smart` box decrypts to: PanelServer
    // entries whose `config` is a ProfileItem.
    private val twoServers = """
        [
          {"id":"b1","protocol":"vless","remarks":"Smart B","sort":5,
           "config":{"configType":"VLESS","server":"5.6.7.8","serverPort":"443","remarks":"","subscriptionId":"sub-x"}},
          {"id":"a1","protocol":"vless","remarks":"Smart A","sort":1,
           "config":{"configType":"VLESS","server":"1.2.3.4","serverPort":"443","remarks":""}}
        ]
    """.trimIndent()

    @Test
    fun `all candidates are kept, ordered by the panel's sort`() {
        val profiles = pickSmartProfiles(twoServers)!!

        assertEquals(listOf("1.2.3.4", "5.6.7.8"), profiles.map { it.server })
        assertEquals(listOf("Smart A", "Smart B"), profiles.map { it.remarks })
        // Panel-owned: nothing may tie these to a subscription.
        assertTrue(profiles.all { it.subscriptionId == "" })
    }

    @Test
    fun `entries without a config are skipped, not tripped over`() {
        val list = """
            [
              {"id":"x","protocol":"vless","remarks":"broken","sort":0},
              {"id":"y","protocol":"vless","remarks":"whole","sort":9,
               "config":{"configType":"VLESS","server":"9.9.9.9","serverPort":"443","remarks":""}}
            ]
        """.trimIndent()

        assertEquals(listOf("9.9.9.9"), pickSmartProfiles(list)!!.map { it.server })
    }

    @Test
    fun `an empty list is a real answer`() {
        assertEquals(emptyList<Any>(), pickSmartProfiles("[]"))
    }

    @Test
    fun `a malformed list yields null - keep what you have - rather than throwing`() {
        assertNull(pickSmartProfiles("not a list at all"))
    }

    // ------------------------------------------------------- stored guids --

    @Test
    fun `stored guid list round-trips and tolerates junk`() {
        assertEquals(
            listOf("panel-smart-0", "panel-smart-1"),
            smartGuids("""["panel-smart-0","panel-smart-1"]"""),
        )
        assertEquals(emptyList<String>(), smartGuids(""))
        assertEquals(emptyList<String>(), smartGuids("not json"))
    }
}
