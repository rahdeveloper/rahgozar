package com.rahgozar.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import com.rahgozar.app.AppConfig
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.core.CoreRoutePin
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.util.LogUtil

/**
 * Applies the user's per-app choices to a tun being built.
 *
 * Shared by every VpnService in the app rather than living inside one of them:
 * the choices are made on a single screen and the user has no idea which core
 * will carry them, so "only these apps" has to mean the same thing whether the
 * tunnel is Xray's or OpenVPN's.
 */
object PerAppProxy {

    /** Where the ad SDK's identifier and part of its fetching actually live. */
    private const val GMS_PACKAGE = "com.google.android.gms"

    /** Where a tapped ad sends the user to install what it advertised. */
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * @param context the service building this tun, used to find the browser a
     *   tapped ad would open
     * @param tag log prefix identifying the calling service
     * @param needsCoreExclusion true when the calling core cannot protect its
     *   own sockets, so carrying this app inside the tun is only safe if the
     *   core's dial addresses are known and excluded. Xray's build here cannot
     *   protect; openvpn3 and libbox can, and pass false.
     */
    fun apply(
        builder: VpnService.Builder,
        context: Context,
        tag: String,
        needsCoreExclusion: Boolean = false,
    ) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // The one tunnel this app must be *inside* — and the only one nothing
        // else belongs in.
        //
        // The smart tunnel exists for exactly one reason: to carry the ad
        // requests this app makes, from a country where they cannot be made
        // directly. The AdMob SDK runs in this process, so excluding our own
        // package — which every branch below otherwise does — would send every
        // ad request, and its DNS, straight out on the user's real address
        // while a tunnel sat there carrying nothing.
        //
        // So the rule inverts for these few seconds: an allow list of exactly
        // this app. Merely dropping the exclusion would put the *whole phone*
        // through the operator's server for the length of the session —
        // someone's download, someone's banking app that refuses foreign
        // addresses — and none of that is what the session is for. The user's
        // own tunnel, below, is unchanged.
        // Whatever tun is being built now replaces whatever the flag described.
        PanelStore.sessionRideActive = false

        if (PanelStore.smartSessionActive) {
            // Four packages, and each one earns its place.
            //
            // This app, because the SDK runs in it. Play services, because the
            // SDK is not only this process — it reaches there for the
            // advertising identifier and part of the fetch, and leaving it out
            // was measured on the device: every fill died on a reset TLS
            // handshake, the signature of a request going out on the real
            // network after all.
            //
            // And then the two places a *tapped* ad sends the user: the Play
            // Store, and whichever browser opens a link. An ad the user
            // cannot act on is worth nothing to anyone — they tap, the store
            // opens on their real address, and in the countries this tunnel
            // exists for it does not load at all.
            //
            // Everything else on the phone stays outside, which is the whole
            // point of an allow list rather than simply dropping the
            // exclusion.
            val allowed = buildList {
                add(selfPackageName)
                add(GMS_PACKAGE)
                add(PLAY_STORE_PACKAGE)
                addAll(clickTargets(context, tag))
            }
            for (pkg in allowed) {
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { LogUtil.w(AppConfig.TAG, "$tag: $pkg not in the smart tunnel — $it") }
            }
            LogUtil.i(AppConfig.TAG, "$tag: smart session — tunnel carries ${allowed.joinToString()}")
            return
        }

        // Timed sessions need the ad path to work *while connected*.
        //
        // The user extends their time by watching a rewarded ad, and the only
        // network they have then is their own tunnel — Android runs one VPN at
        // a time, so the smart tunnel is not an option without dropping the
        // connection they are trying to extend. So when the panel is running
        // timed sessions, this app and Play services stay inside the user's
        // tunnel with everything else. The panel still sees the real address:
        // it is only ever asked on the splash, before any tunnel exists.
        //
        // "Inside" is only safe if the core's own uplink can stay out. A core
        // that protects its sockets is always safe; Xray's build cannot, so
        // for it the tun must exclude the addresses the core will dial — and
        // those have to be *known*. For a domain server they come from the
        // resolution the config generator pinned; if it pinned nothing, this
        // app stays outside exactly as it always did, the connection works,
        // and only the extend offer is withheld. Riding anyway was tried, by
        // accident: the tun swallowed the core's own uplink, and every
        // CDN-fronted server read as dead the moment timed sessions came on.
        val keepUsInside = SessionLimit.wantsRideUserTunnel &&
            (!needsCoreExclusion || coreDialAddressesKnown(tag))

