package com.rahgozar.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.rahgozar.app.AppConfig.ANG_PACKAGE
import com.rahgozar.app.ads.AdWorkerGate
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.security.SecureStore
import com.rahgozar.app.ui.compose.ThemeManager
import com.rahgozar.app.ui.splash.SplashSession

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        // The ad SDK enqueues its own deferred jobs, which would otherwise run
        // long after any tunnel is gone. See [AdWorkerGate].
        .setWorkerFactory(AdWorkerGate)
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        // Before anything opens a store. This derives the at-rest key from the
        // Android Keystore and, on an upgrade, re-encrypts the stores that
        // hold the device's identity and the server configurations. Runs in
        // every process — the tunnel processes read the same files, and one
        // opening them without the key would find them empty.
        SecureStore.initialise()

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
        ThemeManager.refresh()

        // Records when the app was last on screen, which is what decides
        // whether a later launch replays the splash — see [SplashSession].
        // Registered here rather than in a base activity so no screen can be
        // added later that forgets to do it.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = SplashSession.markForeground()
            override fun onActivityPaused(activity: Activity) = SplashSession.markForeground()

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // The panel sync deliberately does NOT run here. SplashActivity owns it,
        // because the splash shows that sync's real stages and would otherwise
        // be narrating work it did not start — and running it in both places
        // meant two registrations and two bootstraps per launch.
    }

    /**
     * Reads the whole configuration from the panel on every launch.
     *
     * This is the only way a server, a setting or an ad placement enters the
     * app, so it runs before anything the user can interact with. It is not
     * blocking: a launch on a filtered network must still open the app with
     * whatever the device already had, rather than hanging on a request that
     * is not going to be answered.
     */}
