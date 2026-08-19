package com.rahgozar.app.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.rahgozar.app.AngApplication
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.R
import com.rahgozar.app.ads.AdInventory
import com.rahgozar.app.ads.ConnectAdFlow
import com.rahgozar.app.ads.DisconnectAdFlow
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.enums.PermissionType
import com.rahgozar.app.extension.toast
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsChangeManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.ui.AboutActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahgozar.app.ui.base.HelperBaseComponentActivity
import com.rahgozar.app.ui.brand.AppPreferences
import com.rahgozar.app.ui.brand.BrandTheme
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.rememberAppearance
import com.rahgozar.app.ui.home.HomeScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rahgozar.app.ui.home.AppDrawer
import com.rahgozar.app.ui.home.ConfirmDisconnectDialog
import com.rahgozar.app.ui.home.ConnectLoadingDialog
import com.rahgozar.app.ui.connect.ConnectStage
import com.rahgozar.app.ui.connect.ConnectingScreen
import com.rahgozar.app.ui.connect.DisconnectedScreen
import com.rahgozar.app.ui.connect.SessionSummary
import com.rahgozar.app.ui.home.DrawerItem
import com.rahgozar.app.ui.home.HomeViewModel
import com.rahgozar.app.ui.servers.ServerListScreen
import com.rahgozar.app.ui.perappproxy.PerAppProxyActivity
import com.rahgozar.app.ui.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only screen that starts anything.
 *
 * There is deliberately no path from here to a configuration: no import, no
 * editor, no share, no export, no QR. The server list arrives signed from the
 * panel and this Activity can select one and start it, nothing more. See
 * docs/SECURITY.md for why each removed route mattered.
 */
