package com.rahgozar.app.ads

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.util.LogUtil

/**
 * Stops the ad SDK's deferred work from running outside a smart session.
 *
 * The Google Mobile Ads SDK buffers pings it could not deliver — a click or
 * conversion whose request failed — and enqueues its own WorkManager jobs
 * (`OfflinePingSender`, `OfflineNotificationPoster`) to send them later. Later
 * means minutes or hours after, in this app's `:bg` process, on nothing more
 * than a "network is connected" constraint. There is no tunnel then, and
 * nothing in the ad flow can bring one up for a job it never scheduled: the
 * ping would carry ad and device identifiers out on the user's real address,
 * which is the one thing the smart tunnel exists to prevent.
 *
 * So in the countries the panel says need a tunnel, those jobs are answered
 * without doing anything. It costs the attribution for a click the SDK
 * already failed to report once; the alternative costs the user the address
 * they installed this app to hide. Everywhere else — where ads are allowed to
 * go out directly anyway — the real worker runs untouched.
 *
 * Returning null from [createWorker] is WorkManager's documented way of
 * saying "not mine", so every other worker in the app is built exactly as it
 * was before.
 */
object AdWorkerGate : WorkerFactory() {

    private const val GMA_OFFLINE_PREFIX = "com.google.android.gms.ads.internal.offline"

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (!workerClassName.startsWith(GMA_OFFLINE_PREFIX)) return null

        // Not our problem where no tunnel was ever required: the ad traffic
        // this ping belongs to went out directly too.
        if (!PanelStore.smartRequired) return null

        // Inside a live session the ping travels the same path the ad did.
        //
        // Asked as a fact, not as a flag. This runs in whichever process
        // WorkManager happened to start — often one that has no Activity and
        // has therefore never reconciled the stored session flag against
        // reality. That flag is only ever cleared by the app's own teardown,
        // so after a reboot, a killed tunnel process, or the user switching
        // the VPN off, it reads `true` over nothing at all — and returning
        // null there hands the ping to Google's real worker, which sends the
        // buffered click and its device identifiers on the user's own
        // address, hours later, with nobody present. [SmartTunnel.isCarrying]
        // asks Android instead: is our tunnel service alive, and is this
        // app's traffic inside a VPN right now.
        if (SmartTunnel.isCarrying(appContext)) return null

        LogUtil.w(AppConfig.TAG, "ads: dropped a buffered SDK ping — no smart session to send it through")
        return NoOpWorker(appContext, workerParameters)
    }

    /**
     * Answers the job as done without touching the network.
     *
     * Success rather than retry on purpose: a retry would come back on the
     * same schedule to the same missing tunnel, so it would only turn one
     * dropped ping into an indefinite series of wake-ups that each drop it
     * again.
     */
    private class NoOpWorker(context: Context, params: WorkerParameters) :
        Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }
}
