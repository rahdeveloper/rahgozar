package com.rahgozar.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks [SingBoxTestService] for a measurement and waits for the answer.
 *
 * The wait is deliberate. [RealPingWorkerService] already runs each server on
 * its own coroutine and reports results as they land, so a measurement that
 * blocks its own coroutine behaves exactly like the Xray one that blocks
 * inside the native call — and the batch, the per-row state and the finish
 * message stay in a single place instead of being split across processes.
 *
 * The wait is in two parts, and that matters. The test process measures one
 * server at a time, so a request may sit in its queue for as long as the
 * servers ahead of it take. Timing that as if it were the measurement would
 * report every server after the second or third as dead — so the clock that
 * decides "this server failed" only starts when the test process says it has
 * picked the request up.
 */
internal object SingBoxDelayBridge {

    private const val TAG = "SingBox-DelayBridge"

    /**
     * How long a measurement itself may take once it has started: the probe's
     * own 8s ceiling plus room to start and close a core around it.
     */
    private const val MEASURE_TIMEOUT_MS = 13_000L

    /**
     * How long a request may wait for its turn. Generous because the queue
     * ahead of it is legitimate work — a long list of sing-box servers is
     * measured one at a time — and because process death is reported through
     * onServiceDisconnected rather than by falling silent. This is only a
     * backstop against a test process that stops answering without dying.
     */
    private const val QUEUE_TIMEOUT_MS = 300_000L

    private const val BIND_TIMEOUT_MS = 5_000L

    private const val FAILED = -1L

    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Slot>()

    private class Slot {
        /** Counted down when the test process picks the request up. */
        val started = CountDownLatch(1)

        /** Counted down when it has an answer — or when the process dies. */
        val finished = CountDownLatch(1)

        @Volatile
        var delay = FAILED
    }

    private val callbackThread by lazy {
        HandlerThread("singbox-delay-reply").also { it.start() }
    }

    private val replyMessenger by lazy {
        Messenger(Handler(callbackThread.looper) { message ->
            val id = message.data?.getInt(SingBoxTestService.KEY_REQUEST_ID) ?: return@Handler true
            when (message.what) {
                SingBoxTestService.MSG_STARTED -> pending[id]?.started?.countDown()

                SingBoxTestService.MSG_RESULT -> pending.remove(id)?.let { slot ->
                    slot.delay = message.data.getLong(SingBoxTestService.KEY_DELAY, FAILED)
                    // Started too, in case the result overtakes the ack.
                    slot.started.countDown()
                    slot.finished.countDown()
                }
            }
            true
        })
    }

    private val lock = Any()

    @Volatile
    private var remote: Messenger? = null

    /** Non-null while a binding is registered, connected or not. */
    private var connection: ServiceConnection? = null

    /** Non-null while someone is waiting for [remote] to appear. */
    private var connectLatch: CountDownLatch? = null