        // Off: the whole phone goes through the tunnel, except us. Excluding
        // ourselves matters — the app talks to the panel, and the panel has to
        // see the user's real address to decide their country.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            if (keepUsInside) {
                PanelStore.sessionRideActive = true
                LogUtil.i(AppConfig.TAG, "$tag: timed session — this app rides the user's tunnel")
                return
            }
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // On, but nothing chosen. An empty allow-list would carry nothing at
        // all, so the tunnel falls back to carrying everything — which is what
        // the per-app screen warns about in that exact state.
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        PanelStore.sessionRideActive = keepUsInside
        // Our own package follows the mode: excluded when the list is a bypass
        // list, and kept out of the allow list otherwise — unless a timed
        // session needs us carried, in which case the mode is followed for
        // every other app and inverted for us.
        when {
            keepUsInside && bypassApps -> apps.remove(selfPackageName)
            keepUsInside -> {
                apps.add(selfPackageName)
                apps.add(GMS_PACKAGE)
                apps.add(PLAY_STORE_PACKAGE)
                apps.addAll(clickTargets(context, tag))
            }
            bypassApps -> apps.add(selfPackageName)
            else -> apps.remove(selfPackageName)
        }

        apps.forEach {
            try {
                if (bypassApps) {
                    builder.addDisallowedApplication(it)
                } else {
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // The app was uninstalled after being picked. Not fatal: the
                // rest of the list is still valid.
                LogUtil.e(AppConfig.TAG, "$tag: Failed to configure app $it", e)
            }
        }
    }

    /**
     * Whether the running server's dial addresses are known, so the tun could
     * exclude them. For an IP server that is the address itself; for a domain
     * server the name is resolved here and now — the tun does not exist yet —
     * and pinned so the exclusions, the core's DNS and any later check all
     * hold the same list. Empty means even that failed, and riding would
     * strangle the tunnel.
     */
    private fun coreDialAddressesKnown(tag: String): Boolean {
        val guid = MmkvManager.getRunServer() ?: return false
        val server = MmkvManager.decodeServerConfig(guid)?.server
        val known = CoreRoutePin.ensurePinned(server).isNotEmpty()
        if (!known) {
            LogUtil.w(
                AppConfig.TAG,
                "$tag: $server would not resolve, this app stays outside the tunnel — no extend offer",
            )
        }
        return known
    }

    /**
     * Every package a tapped ad could hand its click-through to.
     *
     * Not "the default browser" — that was one package chosen by one query,
     * and it was wrong in two ordinary situations. A phone with several
     * browsers and no default resolves `ACTION_VIEW` to the system's chooser,
     * which is not a browser and gets discarded, leaving the list empty with
     * nothing logged. And the ad SDK often does not use `ACTION_VIEW` at all:
     * it binds a Custom Tabs provider, which can be a different package than
     * the one that handles plain links. Either way the click leaves through a
     * uid the tun was never told about — carrying the click id that ties it
     * to an impression this tunnel just protected, which is precisely the
     * correlation the tunnel exists to break.
     *
     * So both questions are asked, and every answer is taken. The cost is
     * honest and bounded: for the seconds an ad is on screen, this user's
     * browsers are inside the tunnel too.
     */
    private fun clickTargets(context: Context, tag: String): List<String> {
        val packageManager = context.packageManager
        val targets = linkedSetOf<String>()

        fun collect(intent: Intent, flags: Int) {
            runCatching {
                @Suppress("DEPRECATION", "QueryPermissionsNeeded")
                packageManager.queryIntentActivities(intent, flags)
                    .mapNotNull { it.activityInfo?.packageName }
                    .filterTo(targets) { it.isNotBlank() && it != ANDROID_RESOLVER }
            }
        }

        fun collectServices(intent: Intent) {
            runCatching {
                @Suppress("DEPRECATION", "QueryPermissionsNeeded")
                packageManager.queryIntentServices(intent, 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .filterTo(targets) { it.isNotBlank() }
            }
        }

        // Whatever the user actually set as their default, first — it is the
        // most likely destination and worth having even if the sweeps below
        // return nothing.
        val browsable = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        collect(browsable, PackageManager.MATCH_DEFAULT_ONLY)
        // Then every package that could handle such a link at all …
        collect(browsable, 0)
        // … and every Custom Tabs provider, which is how the SDK usually opens one.
        collectServices(Intent(CUSTOM_TABS_ACTION))

        if (targets.isEmpty()) {
            // Nothing to add is not nothing to say: a tap on this device would
            // leave through a package the tunnel does not carry.
            LogUtil.w(
                AppConfig.TAG,
                "$tag: no browser or custom-tabs provider found — a tapped ad would leave the tunnel",
            )
        }
        return targets.toList()
    }
}

/** The system's "which app should open this?" chooser, which carries nothing. */
private const val ANDROID_RESOLVER = "android"

/** The service ad SDKs bind to open a link in-app rather than in a browser. */
private const val CUSTOM_TABS_ACTION = "android.support.customtabs.action.CustomTabsService"
