package com.rahgozar.app.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.rahgozar.app.AngApplication
import com.rahgozar.app.AppConfig
import com.rahgozar.app.R
import com.rahgozar.app.dto.TestServiceMessage
import com.rahgozar.app.dto.entities.ProfileItem
import com.rahgozar.app.dto.entities.ServerAffiliationInfo
import com.rahgozar.app.dto.entities.SubscriptionCache
import com.rahgozar.app.dto.entities.SubscriptionItem
import com.rahgozar.app.handler.AngConfigManager
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class MainRepository(
    private val app: AngApplication
) : MainDataSource {

    private val closed = AtomicBoolean(false)

    private val _mainServiceEvent = MutableSharedFlow<MainServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val mainServiceEvent: SharedFlow<MainServiceEvent> = _mainServiceEvent.asSharedFlow()

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val event = when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.StateRunning
                AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.StateNotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.StateStartSuccess
                AppConfig.MSG_STATE_START_FAILURE -> MainServiceEvent.StateStartFailure(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.StateStopSuccess
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> MainServiceEvent.MeasureDelaySuccess(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_MEASURE_DELAY_RESULT -> MainServiceEvent.MeasureDelayResult(
                    safeIntent.getStringExtra("content")?.toLongOrNull() ?: -1L
                )

                AppConfig.MSG_MEASURE_CONFIG_TESTING -> MainServiceEvent.MeasureConfigTesting(
                    safeIntent.getStringExtra("content").orEmpty()
                )
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> MainServiceEvent.MeasureConfigSuccess(
                    safeIntent.getStringExtra("content").orEmpty()
                )
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> MainServiceEvent.MeasureConfigNotify(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> MainServiceEvent.MeasureConfigFinish(
                    safeIntent.getStringExtra("content")
                )

                AppConfig.MSG_SPEED_UPDATE -> {
                    val parts = safeIntent.getStringExtra("content").orEmpty().split(",")
                    if (parts.size == 4) {
                        MainServiceEvent.SpeedUpdate(
                            upBytesPerSec = parts[0].toLongOrNull() ?: 0L,
                            downBytesPerSec = parts[1].toLongOrNull() ?: 0L,
                            sessionUpBytes = parts[2].toLongOrNull() ?: 0L,
                            sessionDownBytes = parts[3].toLongOrNull() ?: 0L,
                        )
                    } else {
                        null
                    }
                }

                else -> null
            }
            event?.let { _mainServiceEvent.tryEmit(it) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "")
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister service client", it)
        }
        runCatching {
            app.unregisterReceiver(serviceReceiver)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister main service receiver", it)
        }
    }

    override fun getSelectedSubscriptionId(): String =
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    override fun setSelectedSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, id)
    }

    override fun getSelectServer(): String? = MmkvManager.getSelectServer()

    override fun setSelectServer(guid: String) = MmkvManager.setSelectServer(guid)

    override fun getDoubleColumnDisplay(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    override fun isGroupAllDisplayEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)

    override fun getString(resId: Int): String = app.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String = app.getString(resId, *formatArgs)

    override fun getSubscriptions(): List<SubscriptionCache> {
        val result = mutableListOf<SubscriptionCache>()
        if (isGroupAllDisplayEnabled()) {
            result += SubscriptionCache(
                guid = "",
                subscription = SubscriptionItem().apply {
                    remarks = app.getString(R.string.filter_config_all)
                }
            )
        }
        result += MmkvManager.decodeSubscriptions()
        return result
    }

    override fun getSubscriptionItem(id: String): SubscriptionItem? =
        MmkvManager.decodeSubscription(id)

    override fun getServerGuidList(groupId: String): List<String> =
        if (groupId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(groupId)
        }

    override fun decodeServerConfig(guid: String): ProfileItem? =
        MmkvManager.decodeServerConfig(guid)

    override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? =
        MmkvManager.decodeServerAffiliationInfo(guid)

    override fun encodeServerList(guids: List<String>, groupId: String) =
        MmkvManager.encodeServerList(ArrayList(guids), groupId)

    override fun removeInvalidServerByGuid(guid: String): Int =
        MmkvManager.removeInvalidServer(guid)

    override fun removeInvalidServersInGroup(groupId: String): Int =
        if (groupId.isEmpty()) {
            MmkvManager.removeInvalidServer("")
        } else {
            getServerGuidList(groupId).sumOf(::removeInvalidServerByGuid)
        }

    override fun clearAllTestDelayResults(guids: List<String>) =
        MmkvManager.clearAllTestDelayResults(guids)

    override fun sortByTestResultsForSub(subId: String) {
        AngConfigManager.sortByTestResultsForSub(subId)
    }

    override fun getSubsList(): List<String> = MmkvManager.decodeSubsList()

    override fun sendMsg2Service(msgId: Int, content: String) =
        MessageHelper.sendMsg2Service(app, msgId, content)

    override fun sendMsg2TestService(msg: TestServiceMessage) =
        MessageHelper.sendMsg2TestService(app, msg)

    override fun cancelAllPing() {
        sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    override fun testCurrentServerRealPing() {
        sendMsg2Service(AppConfig.MSG_MEASURE_DELAY, "")
    }

    override fun initAssets() {
        SettingsManager.initAssets(app, app.assets)
    }
}
