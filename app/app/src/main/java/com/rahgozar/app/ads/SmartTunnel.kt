package com.rahgozar.app.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.rahgozar.app.AppConfig
import com.rahgozar.app.core.CoreRoutePin
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Reachability
import com.rahgozar.app.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tunnel the ad flow runs, as a session.
 *
 * The panel decides *whether* this device needs it ([PanelStore.smartRequired])
 * and *what* it may connect to (the hidden candidate profiles the sync stores
 * under `panel-smart-<i>`). This object only owns the lifecycle: up on the
 * splash before the first fill is requested, down when the user's first real
 * action — connect, or opening the server list — has had its ad. Which
 * candidate to run is the caller's choice; [SmartSelector] exists to make it
 * an informed one.
 *
 * A session, not just a running service, because the app has to tell this
 * tunnel apart from one the user asked for. While the session is active the
 * home screen shows *disconnected*: the panel's tunnel carrying ad traffic is
 * not the user's VPN, and a dial that claimed otherwise would be lying about
 * the thing the app exists to do. The flag lives in multi-process storage
 * ([PanelStore.smartSessionActive]) because the tunnel outlives the UI
 * process, and it is set and cleared strictly together with the run-guid
 * override so the two cannot disagree about whose tunnel is up.
 */
object SmartTunnel {

    /**
     * The candidate guids whose profiles actually exist, in the panel's
     * order. Usually identical to the stored list; the existence check is
     * armour against a half-written store.
     */
    fun candidates(): List<String> = runCatching {
        Gson().fromJson(PanelStore.smartGuidsJson, Array<String>::class.java)
            ?.toList().orEmpty()
    }.getOrDefault(emptyList())
        .filter { MmkvManager.decodeServerConfig(it) != null }

    /** Whether the last sync left anything to run at all. */
    val available: Boolean
        get() = candidates().isNotEmpty()

    /**
     * True while the ad flow's tunnel is *supposed* to be the one running.
     *
     * An intention, not an observation — see [isCarrying] for the difference,
     * which matters enough that anything about to send ad traffic must use
     * that one instead.
     */
    val isActive: Boolean
        get() = PanelStore.smartSessionActive

    /**
     * Whether the tunnel that is up right now is the ad flow's rather than the
     * user's.
     *
     * The question every surface outside the app has to ask before it says
     * "connected". The home screen already knows the answer — it masks the
     * tunnel's own events while a session is up, because the panel's tunnel
     * carrying ad traffic is not the user's VPN. The quick-settings tile and
     * the widget did not, so for the few seconds of an ad they contradicted it:
     * the tile went active and labelled itself with the *smart server's* name,
     * a server the user never chose and cannot see in their list.
     *
     * Both halves are needed. The flag alone is a stored intention that only
     * this app's own teardown clears, so a session whose tunnel is long gone
     * still reads true — and a surface that refused to act on that would be
     * dead until something reconciled it. Asking Android as well means a
     * stale flag costs nothing: no tunnel, no session.
     */
    fun ownsTheRunningTunnel(context: Context): Boolean =
        PanelStore.smartSessionActive && TunnelState.isRunning(context)

    /**
     * Whether this app's traffic is, at this instant, inside the ad flow's
     * tunnel.
     *
     * [isActive] cannot answer that. It reads a stored flag that only this
     * app's own teardown ever clears, so every other way a tunnel can end —
     * the core exiting, the user switching the VPN off in Android's settings,
     * another VPN app taking the single slot, the tunnel process being
     * killed — leaves it reading true over nothing. An ad requested or shown
     * in that state goes out on the user's real address while the code
     * believes it is protected, which is the one outcome this whole
     * arrangement exists to prevent.
     *
     * So the question is asked of Android, twice over: our tunnel service is
     * alive, and the network this app's own packets are using is a VPN. The
     * second is the load-bearing half — it is answered per-uid, so it is true
     * only if *this* app is genuinely inside a tun, which also catches a
     * tunnel that came up carrying nothing.
     */
    fun isCarrying(context: Context): Boolean =
        PanelStore.smartSessionActive &&
            TunnelState.isRunning(context) &&
            trafficIsTunnelled(context)

