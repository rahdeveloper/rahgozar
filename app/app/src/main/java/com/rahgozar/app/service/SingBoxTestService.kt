package com.rahgozar.app.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs sing-box delay tests, in a process of its own.
 *
 * It has to be its own process, and not the one [CoreTestService] runs in:
 * that process has the Xray runtime loaded, and a process can hold exactly one
 * gomobile runtime (see docs/SINGBOX-INTEGRATION.md). Nor can it share the
 * tunnel's process — the two would fight over libbox's process-global paths,
 * and the connect-time gate deliberately measures *while* the tunnel is up.
 *
 * Kept deliberately thin: the batch, its ordering and every message the UI
 * sees still belong to [RealPingWorkerService]. All that crosses this boundary
 * is one measurement and one number.
 */
class SingBoxTestService : Service() {

    companion object {
        const val MSG_MEASURE = 1
        const val MSG_RESULT = 2

        /**
         * Sent the moment a request leaves the queue.
         *
         * Without it the caller cannot tell waiting from working, and would
         * have to time both — so a healthy server queued behind two others
         * would be reported dead for the sin of being third. See
         * [SingBoxDelayBridge].
         */
        const val MSG_STARTED = 3

        const val KEY_CONFIG = "config"
        const val KEY_TEST_URL = "testUrl"
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_DELAY = "delay"

        /**
         * Marks a measurement someone is waiting on right now — the connect
         * gate, or a single row the user just tapped — so it is taken before a
         * queue of background ones rather than behind it.
         */
        const val KEY_URGENT = "urgent"

        private const val TAG = "SingBox-TestService"
    }

    private class Request(
        val id: Int,
        val config: String,
        val testUrl: String,
        val urgent: Boolean,
        val reply: Messenger?,
        val sequence: Long,
    ) : Comparable<Request> {
        override fun compareTo(other: Request): Int {
            if (urgent != other.urgent) return if (urgent) -1 else 1
            return sequence.compareTo(other.sequence)
        }
    }

    private val queue = PriorityBlockingQueue<Request>()
    private val sequence = AtomicLong(0)

    /**
     * Measuring blocks for as long as the core takes to answer, so it happens
     * on this thread rather than on the one receiving messages — which must
     * stay free to keep accepting them, or a request could not be marked
     * urgent while another is running.
     *
     * One thread, because [SingBoxDelayTest] can only run one core at a time.
     */
    private var worker: Thread? = null

    @Volatile
    private var running = true

    /** Only enqueues, so the main looper is never blocked. */
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == MSG_MEASURE) enqueue(message)
        true
    })

    override fun onCreate() {
        super.onCreate()
        worker = Thread(::loop, "singbox-test").also { it.start() }
        LogUtil.i(AppConfig.TAG, "$TAG: ready")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "$TAG: stopped")
    }

    private fun enqueue(message: Message) {
        val data = message.data ?: return
        queue.put(
            Request(
                id = data.getInt(KEY_REQUEST_ID),
                config = data.getString(KEY_CONFIG).orEmpty(),
                testUrl = data.getString(KEY_TEST_URL).orEmpty(),
                urgent = data.getBoolean(KEY_URGENT, false),
                reply = message.replyTo,
                sequence = sequence.incrementAndGet(),
            )
        )
    }

    private fun loop() {
        while (running) {
            val request = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }

            // Announced before the work starts, so the caller's clock covers
            // the measurement and not the wait for its turn.
            send(request, MSG_STARTED, null)

            val delay = if (request.config.isBlank() || request.testUrl.isBlank()) {
                -1L
            } else {
                runCatching { SingBoxDelayTest.measure(this, request.config, request.testUrl) }
                    .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: measurement threw", it) }
                    .getOrDefault(-1L)
            }
            send(request, MSG_RESULT, delay)
        }
    }

    private fun send(request: Request, what: Int, delay: Long?) {
        // A caller that has gone away is not an error: the batch it belonged
        // to was cancelled, and the result has nowhere to go.
        try {
            request.reply?.send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putInt(KEY_REQUEST_ID, request.id)
                    delay?.let { putLong(KEY_DELAY, it) }
                }
            })
        } catch (e: RemoteException) {
            LogUtil.i(AppConfig.TAG, "$TAG: caller is gone, dropping message: ${e.message}")
        }
    }
}
