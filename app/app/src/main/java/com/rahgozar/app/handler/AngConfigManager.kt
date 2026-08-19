package com.rahgozar.app.handler

import com.rahgozar.app.dto.entities.ProfileItem

/**
 * What is left of profile handling once configurations stop coming from the
 * user.
 *
 * This object used to be the app's import and export surface: parse a link,
 * decode a QR image, pull a subscription URL, render a profile back out to the
 * clipboard or a QR bitmap. All of it is gone. The server list arrives inside a
 * signed, per-device-encrypted bootstrap response and nothing in the app turns
 * a profile back into something a person can copy.
 *
 * Three read-only operations remain, none of which can produce a credential:
 * ordering by measured latency, dropping entries that failed their test, and
 * rendering a masked one-line description for the list.
 */
object AngConfigManager {

    /**
     * Removes servers whose latency test failed.
     *
     * @param subId The group ID.
     */
    fun removeInvalidServer(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        val invalidServers = serverList.filter {
            val aff = MmkvManager.decodeServerAffiliationInfo(it)
            aff != null && aff.testDelayMillis < 0L
        }
        MmkvManager.removeServers(invalidServers, subId)
    }

    /**
     * Sorts servers by measured latency, slowest and untested last.
     *
     * @param subId The group ID.
     */
    fun sortByTestResultsForSub(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        if (serverList.isEmpty()) return

        val sorted = serverList
            .map { guid ->
                val delay =
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                guid to if (delay <= 0L) Long.MAX_VALUE else delay
            }
            .sortedBy { it.second }
            .map { it.first }
            .toMutableList()
        MmkvManager.encodeServerList(sorted, subId)
    }

    /**
     * A one-line label for the server list.
     *
     * The address is masked — last octet for IPv4, everything past the second
     * group for IPv6 — so a screenshot of the app, which users do send to
     * support and post publicly, does not carry a working address with it.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