    /**
     * [isCarrying], with patience for a tunnel that has just come up.
     *
     * Routes and the per-uid network assignment land a moment after the core
     * reports itself started, so an instant check right after a bring-up can
     * be a false no. Only the *first* answer is waited for; once it is true
     * the caller has what it needs.
     */
    suspend fun awaitCarrying(context: Context, timeoutMs: Long = CARRY_WAIT_MS): Boolean {
        if (isCarrying(context)) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!isCarrying(context)) delay(CARRY_POLL_MS)
            true
        } ?: false
    }

    /**
     * Waits until something actually gets through the tunnel, not merely until
     * one exists.
     *
     * [isCarrying] proves this app's packets are being handed to a tun. It says
     * nothing about whether the far end has answered — a core reports itself
     * started the moment it is listening, seconds before its handshake with the
     * server completes. The ad SDK's very first request went out in that gap
     * and hung: our budget for it was 12s, Google's own HTTP budget is 60s, so
     * the request was still on the wire long after the flow had given up, and
     * the user was shown no ad at all while a perfectly good tunnel finished
     * coming up behind them. The second request, sent twelve seconds later,
     * filled in four.
     *
     * So the first thing sent through a fresh tunnel is this: one small request
     * whose only job is to come back. It costs a round trip on a path that
     * works and buys the whole of the ad's own budget being spent on the ad.
     *
     * The target is Google's own connectivity endpoint — deliberately the same
     * infrastructure the SDK is about to talk to, so a "yes" here is evidence
     * about the path that matters rather than about the internet in general.
     * It carries no identity and returns no body.
     *
     * @return true as soon as a request completes; false if none did in time,
     *   which the caller may still choose to proceed on — a tunnel that has not
     *   answered yet is not proof of one that never will.
     */
    suspend fun awaitReachable(timeoutMs: Long = REACH_WAIT_MS): Boolean =
        Reachability.await(timeoutMs)

    /**
     * Says the session is still being used.
     *
     * The tunnel's own process holds a watchdog that ends a session nothing is
     * doing anything with — see [SessionLimit]. It cannot see the flow, so the
     * flow has to tell it: at bring-up, and at every ad shown or tapped. A
     * session that stops being touched is one whose flow is gone.
     */
    fun touch() {
        PanelStore.smartSessionTouchedAt = SystemClock.elapsedRealtime()
    }

    /** Whether the network this app's packets take right now is a VPN. */
    private fun trafficIsTunnelled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching false
        val active = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * Which session is running, counting from this process's first.
     *
     * An ad is only safe to show if it was *fetched* inside the session that
     * is still up — a fill that landed after a teardown travelled on the
     * user's real address, and showing it would send the impression the same
     * way. Ads carry this number so that can be checked. In memory on purpose:
     * the pending ad is in memory too, so both die together with the process.
     */
    @Volatile
    var epoch: Int = 0
        private set

    /**
     * Brings the smart tunnel up on one specific candidate. Quietly — its
     * start is an implementation detail of the ad flow, not a connection the
     * user asked for.
     *
     * @return false when the candidate has no profile; the caller moves on.
     */
    fun start(context: Context, guid: String): Boolean {
        if (MmkvManager.decodeServerConfig(guid) == null) return false
        // A deadline can only belong to a user connection, and there is none
        // running if this is starting. One left behind by a connection that
        // ended badly would be read as "already expired" by whatever comes
        // next — which is how an earlier session once killed this tunnel a
        // moment after it came up.
        SessionLimit.clear()
        epoch++
        PanelStore.smartSessionActive = true
        touch()
        MmkvManager.setRunGuidOverride(guid)
        // The one caller entitled to run the override, because it is the one
        // that just set it. Every other start in the app clears it first —
        // see [LauncherManager.startContextService].
        LauncherManager.startService(context, quiet = true, honourOverride = true)
        LogUtil.i(AppConfig.TAG, "ads: smart tunnel starting ($guid)")
        return true
    }

    /**
     * Gets one working smart tunnel up, out of however many candidates the
     * panel sent. Shared by the splash flow and the connect flow — same
     * failover, wherever the ad is.
     *
     * The intelligence is in the order of operations: measure everything
     * *first* (in parallel, through the test service — see [SmartSelector]),
     * then connect to a candidate that demonstrably answers. Connecting
     * blind and judging by the core's start signal cannot work here, because
     * a dead server still starts a perfectly healthy-looking core.
     *
     * The retry loop exists for the narrow window between those two steps: a
     * candidate that answered the probe can still fail its start (a broken
     * config, a server that died in the meantime). Each failed start retires
     * that candidate and asks the selector again about the rest.
     */
    suspend fun bringUp(context: Context): Boolean {
        // Only a real VpnService tunnel counts.
        //
        // The run mode decides which service starts, and two of them cannot
        // carry this app's traffic at all: proxy-only mode builds no tun —
        // just a loopback SOCKS listener nothing points the ad SDK at — and
        // root mode's iptables deliberately RETURN this app's own uid so the
        // core does not loop. Both still report a successful start, so
        // without this check the flow would believe it had a tunnel and
        // request ads over the user's real address.
        if (!SettingsManager.isVpnMode() || SettingsManager.isRootMode()) {
            LogUtil.w(AppConfig.TAG, "ads: run mode has no tun for this app — no smart session")
            return false
        }

        // A session this *process* opened, whose tunnel is still up, is taken
        // at its word — a relaunch over a live splash tunnel.
        //
        // The epoch is what makes that check honest. The flag lives in
        // multi-process storage and survives everything, so a fresh process
        // can find it set by a run that is long gone, with some tunnel of
        // ours still up but carrying nothing. Trusting it there meant asking
        // for ads through a black hole — seen on the device as the SDK
        // failing to resolve its own hostnames. An epoch of zero means this
        // process opened nothing, whatever the flag says.
        if (isActive && epoch > 0 && TunnelState.isRunning(context)) return true
        if (isActive) {
            LogUtil.w(AppConfig.TAG, "ads: discarding a session this process did not open")
            stopNow(context)
        }

        var remaining = candidates()
        var attempts = 0
        while (remaining.isNotEmpty() && attempts < MAX_BRING_UP_ATTEMPTS) {
            attempts++
            // Measuring picks *between* candidates. It never vetoes them.
            //
            // The probe underneath is a plain, unprotected TCP connect with a
            // one-second budget (RealPingWorkerService), so another VPN app
            // holding the device's default network — or a single lost SYN —
            // is enough for it to report "nothing answered" about a server
            // that connects perfectly a moment later. Seen on the device:
            // the probe failed while the same server was carrying traffic in
            // another client. The core's own start is the verdict that counts,
            // so when the measurement has no opinion the panel's order does.
            //
            // With a single candidate there is nothing to choose between, and
            // the probe would be pure risk, so it is skipped outright.
            val guid = when {
                remaining.size == 1 -> remaining.first()
                else -> SmartSelector.firstWorking(context, remaining) ?: remaining.first()
            }
            // A name this tunnel could not dial is not worth starting.
            //
            // The session puts this app inside the tun, so the core's own
            // uplink has to be excluded from it — and an exclusion is an
            // address. For a CDN-fronted candidate that address comes from
            // resolving the name here, while there is still no tun in the way;
            // the core is then handed the same address instead of the name, so
            // it never repeats the lookup from inside. If even this resolution
            // fails, starting would build a tunnel that swallows its own
            // uplink, so the candidate is retired and the next one gets its
            // turn — the same treatment as one that fails to start.
            if (!pinRoute(guid)) {
                remaining = remaining - guid
                continue
            }

            if (startAndAwait(context, guid)) {
                // The core says it started; the system has not necessarily
                // finished handing it the routes yet. A request fired into
                // that gap comes straight back as "connection closed by
                // peer" — which is exactly how the first connect-slot fill
                // was lost on the device. This is the cost of not losing it.
                delay(SETTLE_MS)
                return true
            }

            LogUtil.w(AppConfig.TAG, "ads: $guid answered its probe but failed to start")
            // Clear the half-open session before trying the next candidate.
            stop(context)
            remaining = remaining - guid
        }
        return false
    }

    /**
     * Resolves a candidate's address and remembers it, off the main thread.
     *
     * @return false when nothing could be resolved, so the caller retires this
     *   candidate. An address-literal candidate always passes.
     */
    private suspend fun pinRoute(guid: String): Boolean = withContext(Dispatchers.IO) {
        val config = MmkvManager.decodeServerConfig(guid) ?: return@withContext false
        val addresses = CoreRoutePin.ensurePinned(config.server)
        if (addresses.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "ads: ${config.server} would not resolve — skipping this smart candidate",
            )
        }
        addresses.isNotEmpty()
    }

    /**
     * Starts the smart tunnel on [guid] and waits for the core's own verdict.
     *
     * The success broadcast is the scenario's signal — the receiver here is
     * the "Broadcast Receiver که مستقیم از هسته پیام می‌گیره". It is
     * registered *before* the start so the answer cannot slip past in the
     * gap.
     */
    private suspend fun startAndAwait(context: Context, guid: String): Boolean = try {
        awaitStart(context, guid)
    } catch (e: CancellationException) {
        // The flow was cancelled mid-start — the splash closed, the app went
        // away. Whatever happens next, it must not inherit a live session:
        // the override would send the user's own connect to the panel's
        // profile, on a tunnel scoped to this app alone. NonCancellable
        // because the scope this runs in is already cancelling.
        withContext(NonCancellable) { stopNow(context) }
        throw e
    }

    private suspend fun awaitStart(context: Context, guid: String): Boolean {
        val verdict = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getIntExtra("key", 0)) {
                    AppConfig.MSG_STATE_START_SUCCESS -> verdict.complete(true)
                    AppConfig.MSG_STATE_START_FAILURE -> verdict.complete(false)
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
            if (!start(context, guid)) return false
            withTimeoutOrNull(START_TIMEOUT_MS) { verdict.await() } ?: false
        } finally {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
        }
    }

    /**
     * Ends the session and stops the tunnel — but not while ad traffic is
     * still moving.
     *
     * This is the other half of the leak the tunnel exists to prevent. The
     * GMA SDK has no way to cancel a request, so a load whose wait timed out
     * is still on the wire, and an ad that was just dismissed still has its
     * impression ping to send. Pulling the tunnel out from under either of
     * them puts exactly the traffic this flow protects onto the user's real
     * address. So the tunnel is held until the SDK goes quiet, and then for a
     * short grace on top — both bounded, because a tunnel that never comes
     * down is its own kind of broken.
     */
    suspend fun stop(context: Context) {
        if (!isActive) return

        // Everything from here is irrevocable and must complete.
        //
        // The callers all run this on an Activity's lifecycleScope, and an
        // Activity is destroyed by things the user does not think of as
        // leaving: a rotation, dark mode, a font-size change. That cancelled
        // the teardown midway and left the tunnel up with no flow to end
        // it — the app's own traffic policy, applied to a phone nobody was
        // looking at. The same reasoning already protects the *start* (see
        // [startAndAwait]); the stop needs it more, because a start that is
        // abandoned leaves nothing behind and a stop that is abandoned leaves
        // everything.
        withContext(NonCancellable) {
            drain()
            stopNow(context)
        }
    }

    /**
     * Holds the tunnel while ad traffic is still moving, then a little longer.
     *
     * Two different things are being waited for and they need different
     * patience. Requests this process made are counted, so the wait can be
     * adaptive: it ends the moment the SDK is quiet, and only a request that
     * is genuinely stuck costs the full budget. A *click* cannot be counted —
     * it leaves this process entirely, for the Play Store or a browser — so
     * all that can be done is to keep the tunnel up for the moments the
     * redirect chain takes to start, and that is a fixed hold.
     *
     * Both are bounded. A tunnel that never comes down carries the user's own
     * browsing through the operator's server, which is a worse failure than
     * the one being prevented.
     */
    private suspend fun drain() {
        val quiet = AdInventory.awaitQuiet(DRAIN_TIMEOUT_MS)
        if (!quiet) {
            LogUtil.w(AppConfig.TAG, "ads: SDK still busy after ${DRAIN_TIMEOUT_MS}ms — holding longer")
            if (!AdInventory.awaitQuiet(DRAIN_EXTENSION_MS)) {
                LogUtil.w(AppConfig.TAG, "ads: giving up on the drain, tearing down anyway")
            }
        }

        val sinceClick = AdInventory.millisSinceLastClick()
        val clickHold = (CLICK_HOLD_MS - sinceClick).coerceIn(0L, CLICK_HOLD_MS)
        if (clickHold > 0) {
            LogUtil.i(AppConfig.TAG, "ads: holding ${clickHold}ms for a tapped ad's redirect")
        }
        delay(maxOf(TEARDOWN_GRACE_MS, clickHold))
    }

    /**
     * Ends the session immediately, without draining.
     *
     * For the paths where there is nothing to drain — a bring-up that failed,
     * or a session being abandoned — and for callers that cannot suspend.
     *
     * The flag and the override are cleared *before* the stop message goes
     * out, so anything reacting to the stop broadcast already sees a world
     * with no smart session in it.
     */
    fun stopNow(context: Context) {
        if (!isActive) return
        clear()
        LauncherManager.stopService(context)
        LogUtil.i(AppConfig.TAG, "ads: smart tunnel stopped")
    }

    /**
     * Forgets the session without touching any service.
     *
     * The last line of defence for the invariant this whole object rests on:
     * the run-guid override must never outlive the ad flow. If it does, the
     * user's own connect starts the panel's smart profile instead of their
     * server, on a tunnel scoped to this app alone — a phone that says
     * "connected" and carries nothing. Called on the user's own connect path,
     * where an override can only ever be leftover.
     */
    fun clearSession() {
        if (!isActive && MmkvManager.getRunGuidOverride() == null) return
        LogUtil.w(AppConfig.TAG, "ads: clearing a smart session that outlived its flow")
        clear()
    }

    /**
     * Brings a stale session back in line with reality.
     *
     * Called when a UI process comes up. A crash or a swipe-away can leave the
     * flag claiming a session whose service is long gone — and the run-guid
     * override with it, which would silently point the user's next connect at
     * the smart server. A session whose tunnel *is* still running (the UI
     * process died under it) is left alone.
     */
    fun reconcile(context: Context) {
        if (isActive && !TunnelState.isRunning(context)) {
            LogUtil.w(AppConfig.TAG, "ads: clearing stale smart session")
            clear()
        } else if (!isActive) {
            // No session, no override — belt and braces.
            MmkvManager.setRunGuidOverride(null)
        }
    }


    private fun clear() {
        PanelStore.smartSessionActive = false
        MmkvManager.setRunGuidOverride(null)
    }

    /**
     * How long the smart core gets from start to its success broadcast.
     * An Xray start is seconds; a core that has not answered in fifteen is
     * not about to, and there is a user waiting behind every caller.
     */
    private const val START_TIMEOUT_MS = 15_000L

    /** Grace between "the core started" and the tunnel actually carrying. */
    private const val SETTLE_MS = 1_200L

    /**
     * How long the tunnel is held for requests that are still on the wire.
     *
     * Generous enough to cover a load whose own timeout has just expired
     * (the panel's default is 8s and the SDK gives up shortly after), and
     * bounded so a stuck request cannot strand the user's connect behind it.
     */
    private const val DRAIN_TIMEOUT_MS = 6_000L

    /**
     * Extra patience when the SDK is *still* busy at the end of the drain.
     *
     * The first budget is sized for the ordinary case. This one exists for
     * the case that budget was never meant to cover — a request the SDK has
     * not answered at all — where the choice is between waiting a while
     * longer and destroying the tun with that request on the wire. Bounded,
     * because the alternative to a leak is not an unbounded tunnel.
     */
    private const val DRAIN_EXTENSION_MS = 6_000L

    /**
     * How long the tunnel outlives a tapped ad.
     *
     * A click leaves this process: the Play Store or a browser opens the
     * click-through URL, and the redirect chain from googleadservices to the
     * advertiser starts moving in an app this one cannot count. Those
     * packages are inside the tun for exactly this reason ([PerAppProxy]), so
     * the only thing left to get right is not closing it under them. Covers
     * the redirect chain rather than the whole visit, which is what a bound
     * means.
     */
    private const val CLICK_HOLD_MS = 8_000L

    /** How long a freshly started tunnel gets to become this app's network. */
    private const val CARRY_WAIT_MS = 4_000L

    /** Poll interval while waiting for that. */
    private const val CARRY_POLL_MS = 150L

    /**
     * How long a fresh tunnel gets to prove it passes traffic.
     *
     * Generous on purpose. Waiting here is not lost time — it is time the ad's
     * own budget no longer has to spend on a tunnel that was not ready, and the
     * splash is showing a progress indicator throughout either way. What it
     * must not become is an indefinite hold on a tunnel that will never work,
     * which is what the ceiling is for.
     */
    private const val REACH_WAIT_MS = 9_000L




    /**
     * Held after the SDK is quiet, for the pings a dismissal fires on its way
     * out — impression and click reporting happen after the callback returns.
     */
    private const val TEARDOWN_GRACE_MS = 1_500L

    /**
     * How many measure-then-start rounds a bring-up tolerates. Each retry
     * only happens on the rare probe-passed-but-start-failed path; three of
     * those in a row means something is wrong that a fourth will not fix.
     */
    private const val MAX_BRING_UP_ATTEMPTS = 3
}