    /**
     * One instance for the life of the process, reused across bind cycles.
     *
     * Reconnection matters here: if the test process is killed, the binding
     * stays registered and Android restarts the service, so this same object
     * is called again — which is why [connect] waits for it rather than
     * binding a second time and leaking the first registration.
     */
    private val callbacks = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) {
                remote = binder?.let { Messenger(it) }
                connectLatch?.countDown()
                connectLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            LogUtil.w(AppConfig.TAG, "$TAG: test process went away")
            synchronized(lock) { remote = null }
            // Anything still waiting will never be answered now.
            releasePending()
        }
    }

    /**
     * @param urgent true when something is waiting on this answer right now —
     *   the connect gate, or a single row the user tapped — so it is taken
     *   ahead of a queue of background measurements
     * @return the measured delay in milliseconds, or -1 if the server did not
     *   carry the request — the same contract as the Xray path
     */
    fun measure(context: Context, config: String, testUrl: String, urgent: Boolean = false): Long {
        // Claimed before binding, not after: releaseIfIdle unbinds when nothing
        // is pending, and a slot registered later would let a batch ending
        // beside this one pull the connection out from under it.
        val id = nextRequestId.getAndIncrement()
        val slot = Slot()
        pending[id] = slot

        val service = connect(context)
        if (service == null) {
            pending.remove(id)
            return FAILED
        }

        val message = Message.obtain(null, SingBoxTestService.MSG_MEASURE).apply {
            data = Bundle().apply {
                putInt(SingBoxTestService.KEY_REQUEST_ID, id)
                putString(SingBoxTestService.KEY_CONFIG, config)
                putString(SingBoxTestService.KEY_TEST_URL, testUrl)
                putBoolean(SingBoxTestService.KEY_URGENT, urgent)
            }
            replyTo = replyMessenger
        }

        return try {
            service.send(message)

            if (!slot.started.await(QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LogUtil.w(AppConfig.TAG, "$TAG: never got a turn")
                // Nothing came back at all, so the process is not answering;
                // the next call rebinds rather than joining a dead queue.
                dropConnection(context)
                return FAILED
            }

            if (slot.finished.await(MEASURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                slot.delay
            } else {
                LogUtil.w(AppConfig.TAG, "$TAG: no answer within ${MEASURE_TIMEOUT_MS}ms of starting")
                FAILED
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: could not reach the test process", e)
            // The process may have died mid-batch; the next call rebinds.
            dropConnection(context)
            FAILED
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Lets the test process go once a batch has finished with it.
     *
     * Nothing forces this — the binding would survive on its own — but an idle
     * sing-box process holds a whole Go runtime, and a phone should not carry
     * that between tests. Skipped while any measurement is still outstanding,
     * because a second batch may be running beside the one that just ended.
     */
    fun releaseIfIdle(context: Context) {
        synchronized(lock) {
            if (pending.isNotEmpty() || connection == null) return
            unbindLocked(context)
        }
    }

    /**
     * Bound against the application context on purpose.
     *
     * The caller is [CoreTestService], which stops itself at the end of every
     * batch — and a binding registered against a service that goes away is
     * torn down by the framework *without* an onServiceDisconnected callback,
     * which would leave this object holding a Messenger whose looper has quit.
     * Every later measurement would then block until it timed out and report a
     * healthy server as dead.
     */
    private fun connect(context: Context): Messenger? {
        remote?.let { return it }

        val application = context.applicationContext
        val latch = synchronized(lock) {
            remote?.let { return it }
            connectLatch?.let { return@synchronized it }

            val latch = CountDownLatch(1)
            connectLatch = latch

            // Only bind when nothing is registered. A registration that exists
            // but has no Messenger is a service Android is restarting for us,
            // and binding again would leak the first one.
            if (connection == null) {
                val bound = runCatching {
                    application.bindService(
                        Intent(application, SingBoxTestService::class.java),
                        callbacks,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrDefault(false)

                if (!bound) {
                    LogUtil.e(AppConfig.TAG, "$TAG: could not bind the test process")
                    // Documented contract: a refused bind still leaves the
                    // connection registered, so it has to be released.
                    runCatching { application.unbindService(callbacks) }
                    connectLatch = null
                    return null
                }
                connection = callbacks
            }
            latch
        }

        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LogUtil.e(AppConfig.TAG, "$TAG: test process did not come up in time")
            // Cleared, or every later call would wait on a latch nothing will
            // ever count down. The binding itself stays: it may yet connect,
            // and the next call will wait on a fresh latch.
            synchronized(lock) { if (connectLatch === latch) connectLatch = null }
            return null
        }
        return remote
    }

    /** Drops a binding that is not answering, so the next call makes a new one. */
    private fun dropConnection(context: Context) {
        synchronized(lock) { unbindLocked(context.applicationContext) }
        releasePending()
    }

    private fun unbindLocked(context: Context) {
        connection?.let { runCatching { context.applicationContext.unbindService(it) } }
        connection = null
        remote = null
        // Waiters wake to a null remote, which is the right answer now.
        connectLatch?.countDown()
        connectLatch = null
    }

    private fun releasePending() {
        pending.values.forEach { it.started.countDown(); it.finished.countDown() }
        pending.clear()
    }
}
