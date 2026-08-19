package com.rahgozar.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.rahgozar.app.AppConfig
import com.rahgozar.app.R
import com.rahgozar.app.core.CoreNativeManager
import com.rahgozar.app.dto.RealPingEvent
import com.rahgozar.app.dto.TestServiceMessage
import com.rahgozar.app.enums.NotificationChannelType
import com.rahgozar.app.extension.serializable
import com.rahgozar.app.handler.AngConfigManager
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.helper.NotificationHelper
import com.rahgozar.app.util.LogUtil
import java.util.Collections

class CoreTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    /**
     * True from the moment this service starts shutting down.
     *
     * Cancelling a batch does not stop it immediately: each measurement's
     * `finally` runs afterwards, on its own thread, and emits one last
     * progress event. Those arrived *after* the notification had been taken
     * down and posted it again — this time with nothing to pull it back, so
     * it sat in the user's shade reading "0 / 0" until they swiped it away.
     * That is the notification the ad flow leaves behind on every launch,
     * because the smart selection cancels its round the moment a winner
     * answers.
     */
    @Volatile
    private var stopping = false

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        takeNotificationDown()
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new round: whatever was shutting down before, this one is running.
        stopping = false
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            // Not the menu item's wording. This is a notification a user sees
            // without having asked for anything — the ad flow measures servers
            // on its own — so it says what is happening, not which internal
            // operation is running.
            getString(R.string.notif_checking_servers)
        )
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(message, startId)
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel()
            else -> {
                NotificationHelper.stopForeground(this); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting worker   subscription ${message.subscriptionId}")

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guidsList,
                onlyTcp = message.onlyTcp,
                onEvent = { event -> handleWorkerEvent(event, message) { activeWorkers.remove(worker) } }
            )
            activeWorkers.add(worker)
            worker.start()
        } else {
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, message: TestServiceMessage, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                // Never once shutting down: see [stopping].
                if (!stopping) {
                    NotificationHelper.updateNotification(
                        channelType = NotificationChannelType.CORE_TEST,
                        context = this,
                        title = getString(R.string.app_name),
                        content = getString(R.string.connection_runing_task_left, event.text)
                    )
                }
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, event.text)
            }

            is RealPingEvent.Started -> {
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_TESTING, event.guid)
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
            }

            is RealPingEvent.Finish -> {
                if(message.subscriptionId.isNotEmpty()){
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                        AngConfigManager.removeInvalidServer(message.subscriptionId)
                    }

                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)) {
                        AngConfigManager.sortByTestResultsForSub(message.subscriptionId)
                    }
                }

                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status)
                onWorkerDone()
                if (activeWorkers.isEmpty()) {
                    takeNotificationDown()
                    stopSelf()
                }
            }
        }
    }

    private fun handleMeasureCancel() {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        takeNotificationDown()
        stopSelf()
    }

    /**
     * Removes the notification and closes the door behind it.
     *
     * Both halves are needed. `stopForeground` detaches and removes what
     * `startForeground` put up, but progress updates post through the plain
     * notification manager, so one that landed in between outlives it — hence
     * the explicit cancel. And [stopping] is what keeps the next straggler
     * from putting it back up.
     */
    private fun takeNotificationDown() {
        stopping = true
        NotificationHelper.stopForeground(this)
        NotificationHelper.cancel(NotificationChannelType.CORE_TEST, this)
    }
}
