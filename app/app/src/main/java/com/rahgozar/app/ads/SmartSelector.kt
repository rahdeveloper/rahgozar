package com.rahgozar.app.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.rahgozar.app.AppConfig
import com.rahgozar.app.dto.TestServiceMessage
import com.rahgozar.app.enums.EConfigType
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks a smart candidate that actually answers.
 *
 * The trap this exists for: a core start "succeeding" proves nothing about
 * the server — a dead one still brings the tunnel up, and the splash would
 * then wait out the whole ad timeout on a path that carries nothing. So the
 * candidates are measured first, all at once, through the same test service
 * the server list uses: it builds a standalone outbound to each server, which
 * is a real request over the real network, not the core's own opinion.
 *
 * First answer wins. With several candidates the fastest healthy one replies
 * in a couple of seconds, and waiting for the full round to rank them would
 * hold the splash for the slowest dead server instead — the exact trade the
 * server list's auto-pick already refuses.
 */
object SmartSelector {

    /** Ample for an Xray probe, which answers or fails in a few seconds. */
    private const val MEASURE_TIMEOUT_MS = 12_000L

    /**
     * A sing-box probe starts a whole core in another process first; cutting
     * it off at Xray's deadline would fail servers that were about to answer.
     * Same reasoning, same number, as the home screen's verification gate.
     */
    private const val SINGBOX_MEASURE_TIMEOUT_MS = 22_000L

    /**
     * Measures [guids] in parallel and returns the first that answered, or
     * null when the round finished, timed out, or was empty — all of which
     * mean the same thing to the caller: nothing here is worth connecting to.
     */
    suspend fun firstWorking(context: Context, guids: List<String>): String? {
        if (guids.isEmpty()) return null

        MmkvManager.clearAllTestDelayResults(guids)

        val winner = CompletableDeferred<String?>()
        val pending = guids.toMutableSet()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getIntExtra("key", 0)) {
                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                        // Fires per server, for failures too; the verdict is
                        // the stored delay, not the message's name.
                        val guid = intent.getStringExtra("content") ?: return
                        if (!pending.remove(guid)) return
                        val delay = MmkvManager.decodeServerAffiliationInfo(guid)
                            ?.testDelayMillis ?: -1L
                        if (delay > 0) {
                            winner.complete(guid)
                        } else if (pending.isEmpty()) {
                            winner.complete(null)
                        }
                    }

                    // The round ended. A positive result would already have
                    // won; completing again is a no-op.
                    AppConfig.MSG_MEASURE_CONFIG_FINISH -> winner.complete(null)
                }
            }
        }

        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
        return try {
            MessageHelper.sendMsg2TestService(
                context,
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    serverGuids = guids,
                ),
            )
            val picked = withTimeoutOrNull(timeoutFor(guids)) { winner.await() }
            LogUtil.i(
                AppConfig.TAG,
                "ads: smart selection → ${picked ?: "none answered"} (${guids.size} measured)",
            )
            picked
        } finally {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            // The answer is decided; probes still running are cost for nothing.
            runCatching {
                MessageHelper.sendMsg2TestService(
                    context,
                    TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL),
                )
            }
        }
    }

    private fun timeoutFor(guids: List<String>): Long =
        if (guids.any { MmkvManager.decodeServerConfig(it)?.configType == EConfigType.SINGBOX }) {
            SINGBOX_MEASURE_TIMEOUT_MS
        } else {
            MEASURE_TIMEOUT_MS
        }
}