class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(application)
    }

    /** Which overlay is open, readable from both Compose and onKeyDown. */
    private val showDrawerState = mutableStateOf(false)
    private val showServersState = mutableStateOf(false)
    private val confirmDisconnect = mutableStateOf(false)

    /**
     * True for the whole span of a connect: the ad phase and the real
     * connection both. It puts [ConnectLoadingDialog] up and makes the dial
     * ignore taps — a second tap mid-scenario would read the smart tunnel as
     * "running" and turn the user's connect into a disconnect of a tunnel
     * they never knowingly had.
     */
    private val connecting = mutableStateOf(false)

    /**
     * The same hold, for a tap that is not a connection.
     *
     * The server-list tap also shows an ad and then closes the ad tunnel, and
     * it needs the screen held for exactly the reason [connecting] does — a
     * dial left live during that teardown lets a second tap start a scenario
     * on a session the first one is already destroying. Kept separate only so
     * the dialog does not claim to be connecting when it is not.
     */
    private val busy = mutableStateOf(false)

    /**
     * Which of the two destination screens is up, or null for the home screen.
     *
     * They are where the connect and disconnect taps *land*, and having
     * somewhere to land is the point: a full-screen ad has to sit between two
     * screens, and until these existed both taps showed one and then handed the
     * user back the screen they had tapped from. See [ConnectingScreen] for the
     * whole argument, and [beginConnect] for the ordering that makes it true.
     */
    private val connectStage = mutableStateOf<ConnectStage?>(null)
    private val disconnectSummary = mutableStateOf<SessionSummary?>(null)

    /**
     * Whether the wait currently on screen has an ad in it.
     *
     * The two waiting surfaces say a line about ads paying for the service,
     * and it is only worth saying where it is true. Recorded when the tap
     * starts rather than asked for later: by the time the screen is up, the
     * placement's own state has moved on — the slot has been consumed, the
     * session ended — and the answer would be no for a journey that showed one.
     */
    private val adInThisWait = mutableStateOf(false)

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService && mainViewModel.uiState.value.isRunning) restartV2Ray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        // A crash can leave the smart session claiming a tunnel that is not
        // there — and, worse, leave the run override pointing the connect
        // button at the smart server. Reconciled before the user can tap.
        SmartTunnel.reconcile(this)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onResume() {
        super.onResume()
        // The splash may have replaced the whole server list while this screen
        // was not looking.
        homeViewModel.refreshServer()
        // And if that left nobody selected — first launch, or the selected
        // server was retired — measure and choose one that actually answers.
        homeViewModel.autoPickIfNeeded()
    }

    @Composable
    override fun ScreenContent() {
        val appearance = rememberAppearance()
        val state by homeViewModel.uiState.collectAsStateWithLifecycle()
        val revision by homeViewModel.serversRevision.collectAsStateWithLifecycle()
        val testingGuids by homeViewModel.testingGuids.collectAsStateWithLifecycle()
        // Hoisted onto the Activity so onKeyDown can see them: the hardware
        // back key reaches the Activity first, and it used to background the
        // whole app while an inner screen was open.
        var showDrawer by showDrawerState
        var showServers by showServersState

        BrandTheme(
            themeMode = appearance.themeMode.value,
        ) {
            val dark = LocalPalette.current.isDark
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            when {
                // Both sit above everything: they are the screen the tap is
                // on its way to, and the server list underneath is where it
                // came from.
                connectStage.value != null -> ConnectingScreen(
                    stage = connectStage.value!!,
                    serverName = state.serverName,
                    serverCountry = state.serverCountry,
                    serverProtocol = state.serverProtocol,
                    afterAnAd = adInThisWait.value,
                    onDismiss = { connectStage.value = null },
                )

                disconnectSummary.value != null -> DisconnectedScreen(
                    summary = disconnectSummary.value!!,
                    onDone = { disconnectSummary.value = null },
                )

                showServers -> ServerListScreen(
                    // Recomposes as each measurement lands rather than only at
                    // the end, so a long test shows progress.
                    rows = remember(revision, state.testing, testingGuids) { homeViewModel.serverRows() },
                    testing = state.testing,
                    onSelect = { row ->
                        setSelectServer(row.guid)
                        homeViewModel.refreshServer()
                        showServers = false
                    },
                    onTestAll = homeViewModel::testAll,
                    onBack = { showServers = false },
                    onToggleTheme = { appearance.toggleTheme(dark) },
                )

                else -> HomeScreen(
                    state = state,
                    // Connecting leaves this screen — the ad phase behind a
                    // dialog, everything after it on [ConnectingScreen];
                    // disconnecting asks first.
                    onToggle = {
                        when {
                            // Mid-scenario taps do nothing; see [connecting]
                            // and [busy]. Whatever is covering the screen at
                            // the time already swallows the tap, but a dial
                            // that is inert in its own right does not depend
                            // on something being on top of it.
                            connecting.value || busy.value -> Unit
                            state.isOn -> confirmDisconnect.value = true
                            else -> beginConnect()
                        }
                    },
                    onExtend = { homeViewModel.extendSession(this@MainActivity) },
                    onSelectServer = {
                        if (splashAdSessionOpen()) {
                            adInThisWait.value = AdInventory.hasPending()
                            showPendingAdThen { showServersState.value = true }
                        } else {
                            showServers = true
                        }
                    },
                    onTest = homeViewModel::testCurrent,
                    onMenu = { showDrawer = true },
                    onToggleTheme = { appearance.toggleTheme(dark) },
                )
            }

            // Only until the ad phase is over. From the moment the tap has a
            // screen of its own, that screen is the wait — a dialog behind it
            // would be a second answer to the same question.
            val landed = connectStage.value != null || disconnectSummary.value != null
            if ((connecting.value || busy.value) && !landed) {
                ConnectLoadingDialog(
                    connecting = connecting.value,
                    adNote = adInThisWait.value,
                )
            }

            if (confirmDisconnect.value) {
                ConfirmDisconnectDialog(
                    onConfirm = {
                        confirmDisconnect.value = false
                        // Stops, rather than asking the state what to do.
                        //
                        // This used to call handleFabAction, which re-derives
                        // the action from `isOn` — so if that slipped to false
                        // between the dialog opening and the button being
                        // pressed, "Disconnect" quietly became "connect", the
                        // running service answered "already running, ignoring
                        // start", and the tunnel could not be stopped at all.
                        // A dialog that says Disconnect has only one job.
                        //
                        // It still has only one job: the ad flow below runs
                        // *after* the stop and cannot prevent it.
                        disconnectThenShowAd()
                    },
                    onDismiss = { confirmDisconnect.value = false },
                )
            }

            if (showDrawer) {
                AppDrawer(
                    items = drawerItems(homeViewModel.panelLinks()),
                    onSelect = { item ->
                        showDrawer = false
                        if (item.url.isNotEmpty()) openUrl(item.url) else navigateTo(item.id)
                    },
                    onDismiss = { showDrawer = false },
                )
            }
        }
    }

    private fun navigateTo(destination: String) {
        val intent = when (destination) {
            "per_app_proxy" -> Intent(this, PerAppProxyActivity::class.java)
            "settings" -> Intent(this, SettingsActivity::class.java)
            // Was "about", which nothing could ever reach: the only item that
            // ever carried that id came from the panel's `about_url` and so
            // always had a URL, and an item with a URL is opened in a browser
            // instead of coming here. The screen had been unreachable since.
            "about_app" -> Intent(this, AboutActivity::class.java)
            // Upstream had a "promotion" entry here that opened a base64-hidden
            // third-party URL. Removed: it is not ours, it is not disclosed to
            // the user, and an undisclosed outbound link is the kind of thing a
            // Play review flags.
            else -> return
        }
        settingsActivityLauncher.launch(intent)
    }

    /**
     * The one way the tunnel is brought down, wherever the tap came from.
     *
     * @param deferSync passed straight through to the ViewModel, and only ever
     *   true for [disconnectThenShowAd] — see [HomeViewModel.onDisconnectRequested].
     */
    private fun disconnect(deferSync: Boolean = false) {
        homeViewModel.onDisconnectRequested(deferSync)
        LauncherManager.stopService(this)
    }

    /**
     * The user's own Disconnect, which is the only one that owes an ad.
     *
     * The stop is first and unconditional. Everything after it is optional in
     * the strongest sense: if [DisconnectAdFlow] never ran, threw, or was
     * cancelled by the Activity going away, the tunnel is still down and the
     * screen still says so. That ordering is why the ad cannot become a way to
     * fail a disconnect.
     *
     * The other three paths that stop the tunnel deliberately do not come here.
     * [beginConnect]'s repair is mid-connect and about to start a tunnel;
     * [HomeViewModel.failVerification]'s is the app telling the user their
     * server does not work, which is no moment for an ad; and the session
     * limit's cut happens in the tunnel's own process, where there is no
     * Activity to show one over.
     */
    private fun disconnectThenShowAd() {
        // Read before the stop, because the stop is what erases it: every
        // figure on the summary comes from the live tunnel state. See
        // [SessionSummary].
        val summary = SessionSummary.of(homeViewModel.uiState.value)
        val owesAnAd = DisconnectAdFlow.worthRunning()
        disconnect(deferSync = owesAnAd)
        if (!owesAnAd) {
            // No ad, same screen. The summary is the app reporting a session
            // that ended, not a wrapper Google's placement rules asked for,
            // and a screen that only appeared when an ad had been sold would
            // be exactly the second thing.
            disconnectSummary.value = summary
            return
        }

        adInThisWait.value = true
        lifecycleScope.launch {
            // The same hold the server-list tap uses, for the same reason: a
            // smart session is about to exist, and a live dial during it lets
            // a tap start a connect on a tunnel this flow is committed to
            // destroying. See [busy].
            busy.value = true
            try {
                DisconnectAdFlow.run(this@MainActivity) {
                    disconnectSummary.value = summary
                }
            } finally {
                busy.value = false
            }
            // Only on the paths that got here without being cancelled: the
            // sync the disconnect deferred, now that no tunnel of ours is up.
            homeViewModel.syncWhenTheTunnelIsDown()
        }
    }

    /**
     * Everything a connect tap means, in the order that makes it one journey:
     * the ad this tap owes, and then — on a screen of its own — the tunnel
     * they actually asked for.
     *
     * The split between [ConnectLoadingDialog] and [ConnectingScreen] is the
     * design, not an accident of refactoring. The dialog covers only what
     * happens *before* the ad, where there is nothing to report and the user
     * has not left home yet. [ConnectingScreen] opens the moment the last ad
     * of the tap is dismissed, and owns everything after it. That ordering is
     * what makes the ad a transition between two screens rather than an
     * interruption of one — and it is why the screen must never be opened
     * early "so the state machine is simpler".
     *
     * The dial is still not touched here: its spin means "your tunnel is being
     * dialled", and during the ad phase the tunnel going up is the panel's.
     */
    private fun beginConnect() {
        if (connecting.value) return

        // Asked of Android, not of the screen's state.
        //
        // "Connected" here is assembled from broadcasts, and a stale receiver
        // answering one of them can leave the screen believing the tunnel is
        // down while it is up. Starting again would then do nothing at all —
        // the service reports it is already running — and the user would be
        // left with a tunnel they cannot turn off. A live *smart* tunnel is
        // not that case: it is this flow's own and is about to be replaced.
        // See [TunnelState].
        //
        // First of all the checks, and it used to be third. A tunnel that is
        // up has to be stoppable whatever else is true — the two branches
        // below both end in something that is not a disconnect.
        if (!SmartTunnel.isActive && TunnelState.isRunning(this)) {
            disconnect()
            return
        }

        // A parked ad from the splash spends this tap, and spends it on the
        // server list.
        //
        // The alternative was showing it and then the connect slot's ad too:
        // two full-screen ads for one tap, with only the second of them
        // followed by a screen. Splitting them across two taps gives each an
        // action of its own and a destination of its own — this one lands on
        // the server list, the next tap's lands on [ConnectingScreen] — and
        // the connect slot is not skipped, only postponed by one tap.
        //
        // The list is the honest destination rather than a convenient one: a
        // user who has just asked to connect is exactly the user who might
        // want to choose where to.
        if (AdInventory.hasPending()) {
            adInThisWait.value = true
            showPendingAdThen { showServersState.value = true }
            return
        }

        if (!homeViewModel.uiState.value.hasServer) {
            // Nothing to connect to — but the tap is still the user's first
            // action, so it ends the splash's ad session like any other. That
            // matters more than it sounds: the session only ends on an action,
            // the auto-pick that would give this device a server is deferred
            // while the session is up, and refusing the tap outright left both
            // waiting on each other with the user stuck on a screen that could
            // not connect. No ad is shown here — the branch above already took
            // any that existed.
            if (splashAdSessionOpen()) {
                showPendingAdThen { warnNoServer() }
            } else {
                homeViewModel.autoPickIfNeeded()
                warnNoServer()
            }
            return
        }

        // The consent sheet is its own modal moment, and it happens once per
        // install. Nothing is stacked on top of it: its callback runs a plain
        // connect, and the ad scenario gets its turn on the next tap.
        val consent = if (SettingsManager.isVpnMode()) VpnService.prepare(this) else null
        if (consent != null) {
            requestVpnPermission.launch(consent)
            return
        }

        connecting.value = true
        // Asked before the flow runs, because the flow is what changes the
        // answer. Not a promise that an ad will fill — only that this wait is
        // one an ad is expected in, which is what the line on screen claims.
        adInThisWait.value = AdManager.isActive(AdSlot.CONNECT)
        lifecycleScope.launch {
            try {
                val usedSmart = runAdBeforeConnect {
                    connectStage.value = ConnectStage.PREPARING
                }
                // The paths that showed nothing at all still land on the
                // screen: it is the connect journey, not the ad's container,
                // and a tap that behaved differently depending on whether an
                // ad happened to fill would be a worse app for it.
                if (connectStage.value == null) connectStage.value = ConnectStage.PREPARING

                if (usedSmart) {
                    // The smart core is releasing the VPN interface; the same
                    // half second the settings restart path already allows.
                    delay(SMART_RELEASE_MS)
                }
                connectStage.value = ConnectStage.DIALLING
                startV2Ray()
                awaitConnectSettled()

                if (homeViewModel.uiState.value.isOn) {
                    connectStage.value = ConnectStage.DONE
                    delay(CONNECTED_HOLD_MS)
                    connectStage.value = null
                } else {
                    // Left up. A failure is the one outcome the user has to
                    // acknowledge, and the home screen behind says the same
                    // thing in a line they may never look at.
                    connectStage.value = ConnectStage.FAILED
                }
            } finally {
                connecting.value = false
            }
        }
    }

    /**
     * The one ad this tap owes.
     *
     * One, not two, and structurally rather than by timing: a tap that had a
     * parked splash ad to show never reaches here — [beginConnect] sends it to
     * the server list instead. So the connect slot is the only thing that can
     * run at this point, and there is no arrangement of fills, races or panel
     * switches that puts two full-screen ads on one tap.
     *
     * The splash session's tunnel is reused, not wasted: [ConnectAdFlow]'s
     * bring-up recognises a live session this process opened and loads the
     * connect ad straight through it. And when the flow declines to run —
     * slot off, or its interval says no — the session is still this tap's to
     * take down before the real server is dialled.
     *
     * @param onScreenFree called once, when the ad is gone and the screen
     *   belongs to the connect again. [ConnectAdFlow] fires it on every path,
     *   including the ones that show nothing.
     * @return whether a smart tunnel was up during any of it, so the caller
     *   knows to let the VPN interface go before dialling the real server.
     */
    private suspend fun runAdBeforeConnect(onScreenFree: () -> Unit): Boolean {
        val hadSplashSession = SmartTunnel.isActive
        val usedSmart = ConnectAdFlow.run(this, onScreenFree)
        if (SmartTunnel.isActive) {
            SmartTunnel.stop(this)
        }
        return usedSmart || hadSplashSession
    }

    /**
     * Waits for the dial to reach a state worth handing the screen back to.
     *
     * "Settled" has to mean *stayed*. Stopping the smart tunnel makes the
     * service broadcast a stop that can land after the real connection has
     * already been asked for, so the dial dips to off and back; a dialog that
     * closed on the first non-connecting state would flash the user a failure
     * that is not one. A state that survives [SETTLE_HOLD_MS] is the real
     * one. The overall cap is a backstop only — past it the dial says the
     * same thing this dialog would.
     */
    private suspend fun awaitConnectSettled() {
        withTimeoutOrNull(CONNECT_SETTLE_TIMEOUT_MS) {
            while (true) {
                homeViewModel.uiState.first { !it.isConnecting }
                val flippedBack = withTimeoutOrNull(SETTLE_HOLD_MS) {
                    homeViewModel.uiState.first { it.isConnecting }
                }
                if (flippedBack == null) return@withTimeoutOrNull
            }
        }
    }

    /**
     * Whether the splash's ad session still owes this screen something — a
     * smart tunnel to bring down, a second ad to show, or both. In countries
     * the panel exempts from Smart, the ad loads with no tunnel at all, so
     * the pending ad has to count on its own.
     */
    private fun splashAdSessionOpen(): Boolean =
        SmartTunnel.isActive || AdInventory.hasPending()

    /**
     * The user's first real action after the splash left an ad session open.
     *
     * The scenario, in order: show the pending ad if one filled (the second
     * load the splash started), open the screen the tap was heading for, and
     * bring the smart tunnel down behind it. Either half can be absent — an ad
     * that never filled, or a country that needed no tunnel — and what remains
     * still runs in this order.
     *
     * **When an ad was shown, the screen opens before the teardown**, which is
     * the same rule [beginConnect] is built around: an ad only counts as a
     * transition if what follows it is the next screen, and the drain below is
     * several seconds of the user looking at the screen they tapped from.
     * With no ad there is no transition to get right, so the old order stands
     * and the action gets the benefit of the auto-pick.
     *
     * The teardown is suspending because it drains first: see
     * [SmartTunnel.stop].
     */
    private fun showPendingAdThen(action: () -> Unit) {
        lifecycleScope.launch {
            // The screen is busy for the whole of this, teardown included.
            //
            // It used to be busy for none of it, which left the dial live
            // while the smart session was being closed underneath. A tap in
            // that window started a second scenario that reused the very
            // session the first one was already committed to destroying: it
            // requested an ad through a tunnel that vanished mid-flight, and
            // then showed it. One tap, one scenario — the dialog is what
            // makes that true.
            busy.value = true
            try {
                val pending = AdInventory.takePending()
                if (pending != null) {
                    AdInventory.showAndAwait(this@MainActivity, pending)
                    // The destination, the moment the ad is gone.
                    action()
                }
                SmartTunnel.stop(this@MainActivity)
                // Before the action, not after: it was deferred while the
                // session was up (a measurement taken through the smart tunnel
                // is not the server's own latency), and the action may well be
                // the one that needs its result.
                homeViewModel.autoPickIfNeeded()
                if (pending == null) action()
            } finally {
                busy.value = false
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    /**
     * Why a connect tap did nothing.
     *
     * Upstream's string here was «انتخاب فایل کانفیگ» / "Select a config" — it
     * names the file chooser the security pass removed, so it told the user to
     * do something this app has no way of doing. Written inline in both
     * languages like every other redesigned screen, and it tells the two cases
     * apart: a choice still being measured, or a round that ended with nothing
     * answering.
     */
    private fun warnNoServer() {
        toast(
            when {
                homeViewModel.isChoosingServer ->
                    "Finding the best server…"

                else ->
                    "Pick a server from the list first"
            }
        )
    }

    private fun startV2Ray() {
        if (!homeViewModel.uiState.value.hasServer) {
            warnNoServer()
            return
        }
        // Whatever happened before this tap, the server about to start is the
        // user's. A leftover run-guid override — from an ad flow that was
        // cancelled rather than finished — would otherwise start the panel's
        // smart profile here, on a tunnel scoped to this app alone, and the
        // user would watch a "connected" dial carry nothing.
        SmartTunnel.clearSession()
        // The clock starts before the tunnel does, so the countdown covers the
        // whole connection rather than beginning wherever the core happened to
        // finish. A panel with no limit clears it instead.
        SessionLimit.begin()
        homeViewModel.onConnectRequested()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.uiState.value.isRunning) LauncherManager.stopService(this)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            startV2Ray()
        }
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            if (mainViewModel.uiState.value.isRunning) restartV2Ray()
        }
    }

    /** The drawer entries that survive the security pass, plus the panel's links. */
    private fun drawerItems(links: List<Pair<String, String>>): List<DrawerItem> = buildList {
        add(DrawerItem("per_app_proxy", "Per-app proxy"))
        add(DrawerItem("settings", "Settings"))
        // The app's own build, not the operator's company page — which is what
        // the panel's `about_url` below is, and why the two are named apart.
        // This one is the version, the core it is built on, and the
        // open-source licences, none of which any other screen shows.
        add(DrawerItem("about_app", "About this app"))
        // Supplied by the panel, so a support page can be added or moved
        // without an app release. An empty URL never reaches here.
        links.forEach { (key, url) ->
            when (key) {
                "privacy" -> add(DrawerItem(key, "Privacy policy", url))
                "terms" -> add(DrawerItem(key, "Terms", url))
                "about" -> add(DrawerItem(key, "About us", url))
                "support" -> add(DrawerItem(key, "Support", url))
                "faq" -> add(DrawerItem(key, "FAQ", url))
            }
        }
    }

    private companion object {
        /** Long enough for a stopped core to hand the VPN interface back. */
        const val SMART_RELEASE_MS = 600L

        /** How long a dial state must hold before it counts as the answer. */
        const val SETTLE_HOLD_MS = 1_500L

        /**
         * Backstop for the loading dialog. Comfortably past the verification
         * gate's worst case (a sing-box start plus its 22s measurement), so
         * it only ever fires when something is genuinely stuck.
         */
        const val CONNECT_SETTLE_TIMEOUT_MS = 40_000L

        /**
         * How long «متصل شدید» stays before the home screen takes over.
         *
         * Long enough to be read as the answer to the tap, short enough that
         * it is not a screen standing between the user and their connection.
         * The home screen says the same thing from then on, in the dial.
         */
        const val CONNECTED_HOLD_MS = 1_400L
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { toast(R.string.toast_failure) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            // Close what is open before leaving. Backgrounding the app from the
            // server list — which is what this did — loses the user's place for
            // a press that everywhere else means "go up one".
            if (connectStage.value != null) {
                // A connect in flight has nothing to go back to: the ad
                // tunnel may be half down and the user's core half up, and
                // leaving would hand them a dial that contradicts both. Only
                // the two states that are finished with can be left, and one
                // of them leaves by itself.
                if (connectStage.value == ConnectStage.FAILED) connectStage.value = null
                return true
            }
            if (disconnectSummary.value != null) {
                disconnectSummary.value = null
                return true
            }
            if (confirmDisconnect.value) {
                confirmDisconnect.value = false
                return true
            }
            if (showDrawerState.value) {
                showDrawerState.value = false
                return true
            }
            if (showServersState.value) {
                showServersState.value = false
                return true
            }
            // From home, back leaves the app running in the background rather
            // than tearing down a live tunnel.
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
