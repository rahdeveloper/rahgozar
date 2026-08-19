package com.rahgozar.app.panel

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Brings the AdMob SDK up, and only when the panel says so.
 *
 * Initialisation is deliberately late and conditional. `MobileAds.initialize`
 * starts network activity and identifier collection the moment it is called,
 * so an app whose operator has ads switched off must never call it — both
 * because it is wasted work and because Play's data-safety answers have to
 * match what the app actually does.
 *
 * Everything about *what* is shown lives in [AdsConfig], which is panel state.
 * This object knows only how to start the SDK and where the current
 * configuration is.
 */
object AdManager {

    private val started = AtomicBoolean(false)

    @Volatile
    private var config: AdsConfig = AdsConfig.disabled()

    /** The configuration the last sync delivered. Never null. */
    val current: AdsConfig get() = config

    /**
     * Applies a freshly-fetched configuration.
     *
     * Safe to call on every sync. Deliberately does *not* start the SDK any
     * more: on the networks this app is for, the SDK's first requests only
     * succeed once the Smart tunnel is up, so initialisation now happens in
     * [ensureStarted], called by the ad flow at the moment an ad is actually
     * about to be requested.
     */
    fun apply(context: Context, ads: AdsConfig) {
        config = ads
        if (!ads.enabled) {
            LogUtil.i(AppConfig.TAG, "ads: disabled by panel")
            return
        }
        for (placement in ads.all().filter { it.enabled }) {
            LogUtil.i(
                AppConfig.TAG,
                "ads: ${placement.slot.wire} → ${placement.format.wire} " +
                    "unit=${placement.adUnitId} smart=${placement.requireSmart} " +
                    "interval=${placement.minIntervalSec}s",
            )
        }
    }

    /**
     * Starts the SDK, once, at the moment an ad is about to be loaded.
     *
     * Timing is the whole point of this method's existence. `MobileAds
     * .initialize` starts network activity immediately, and calling it while
     * the device is still on a network that blocks Google leaves those
     * requests hanging exactly when the splash wants a fast fill. The ad flow
     * calls this *after* the Smart tunnel is up (or immediately, when the
     * panel says this device does not need one).
     *
     * A config that turns ads off later still takes effect for every
     * placement even though the SDK stays loaded — there is no way to
     * un-initialise it, which is exactly why the first call is guarded.
     */
    suspend fun ensureStarted(context: Context) {
        val ads = config
        if (!ads.enabled) return
        if (!started.compareAndSet(false, true)) return

        // Test mode is the panel's call, not the build's. A release build
        // pointed at test units is a perfectly valid state while an AdMob
        // account is still being set up.
        if (ads.testMode || ads.testDeviceIds.isNotEmpty()) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder()
                    .setTestDeviceIds(ads.testDeviceIds)
                    .build()
            )
        }

        // Awaited, not fire-and-forget. Initialisation is itself network work
        // — the whole reason it was moved out of `apply` and behind the
        // tunnel — so returning before it finishes would let it run on
        // whatever path exists after the caller moves on.
        withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                MobileAds.initialize(context.applicationContext) { status ->
                    val adapters = status.adapterStatusMap.entries.joinToString {
                        "${it.key}=${it.value.initializationState}"
                    }
                    LogUtil.i(AppConfig.TAG, "ads: SDK ready (test_mode=${ads.testMode}) $adapters")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } ?: LogUtil.w(AppConfig.TAG, "ads: SDK init did not report back in time")
    }

    /** Long enough for a first init on a slow link, short enough to give up on. */
    private const val INIT_TIMEOUT_MS = 10_000L

    /**
     * Whether a slot should show an ad right now.
     *
     * The single question a UI should ask. Rendering comes later, once the
     * placement's position in each screen is decided.
     */
    fun isActive(slot: AdSlot): Boolean = config.isActive(slot)

    fun placement(slot: AdSlot): AdPlacement = config.placement(slot)
}
