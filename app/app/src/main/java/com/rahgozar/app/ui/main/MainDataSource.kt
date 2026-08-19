package com.rahgozar.app.ui.main

import com.rahgozar.app.dto.TestServiceMessage
import com.rahgozar.app.dto.entities.ProfileItem
import com.rahgozar.app.dto.entities.ServerAffiliationInfo
import com.rahgozar.app.dto.entities.SubscriptionCache
import com.rahgozar.app.dto.entities.SubscriptionItem
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * What the main screen is allowed to ask for.
 *
 * The interface is the enforcement point for "configurations come from the
 * panel and nowhere else": there is no way to import one, no way to render one
 * back out, and no per-server delete. Groups are still exposed because the
 * panel organises servers into them, not because the user maintains them.
 */
interface MainDataSource : Closeable {
    val mainServiceEvent: Flow<MainServiceEvent>

    fun getSelectedSubscriptionId(): String
    fun setSelectedSubscriptionId(id: String)

    fun getSelectServer(): String?
    fun setSelectServer(guid: String)

    fun getDoubleColumnDisplay(): Boolean
    fun isGroupAllDisplayEnabled(): Boolean

    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String

    fun getSubscriptions(): List<SubscriptionCache>
    fun getSubscriptionItem(id: String): SubscriptionItem?

    fun getServerGuidList(groupId: String): List<String>
    fun decodeServerConfig(guid: String): ProfileItem?
    fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo?

    fun encodeServerList(guids: List<String>, groupId: String)

    fun removeInvalidServerByGuid(guid: String): Int
    fun removeInvalidServersInGroup(groupId: String): Int

    fun clearAllTestDelayResults(guids: List<String>)
    fun sortByTestResultsForSub(subId: String)
    fun getSubsList(): List<String>

    fun sendMsg2Service(msgId: Int, content: String)
    fun sendMsg2TestService(msg: TestServiceMessage)
    fun cancelAllPing()
    fun testCurrentServerRealPing()

    fun initAssets()
}
