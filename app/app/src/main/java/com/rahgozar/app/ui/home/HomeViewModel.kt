package com.rahgozar.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rahgozar.app.AngApplication
import com.rahgozar.app.AppConfig
import com.rahgozar.app.ads.AdInventory
import com.rahgozar.app.ads.ExtendSessionFlow
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.enums.EConfigType
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.dto.TestServiceMessage
import com.google.gson.Gson
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.panel.PanelSync
import com.rahgozar.app.ui.main.MainRepository
import com.rahgozar.app.ui.home.TAPE_SAMPLES
import com.rahgozar.app.ui.main.MainServiceEvent
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Reachability
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The home screen's state, assembled from three sources: the service (is the
 * tunnel up, how fast is it moving), MMKV (which server the panel gave us and
 * which one is selected), and a local clock for the session timer.
 *
 * Reuses [MainRepository] rather than opening a second broadcast receiver: the
 * service only sends each message once, and two receivers competing for it is a
 * race nobody needs.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MainRepository(app as AngApplication)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var tickJob: Job? = null

    /** Bumped whenever measured latencies change, so the list recomposes. */
    private val _serversRevision = MutableStateFlow(0)
    val serversRevision: StateFlow<Int> = _serversRevision.asStateFlow()

    /**
     * The servers being measured right now.
     *
     * A set, not a single guid, because the batch runs several at once — the
     * whole point of naming them individually is that the rows still finishing
     * are distinguishable from the rows already answered. The list used to grey
     * out every row for the entire run, which made a fast parallel batch look
     * like one long stall.
     */
    private val _testingGuids = MutableStateFlow<Set<String>>(emptySet())
    val testingGuids: StateFlow<Set<String>> = _testingGuids.asStateFlow()

    /** Waiting for the first real request to come back through a new tunnel. */
    private var verifyJob: Job? = null

    /** The server whose verification is outstanding, or null. */
    private var verifyGuid: String? = null

    /** Bytes this tunnel had already moved when its verification started. */
    private var verifyBaselineBytes: Long = 0

    /** True while a measurement round is allowed to choose a server. */
    private var autoPicking = false

    /**
     * True while [autoPickIfNeeded] is still measuring.
     *
     * Read by the screen only to explain a tap it had to refuse: "no server
     * yet" means something different while a choice is still being made than
     * it does once the round has ended with nothing.
     */
    val isChoosingServer: Boolean get() = autoPicking

    init {
        refreshServer()
        _uiState.value = _uiState.value.copy(
            // A running smart session is not the user's VPN — see the mask in
            // [onServiceEvent]. The same rule has to hold here or the dial
            // opens on "connected" and the mask below can never take it back.
            link = if (CoreServiceManager.isRunning() && !SmartTunnel.isActive) {
                LinkState.ON
            } else {
                LinkState.OFF
            }
        )
        if (_uiState.value.isOn) {
            // Reopened over a live tunnel: this launch is not an entry, and
            // ad pacing applies from the first tap.
            AdInventory.userConnectedThisLaunch = true
            startTicking()
        }

        viewModelScope.launch {
            repository.mainServiceEvent.collect(::onServiceEvent)
        }
    }

    /**
     * True for the events that describe the tunnel's own state.
     *
     * While the ad flow's smart session is active, the tunnel that is up is
     * the *panel's*, carrying ad traffic — not a connection the user made. If
     * these events reached the dial it would show "connected" on a home
     * screen the user has not touched yet, and the throughput tape would
     * animate someone else's traffic. Measurement events still pass: the
     * server list's numbers are about servers, not about this tunnel.
     */
    private fun maskedDuringSmartSession(event: MainServiceEvent): Boolean = when (event) {
        MainServiceEvent.StateRunning,
        MainServiceEvent.StateStartSuccess,
        MainServiceEvent.StateNotRunning,
        MainServiceEvent.StateStopSuccess,
        is MainServiceEvent.StateStartFailure,
        is MainServiceEvent.SpeedUpdate -> true

        else -> false
    }

    private fun onServiceEvent(event: MainServiceEvent) {
        if (SmartTunnel.isActive && maskedDuringSmartSession(event)) return
        when (event) {
            // Rebinding to a tunnel that is already up — the app was reopened.
            // It has been carrying traffic all along, so it needs no gate.
            MainServiceEvent.StateRunning -> setLink(LinkState.ON)

            MainServiceEvent.StateStartSuccess -> onTunnelUp()

            is MainServiceEvent.MeasureDelayResult -> onVerified(event.delayMillis)

            MainServiceEvent.StateNotRunning,
            MainServiceEvent.StateStopSuccess -> setLink(LinkState.OFF)

            is MainServiceEvent.StateStartFailure -> setLink(LinkState.OFF)

            is MainServiceEvent.SpeedUpdate -> _uiState.update {
                it.copy(
                    upBytesPerSec = event.upBytesPerSec,
                    downBytesPerSec = event.downBytesPerSec,
                    sessionUpBytes = event.sessionUpBytes,
                    sessionDownBytes = event.sessionDownBytes,
                    downHistory = (it.downHistory + event.downBytesPerSec).takeLast(TAPE_SAMPLES),
                    upHistory = (it.upHistory + event.upBytesPerSec).takeLast(TAPE_SAMPLES),
                )
            }

            // Deliberately ignored. It carries a translated sentence, and the
            // number used to be scraped out of it with a regex — which happily
            // pulled a digit out of a *failure* message and displayed it as a
            // healthy latency, so a dead server showed "100ms · excellent".
            // MeasureDelayResult carries the real number.
            is MainServiceEvent.MeasureDelaySuccess -> Unit

            is MainServiceEvent.MeasureConfigTesting -> {
                _testingGuids.value = _testingGuids.value + event.guid
                _serversRevision.value = _serversRevision.value + 1
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                onMeasured(event.guid)
                // Cleared as soon as its own result lands, so no row is left
                // showing a spinner once it has a number — independently of the
                // rows still running beside it.
                _testingGuids.value = _testingGuids.value - event.guid
                _serversRevision.value = _serversRevision.value + 1

                // A connection is waiting on this exact server's verdict.
                if (event.guid == verifyGuid) {
                    onVerified(
                        MmkvManager.decodeServerAffiliationInfo(event.guid)?.testDelayMillis ?: -1L
                    )
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                _testingGuids.value = emptySet()
                _uiState.update { it.copy(testing = false) }
                finishAutoPick()
                refreshServer()
                // The list screen reads MMKV on each composition, so bumping
                // this is what tells it the numbers changed.
                _serversRevision.value = _serversRevision.value + 1
            }

            // Each server that finishes bumps the revision, so results appear
            // as they arrive instead of all at the end.
            is MainServiceEvent.MeasureConfigNotify -> {
                _serversRevision.value = _serversRevision.value + 1
            }

        }
    }

    /**
     * The tunnel is up. Whether it is any *use* is a separate question.
     *
     * A core that started and a server that carries traffic are not the same
     * thing: an Xray server can be dead, filtered, or out of quota and the
     * tunnel still comes up, so the app would sit there showing "connected"
     * while nothing worked. So the dial stays on "connecting" until one real
     * request has gone through and come back.
     *
     * OpenVPN needs no such gate. Its CONNECTED already means a completed TLS
     * handshake, an accepted password and a pushed configuration — the server
     * cannot produce that and be dead.
     */
    private fun onTunnelUp() {
        val guid = MmkvManager.getSelectServer()
        if (selectedIsOpenVpn() || guid.isNullOrEmpty()) {
            setLink(LinkState.ON)
            // Same reason as the verified path below: whether the extend
            // offer is real was decided while this tun was built.
            refreshServer()
            return
        }

        setLink(LinkState.CONNECTING)
        verifyGuid = guid
        // Taken now rather than assumed to be zero: the counters belong to the
        // service and the UI's copy of them is whatever the last session left
        // behind until the first update of this one arrives.
        verifyBaselineBytes = _uiState.value.let { it.sessionDownBytes + it.sessionUpBytes }

        // The test service, not the running core's own delay call.
        //
        // Those two do not agree, and the difference is the whole point. The
        // core's call goes out through the live tunnel, where the routing rules
        // can send the probe straight out instead of through the proxy — so a
        // dead server can answer in 100ms and look excellent. The test service
        // builds a standalone outbound to the server itself, which is the same
        // measurement the server list shows, and it is the one that correctly
        // reports this server as not answering.
        //
        // Using it here also means the number after connecting matches the
        // number in the list, instead of contradicting it.
        //
        // It stays the right instrument even while this app is *inside* the
        // tunnel for a timed session. A detour was built here on the belief
        // that the test's own outbound would then be routed through the tunnel
        // under test — it is not: that outbound dials the server's address, and
        // the service excludes exactly that address from the tun for this very
        // reason. The detour asked the question from inside the tunnel instead,
        // where a resolver that has not come up yet cannot answer it, and
        // healthy servers were failed over a name that could not be looked up.
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            // Asked a moment late, on purpose.
            //
            // The measurement runs a second core in a third process, and it
            // starts at the exact instant the tun appears — which is when
            // Android moves the device's default interface onto it. That core
            // sees the move, treats its route as stale and cancels whatever it
            // had in flight ("network changed" in its own log), so the verdict
            // describes our own tunnel coming up rather than the server. On the
            // device it read as `probe failed: Connection reset` half a second
            // in, and as an eight-second timeout on the next attempt — two
            // symptoms, one cause, and a working server disconnected both
            // times.
            //
            // Waiting costs the user nothing they can see: the dial is already
            // showing "connecting", and the deadline below starts after this,
            // so the server still gets its full window to answer.
            delay(VERIFY_SETTLE_MS)
            // A second connection may have replaced this one while we waited.
            if (verifyGuid != guid) return@launch

            // Inside the user's own tunnel, ask directly.
            //
            // The measurement below runs a second sing-box core in a third
            // process, and that process's uid is inside this tun — so the core
            // auto-detects an interface, binds its sockets to the *physical*
            // one, and has them routed by uid into the tun instead. Its own log
            // shows the result: the server's hostname never resolves, the
            // request hangs for its full budget, and a working connection is
            // reported dead. Restarting the core changes nothing, because the
            // binding is decided the same way each time.
            //
            // A plain request from this process has no interface opinion at
            // all — it is routed by uid like everything else the app sends, so
            // it measures the tunnel rather than fighting it. The round trip it
            // returns is a real number and goes on the dial like any other.
            if (SessionLimit.ridesUserTunnel) {
                val rtt = Reachability.measure(verifyTimeoutFor(guid))
                if (verifyGuid != guid) return@launch
                if (rtt >= 0) onVerified(rtt) else failVerification()
                return@launch
            }

            MmkvManager.clearAllTestDelayResults(listOf(guid))
            repository.sendMsg2TestService(
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    serverGuids = listOf(guid),
                )
            )

            delay(verifyTimeoutFor(guid))
            // No answer at all is the same news as a bad answer: nothing is
            // getting through.
            failVerification()
        }
    }

    /**
     * How long to wait for the verdict before calling it a failure.
     *
     * sing-box gets longer because its test is a heavier thing: the number
     * comes from a whole core started in another process, where Xray's comes
     * from a native call in a process that is already warm. Cutting sing-box
     * off at Xray's deadline would disconnect servers that were about to
     * answer — and a genuinely dead server still fails long before either
     * deadline, so the extra patience costs nothing where it matters.
     */
    private fun verifyTimeoutFor(guid: String): Long =
        if (MmkvManager.decodeServerConfig(guid)?.configType == EConfigType.SINGBOX) {
            SINGBOX_VERIFY_TIMEOUT_MS
        } else {
            VERIFY_TIMEOUT_MS
        }

    private fun onVerified(delayMillis: Long) {
        // Only interesting while a connection is waiting on it. Otherwise this
        // is just the user having pressed "test".
        if (verifyGuid == null || _uiState.value.link != LinkState.CONNECTING) {
            _uiState.update { it.copy(pingMs = delayMillis, testing = false) }
            return
        }

        verifyJob?.cancel()
        verifyJob = null
        verifyGuid = null

        if (delayMillis >= 0) {
            _uiState.update { it.copy(pingMs = delayMillis, notice = null) }
            setLink(LinkState.ON)
            // The tun builder has decided by now whether this app is being
            // carried inside — which is what the extend offer hangs on — so
            // the card is re-read here rather than left with whatever was
            // computed before the connection existed.
            refreshServer()
        } else {
            failVerification()
        }
    }

    /**
     * A verdict of "not responding" is only believed while nothing is moving.
     *
     * The probe and the tunnel answer different questions, and when they
     * disagree the tunnel wins: bytes arriving through it are the user's own
     * traffic working, which no measurement can overrule. The probe can be
     * wrong for reasons that have nothing to do with the server — it runs in a
     * process that cannot protect its own sockets, so anything that puts this
     * app inside the tun puts the probe there too.
     *
     * [excludeServerRoute][com.rahgozar.app.service.SingBoxConfig] is the real
     * fix for that; this is the floor underneath it, for the case it cannot
     * cover — a server named by a domain that would not resolve, so there was
     * no address to exclude.
     *
     * The threshold is what separates "carrying" from "connected and idle": a
     * failed handshake and a few keepalives move single-digit kilobytes, real
     * use moves this in a moment.
     */
    private fun trafficIsMoving(): Boolean {
        val state = _uiState.value
        val moved = state.sessionDownBytes + state.sessionUpBytes - verifyBaselineBytes
        return moved >= VERIFY_TRAFFIC_FLOOR_BYTES
    }

    private fun failVerification() {
        if (trafficIsMoving()) {
            LogUtil.i(
                AppConfig.TAG,
                "verify: no answer from the probe, but the tunnel is carrying — staying connected",
            )
            verifyJob?.cancel()
            verifyJob = null
            verifyGuid = null
            _uiState.update { it.copy(notice = null) }
            setLink(LinkState.ON)
            refreshServer()
            return
        }

        verifyJob?.cancel()
        verifyJob = null
        verifyGuid = null
        // The connection this deadline belonged to is being torn down. A
        // deadline that outlives its connection is a live grenade: the next
        // tunnel to come up — including the ad flow's, which has nothing to do
        // with the user's session — reads it as already expired and stops
        // itself on the spot.
        SessionLimit.clear()
        LauncherManager.stopService(getApplication())
        _uiState.update { it.copy(notice = HomeNotice.SERVER_NOT_RESPONDING, pingMs = -1L) }
        setLink(LinkState.OFF)
    }

    private fun selectedIsOpenVpn(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        return MmkvManager.decodeServerConfig(guid)?.configType == EConfigType.OPENVPN
    }

    /**
     * @param syncAfter false when the caller is about to put another tunnel up
     *   in this app's name — the disconnect slot's ad. The sync below asks the
     *   panel which country this device is in, and it answers from the address
     *   it sees; asked with the ad flow's tunnel up, or mid-request while it
     *   comes up, it would answer for the smart server's exit instead. The
     *   caller runs it once its own tunnel is gone.
     */
    private fun setLink(next: LinkState, syncAfter: Boolean = true) {
        val was = _uiState.value.link
        if (was == next) return
        // The end of the entry sequence, as far as ad pacing is concerned.
        // Smart sessions never reach here — their events are masked — so ON
        // always means the user's own tunnel.
        if (next == LinkState.ON) AdInventory.userConnectedThisLaunch = true
        _uiState.update {
            it.copy(
                link = next,
                // A new session starts at zero on both counters, or the tape
                // opens showing the previous session's last burst.
                elapsed = if (next == LinkState.ON) 0 else it.elapsed,
                upBytesPerSec = 0,
                downBytesPerSec = 0,
                sessionUpBytes = if (next == LinkState.ON) 0 else it.sessionUpBytes,
                sessionDownBytes = if (next == LinkState.ON) 0 else it.sessionDownBytes,
                downHistory = if (next == LinkState.ON) emptyList() else it.downHistory,
                upHistory = if (next == LinkState.ON) emptyList() else it.upHistory,
            )
        }
        if (next == LinkState.ON) {
            startTicking()
        } else {
            stopTicking()
            if (syncAfter) syncWhenTheTunnelIsDown()
        }
    }

    /**
     * Re-reads the panel now that no tunnel is in the way.
     *
     * The tunnel being down is the only moment the panel can be asked an honest
     * question: through one it would answer for the exit country, not the
     * user's. A launch that skipped the splash never syncs at all, so without
     * this the ad flow can run for days on a verdict that has since changed —
     * see [PanelSync.refreshIfDue], which does nothing unless one is due.
     *
     * Public because the disconnect slot's ad defers it: that flow puts its own
     * tunnel up straight after the user's comes down, and calls this back once
     * it has taken it away again.
     */
    fun syncWhenTheTunnelIsDown() {
        viewModelScope.launch {
            // A moment first, so a user who taps connect straight back is not
            // raced: [PanelSync.refreshIfDue] refuses while a tunnel is
            // running, and by then one will be.
            delay(SYNC_SETTLE_MS)
            runCatching { PanelSync.refreshIfDue(getApplication()) }
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update {
                    it.copy(elapsed = it.elapsed + 1)
                }
                refreshRemaining()
            }
        }
    }

    /**
     * Re-reads the session deadline the tunnel's process is counting down.
     *
     * Read rather than tracked, because the number can move underneath this
     * screen: an extension is granted in one place and enforced in another.
     * The warning fires once per minute-mark rather than every second, and
     * never overwrites a notice the user has not had a chance to read.
     */
    private fun refreshRemaining() {
        val left = SessionLimit.remainingMillis()?.let { it / 1000 }
        _uiState.update { state ->
            val warn = left != null && left in 1..WARN_AT_SECONDS &&
                state.notice == null
            state.copy(
                remainingSeconds = left,
                notice = if (warn) HomeNotice.SESSION_ENDING else state.notice,
            )
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        _uiState.update {
            it.copy(
                elapsed = 0,
                upBytesPerSec = 0,
                downBytesPerSec = 0,
                downHistory = emptyList(),
                upHistory = emptyList(),
            )
        }
    }

    /** guid → country, as the last sync recorded it. */
    private fun countries(): Map<String, String> = runCatching {
        @Suppress("UNCHECKED_CAST")
        Gson().fromJson(PanelStore.serverCountries, Map::class.java) as? Map<String, String>
    }.getOrNull().orEmpty()

    /** Re-reads the selected server. Called after a sync or a selection change. */
    fun refreshServer() {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val ping = guid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis } ?: 0L
        _uiState.update {
            it.copy(
                hasServer = profile != null,
                serverName = profile?.remarks.orEmpty(),
                // Host and port, which is what the design's second line shows.
                serverAddress = profile?.let { p -> "${p.server.orEmpty()}:${p.serverPort.orEmpty()}" }
                    .orEmpty()
                    .takeIf { s -> s != ":" }
                    .orEmpty(),
                serverCountry = guid?.let { g -> countries()[g] }.orEmpty(),
                serverProtocol = profile?.configType?.name.orEmpty(),
                // Kept raw: negative means the test ran and failed, which is a
                // different thing from zero, which means it never ran.
                pingMs = ping,
                // Re-read on every refresh rather than cached: the panel can
                // turn the offer on or off between one launch and the next.
                extendMinutes = if (ExtendSessionFlow.offered()) ExtendSessionFlow.minutes() else 0,
            )
        }
        refreshRemaining()
    }

    /**
     * Every server the panel sent, as list rows.
     *
     * Read on demand rather than held in state: the list changes only when a
     * sync writes it, and keeping a second copy in the ViewModel would be one
     * more thing that can disagree with MMKV.
     */
    fun serverRows(): List<com.rahgozar.app.ui.servers.ServerRow> {
        val selected = MmkvManager.getSelectServer()
        val byCountry = countries()
        return MmkvManager.decodeAllServerList().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            com.rahgozar.app.ui.servers.ServerRow(
                guid = guid,
                name = profile.remarks,
                address = "${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}"
                    .takeIf { it != ":" }.orEmpty(),
                country = byCountry[guid].orEmpty(),
                protocol = profile.configType.name,
                pingMs = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L,
                selected = guid == selected,
                testing = guid in _testingGuids.value,
            )
        }
    }

    /** The links the panel supplied, for the drawer. */
    fun panelLinks(): List<Pair<String, String>> =
        com.rahgozar.app.panel.PanelSettings.parse(PanelStore.settingsJson).links()

    /**
     * Chooses a server for a user who has not chosen one.
     *
     * Measures everything and takes the lowest real delay — never a server that
     * failed, because a failed server is exactly the wrong thing to hand
     * somebody on their first launch.
     *
     * With a long list it takes the **first server that answers** instead of
     * waiting for the slowest to time out. Sixty servers at a few seconds each
     * is a minute of staring at a screen, and the difference between the
     * quickest responder and the theoretical best is not worth that.
     *
     * Does nothing when a server is already selected: the user's own choice
     * outranks any measurement.
     */
    fun autoPickIfNeeded() {
        if (autoPicking) return
        // Not while this app is inside a tunnel. Every probe would be
        // measuring the tunnel rather than the server it names — the smart
        // server during an ad session, or the user's own server once timed
        // sessions put us inside it — and the choice would be made on those
        // numbers. The caller re-runs this when the session ends.
        if (SmartTunnel.isActive || insideATunnel()) return
        val selected = MmkvManager.getSelectServer()
        if (!selected.isNullOrEmpty() && MmkvManager.decodeServerConfig(selected) != null) return

        val guids = MmkvManager.decodeAllServerList().toList()
        if (guids.isEmpty()) return

        autoPicking = true
        _uiState.update { it.copy(testing = true) }
        MmkvManager.clearAllTestDelayResults(guids)
        repository.sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = guids)
        )
    }

    /**
     * Whether a measurement right now would be measuring a tunnel this app is
     * sitting inside, rather than the server it names.
     *
     * Only true while timed sessions carry us through the user's own tunnel;
     * with the feature off, this app is outside every tunnel and the numbers
     * mean what they always did.
     */
    private fun insideATunnel(): Boolean = SessionLimit.ridesUserTunnel && _uiState.value.isOn

    /** One server reported in. Takes it if it answered and the list is long. */
    private fun onMeasured(guid: String) {
        if (!autoPicking || guid.isEmpty()) return
        // Only servers from the visible list may win. The ad flow measures
        // its hidden smart candidates through the same test service, and a
        // round of each running at once must not end with the user's
        // selection pointing at a profile no screen can show.
        if (guid !in MmkvManager.decodeAllServerList()) return
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        if (delay <= 0L) return

        val total = MmkvManager.decodeAllServerList().size
        if (total > FAST_PICK_ABOVE) {
            select(guid)
            autoPicking = false
            cancelTesting()
        }
    }

    /** The round ended. Takes the lowest delay among the servers that answered. */
    private fun finishAutoPick() {
        if (!autoPicking) return
        autoPicking = false

        val best = MmkvManager.decodeAllServerList()
            .mapNotNull { guid ->
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                if (delay > 0L) guid to delay else null
            }
            .minByOrNull { it.second }
            ?.first

        // Nothing answered. Leaving it unselected is the honest outcome — the
        // home screen already tells the user to pick one, and choosing a server
        // that just failed its test would be worse than choosing none.
        if (best != null) select(best)
    }

    private fun select(guid: String) {
        MmkvManager.setSelectServer(guid)
        refreshServer()
        _serversRevision.value = _serversRevision.value + 1
    }

    /** Marks the connecting beat; the Activity does the VPN permission dance. */
    fun onConnectRequested() {
        // A fresh attempt clears the verdict on the last one.
        _uiState.update { it.copy(notice = null) }
        setLink(LinkState.CONNECTING)
    }

    /**
     * The user asked for more time.
     *
     * The whole exchange — load, show, reward, grant — belongs to
     * [ExtendSessionFlow]; what is here is only what the screen has to say
     * about each way it can end.
     */
    fun extendSession(activity: android.app.Activity) {
        if (_uiState.value.extending) return
        _uiState.update { it.copy(extending = true, notice = null) }
        viewModelScope.launch {
            val result = ExtendSessionFlow.run(activity)
            _uiState.update {
                it.copy(
                    extending = false,
                    notice = when (result) {
                        ExtendSessionFlow.Result.EXTENDED -> null
                        ExtendSessionFlow.Result.CAPPED -> HomeNotice.EXTENSIONS_USED_UP
                        ExtendSessionFlow.Result.NOT_EARNED -> HomeNotice.REWARD_NOT_EARNED
                        // Nothing filled. Worth saying: the button spent
                        // fifteen seconds claiming it was fetching an ad, and
                        // going quiet after that reads as a broken button
                        // rather than as an ad that never arrived.
                        ExtendSessionFlow.Result.UNAVAILABLE -> HomeNotice.AD_NOT_AVAILABLE
                    },
                )
            }
            refreshRemaining()
        }
    }

    /**
     * The user asked for the tunnel to come down.
     *
     * The screen goes to off here rather than waiting for the service to say
     * it stopped: the request has been made, and a dial that keeps claiming
     * "connected" until a broadcast arrives invites a second tap on something
     * that is already on its way down. Any verification still outstanding is
     * cancelled, or its verdict would land on a tunnel that no longer exists.
     *
     * @param deferSync true when the disconnect slot's ad is about to run, so
     *   the panel is asked after that flow's tunnel is gone rather than while
     *   it is coming up. See [syncWhenTheTunnelIsDown].
     */
    fun onDisconnectRequested(deferSync: Boolean = false) {
        verifyJob?.cancel()
        verifyJob = null
        verifyGuid = null
        // The limit belonged to the connection being ended. Leaving it behind
        // would have the next one start with someone else's clock.
        SessionLimit.clear()
        _uiState.update { it.copy(notice = null, remainingSeconds = null) }
        setLink(LinkState.OFF, syncAfter = !deferSync)
    }

    /**
     * Measures the selected server.
     *
     * One mechanism, whether the tunnel is up or not. It used to be two: with
     * the tunnel up it asked the running core to time a round trip through it,
     * which sounds more truthful and is not. That probe follows the routing
     * rules, so it can be sent straight out instead of through the proxy, and a
     * server that answers nothing at all comes back looking excellent — while
     * the list, measured the other way, showed the same server as dead.
     *
     * Two numbers that disagree teach the user to trust neither, so both now
     * come from the test service, which measures the server itself.
     */
    fun testCurrent() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) return
        // Same reason as [autoPickIfNeeded]: a probe sent while this app is
        // inside a tunnel measures that tunnel, not this server.
        if (SmartTunnel.isActive || insideATunnel()) return
        _uiState.update { it.copy(testing = true) }

        MmkvManager.clearAllTestDelayResults(listOf(guid))
        repository.sendMsg2TestService(
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                serverGuids = listOf(guid),
            )
        )
    }

    /** Measures every server the panel sent. Lives on the server list screen. */
    fun testAll() {
        val guids = MmkvManager.decodeAllServerList().toList()
        if (guids.isEmpty()) return
        if (SmartTunnel.isActive || insideATunnel()) return
        _uiState.update { it.copy(testing = true) }
        MmkvManager.clearAllTestDelayResults(guids)
        repository.sendMsg2TestService(
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                serverGuids = guids,
            )
        )
    }

    fun cancelTesting() {
        _uiState.update { it.copy(testing = false) }
        repository.sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    override fun onCleared() {
        stopTicking()
        repository.close()
        super.onCleared()
    }

    private inline fun MutableStateFlow<HomeUiState>.update(block: (HomeUiState) -> HomeUiState) {
        value = block(value)
    }

    companion object {
        /**
         * Above this many servers, the first responder wins.
         *
         * Below it, waiting for the full round costs a few seconds and buys the
         * genuinely fastest server.
         */
        const val FAST_PICK_ABOVE = 10

        /**
         * How long a new tunnel gets to carry one request before it is judged.
         *
         * Long enough for a slow but working path, short enough that a dead
         * server does not leave the dial spinning while the user waits to find
         * out something the app already suspects.
         */
        const val VERIFY_TIMEOUT_MS = 12_000L

        /** Room for a core start in another process, plus one queued measurement. */
        const val SINGBOX_VERIFY_TIMEOUT_MS = 22_000L

        /**
         * How long the tun is left alone before anything measures through it.
         *
         * The tunnel's own arrival rearranges the device's default interface,
         * and a measurement started inside that window reports the transition
         * rather than the server.
         */
        const val VERIFY_SETTLE_MS = 2_500L

        /**
         * Traffic that overrules a failed probe.
         *
         * Above what a refused handshake and a few keepalives can account for,
         * and far below anything a working tunnel takes more than a moment to
         * carry — so it can only be reached by the connection actually being
         * used.
         */
        const val VERIFY_TRAFFIC_FLOOR_BYTES = 64L * 1024L

        /**
         * How long before the end the user is warned.
         *
         * A minute is enough to finish a message and decide whether to watch
         * an ad, and short enough that the warning still means "now".
         */
        const val WARN_AT_SECONDS = 60L

        /**
         * How long a disconnect must hold before the panel is re-asked.
         *
         * Long enough that a user reconnecting immediately never sees a sync
         * start, short enough that one who is done for now still gets a fresh
         * verdict before their next ad.
         */
        const val SYNC_SETTLE_MS = 2_000L
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
    }
}
