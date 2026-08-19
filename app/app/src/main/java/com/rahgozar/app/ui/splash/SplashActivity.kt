package com.rahgozar.app.ui.splash

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rahgozar.app.ads.SplashAdFlow
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.PanelSync
import com.rahgozar.app.panel.TunnelSettings
import com.rahgozar.app.ui.brand.BrandTheme
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.rememberAppearance
import com.rahgozar.app.ui.main.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * The first screen, and the only one that runs before the app has a
 * configuration.
 *
 * It owns the sync rather than merely waiting for one started elsewhere: the
 * messages it shows are that sync's real stages, so something has to be able to
 * observe them from the beginning.
 */
class SplashActivity : ComponentActivity() {

    private val viewModel: SplashViewModel by viewModels {
        SplashViewModel.Factory(application)
    }

    /** Answered by the system's VPN consent sheet, one question at a time. */
    private var vpnConsent: CompletableDeferred<Boolean>? = null

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vpnConsent?.complete(it.resultCode == RESULT_OK)
        }

    /** The ad flow must run once, however many times the outcome recomposes. */
    private var adFlowStarted = false

    /**
     * Hands over to the app and leaves.
     *
     * CLEAR_TOP with SINGLE_TOP because the splash can now run over a task that
     * still holds a screen — when the app has sat unused long enough to be
     * re-synced. Without them that screen would stay underneath and the user
     * would find a second copy of the app behind the first.
     *
     * The splash itself never stays: pressing back from the app should leave
     * it, not replay this.
     */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Runs the splash ad scenario, then opens the app — always, because every
     * exit from the flow, ad or no ad, lands on the home screen.
     */
    private fun startAdFlow() {
        if (adFlowStarted) return
        adFlowStarted = true
        lifecycleScope.launch {
            SplashAdFlow.run(
                activity = this@SplashActivity,
                onWaiting = viewModel::showAdWaiting,
                ensureVpnConsent = ::ensureVpnConsent,
            )
            openApp()
        }
    }

    /**
     * Gives the splash the whole screen, system bars included.
     *
     * The status bar is the reason. This screen brings the ad flow's tunnel up
     * ([com.rahgozar.app.ads.SmartTunnel]), and Android answers any VPN with a
     * key icon in the status bar — an icon that belongs to a connection the
     * user never asked for and that the app deliberately reports as off. Seen
     * here it would contradict the screen it is sitting on.
     *
     * Hidden from the first frame rather than when the tunnel starts, so there
     * is no moment for the key to appear in and no bar sliding away mid-splash
     * to draw attention to it.
     *
     * Transient-by-swipe: a user who wants the bars can still pull them down,
     * and the app has them back the moment the home screen takes over.
     */
    private fun goFullScreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * True once Android's VPN consent stands. The system sheet appears over
     * the splash on the very first run; every later launch answers without
     * asking. Declining is a real answer — the flow skips ads, it does not
     * ask again this launch.
     */
    private suspend fun ensureVpnConsent(): Boolean {
        val intent = VpnService.prepare(this) ?: return true
        val decision = CompletableDeferred<Boolean>()
        vpnConsent = decision
        vpnConsentLauncher.launch(intent)
        return decision.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Whether the app is still where the user left it — a task with its
        // screens still in it, reached by tapping the launcher icon.
        val resumingLiveTask = !isTaskRoot &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            intent.action == Intent.ACTION_MAIN

        // [SplashSession] holds the whole rule: a new process syncs, a running
        // tunnel never does, and an app left alone long enough starts over.
        if (SplashSession.alreadySynced(this)) {
            if (resumingLiveTask) {
                // The screens underneath are the app, and they are current.
                // Getting out of the way is the entire job here.
                finish()
                return
            }

            PanelSync.storedConfiguration()?.let { stored ->
                // Applied even though nothing was fetched: this may be a fresh
                // process, where the ad configuration and the core's tunnel
                // options have not been set yet.
                TunnelSettings.apply(stored.settings)
                AdManager.apply(this, stored.ads)
            }
            openApp()
            return
        }

        // Only the path that actually draws the splash: the two above are gone
        // within the frame and have no bars to hide.
        goFullScreen()

        setContent {
            val appearance = rememberAppearance()
            val phase by viewModel.phase.collectAsStateWithLifecycle()
            val reason by viewModel.failureReason.collectAsStateWithLifecycle()
            val outcome by viewModel.outcome.collectAsStateWithLifecycle()

            LaunchedEffect(outcome) {
                when (outcome) {
                    is SplashOutcome.Continue -> openApp()
                    is SplashOutcome.AdFlow -> startAdFlow()
                    else -> Unit
                }
            }

            // The splash is the first thing drawn, so it is where the
            // language and theme the user chose last time take effect. Reading
            // them here rather than in each screen means a relaunch never
            // flashes the wrong one.
            BrandTheme(
                themeMode = appearance.themeMode.value,
            ) {
                // Edge-to-edge draws under the status bar, so the system icons
                // sit on our background: they have to invert with it or they
                // disappear on the light theme. Still worth setting although
                // [goFullScreen] hides those bars — a swipe brings them back
                // transiently, over this same background.
                val dark = LocalPalette.current.isDark
                LaunchedEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                }
                SplashScreen(
                    phase = phase,
                    failureReason = reason,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}
