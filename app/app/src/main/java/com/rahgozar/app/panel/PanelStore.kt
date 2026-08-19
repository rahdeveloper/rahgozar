package com.rahgozar.app.panel

import com.rahgozar.app.security.SecureStore
import com.tencent.mmkv.MMKV

/**
 * What the app remembers about its relationship with the panel.
 *
 * Kept in its own MMKV instance rather than the shared settings store: this is
 * device identity, not user preference, and mixing them would put the device
 * private key one careless "clear settings" away from being wiped — which
 * costs a re-registration every time.
 */
object PanelStore {

    private const val ID = "PANEL"

    private const val KEY_DEVICE_PRIVATE = "device_private_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_EXPIRES = "token_expires_at"
    private const val KEY_ETAG = "bootstrap_etag"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_BUNDLE_VERSION = "bundle_version"
    private const val KEY_SOURCES = "discovery_sources"
    private const val KEY_ENDPOINTS = "discovery_endpoints"
    private const val KEY_SETTINGS = "settings_json"
    private const val KEY_ADS = "ads_json"
    private const val KEY_COUNTRIES = "server_countries"
    private const val KEY_SMART_REQUIRED = "smart_required"
    private const val KEY_SESSION_DEADLINE = "session_deadline_at"
    private const val KEY_SESSION_EXTENSIONS = "session_extensions_used"
    private const val KEY_SMART_GUIDS = "smart_guids"
    private const val KEY_APPLIED_SCHEMA = "applied_schema"
    private const val KEY_SMART_SESSION = "smart_session_active"
    private const val KEY_SESSION_RIDE = "session_ride_active"
    private const val KEY_SMART_TOUCHED = "smart_session_touched_at"
    private const val KEY_EXTEND_HOLD = "extend_hold_until"
    private const val KEY_AD_SHOWN_PREFIX = "ad_last_shown_"

    /**
     * Encrypted at rest, with a key the Android Keystore will not hand over —
     * see [SecureStore]. This store holds the device's private key and its
     * panel token, which together are a permanent subscription to the server
     * list for anyone who can copy the file.
     */
    private val store: MMKV by lazy { SecureStore.open(ID) }

    // --------------------------------------------------------------- device --

    /**
     * This device's X25519 pair, generated on first use.
     *
     * Stable for the life of the installation: the panel wraps the content key
     * to it, so a new pair means a new registration and a new device row.
     */
    fun deviceKeyPair(): DeviceKeyPair {
        store.decodeBytes(KEY_DEVICE_PRIVATE)?.takeIf { it.size == 32 }?.let {
            return DeviceKeyPair.fromPrivateKey(it)
        }
        val fresh = DeviceKeyPair.generate()
        store.encode(KEY_DEVICE_PRIVATE, fresh.privateKey)
        return fresh
    }

    var deviceId: String
        get() = store.decodeString(KEY_DEVICE_ID).orEmpty()
        set(value) { store.encode(KEY_DEVICE_ID, value) }

    // ---------------------------------------------------------------- token --

    var token: String
        get() = store.decodeString(KEY_TOKEN).orEmpty()
        set(value) { store.encode(KEY_TOKEN, value) }

    var tokenExpiresAt: Long
        get() = store.decodeLong(KEY_TOKEN_EXPIRES, 0L)
        set(value) { store.encode(KEY_TOKEN_EXPIRES, value) }

    /**
     * True when there is a token worth trying.
     *
     * The margin is a day: renewing early costs one request, while letting a
     * token expire mid-session costs a failed bootstrap on a network that may
     * be exactly the one the user is struggling with.
     */
    fun hasUsableToken(nowSeconds: Long): Boolean =
        token.isNotEmpty() && tokenExpiresAt > nowSeconds + 86_400

    fun clearRegistration() {
        store.removeValuesForKeys(arrayOf(KEY_TOKEN, KEY_TOKEN_EXPIRES, KEY_DEVICE_ID, KEY_ETAG))
    }

    // ------------------------------------------------------------ bootstrap --

    /** ETag of the last bootstrap, so an unchanged config costs one 304. */
    var etag: String
        get() = store.decodeString(KEY_ETAG).orEmpty()
        set(value) { store.encode(KEY_ETAG, value) }

    /**
     * Which *shape* of the bootstrap this device has fully applied.
     *
     * The 304 path replays what was stored — so when a build starts storing
     * something new out of the same response (the smart profile, say), a
     * device that synced before the upgrade would never receive it: its ETag
     * is still current, and the panel keeps answering "unchanged". Comparing
     * this against [PanelSync.APPLIED_SCHEMA] is how an upgraded build knows
     * its next sync must be a full one, whatever the ETag says.
     */
    var appliedSchema: Int
        get() = store.decodeInt(KEY_APPLIED_SCHEMA, 0)
        set(value) { store.encode(KEY_APPLIED_SCHEMA, value) }

    var configVersion: Long
        get() = store.decodeLong(KEY_CONFIG_VERSION, 0L)
        set(value) { store.encode(KEY_CONFIG_VERSION, value) }

    var lastSyncAt: Long
        get() = store.decodeLong(KEY_LAST_SYNC, 0L)
        set(value) { store.encode(KEY_LAST_SYNC, value) }

    /** True when the sync interval the panel asked for has elapsed. */
    fun isSyncDue(nowSeconds: Long, intervalMinutes: Int): Boolean =
        nowSeconds - lastSyncAt >= intervalMinutes * 60L

    /**
     * The last configuration the panel sent, kept verbatim.
     *
     * Needed because of the 304: on most launches the panel answers "unchanged"
     * and sends no body at all. Without a copy here, every such launch would
     * start with no settings and no ad configuration — the app would forget
     * what it was told the moment nothing changed, which is exactly the common
     * case.
     */
    var settingsJson: String
        get() = store.decodeString(KEY_SETTINGS).orEmpty()
        set(value) { store.encode(KEY_SETTINGS, value) }

    var adsJson: String
        get() = store.decodeString(KEY_ADS).orEmpty()
        set(value) { store.encode(KEY_ADS, value) }

    /**
     * Server id → ISO country code, as the panel reported it.
     *
     * Kept beside the profiles rather than inside them: ProfileItem is the
     * shape the v2ray core consumes, and adding a field it does not understand
     * to every stored profile to carry a flag is the wrong trade.
     */
    var serverCountries: String
        get() = store.decodeString(KEY_COUNTRIES).orEmpty()
        set(value) { store.encode(KEY_COUNTRIES, value) }

    // ------------------------------------------------------------- session --

    /**
     * When the current connection must end, on the boot-relative clock, or 0
     * for no limit.
     *
     * `elapsedRealtime`, not wall time: the deadline has to survive a user who
     * changes the phone's clock, and it only has to mean anything while the
     * device is up — which is exactly as long as a tunnel can be running.
     *
     * Multi-process because the two halves live apart: the UI process sets it
     * and the tunnel's own process is what counts it down and enforces it.
     */
    var sessionDeadline: Long
        get() = store.decodeLong(KEY_SESSION_DEADLINE, 0L)
        set(value) { store.encode(KEY_SESSION_DEADLINE, value) }

    /** Extensions already granted on the current connection. */
    var sessionExtensions: Int
        get() = store.decodeInt(KEY_SESSION_EXTENSIONS, 0)
        set(value) { store.encode(KEY_SESSION_EXTENSIONS, value) }

    // ----------------------------------------------------------------- ads --

    /**
     * Whether the panel told this device to bring the Smart tunnel up before
     * asking for an ad fill.
     *
     * Written only on a full bootstrap — a 304 carries no geo block, and the
     * panel's last verdict stands until it says otherwise. Defaults to true
     * for the same reason `require_smart` does: if nothing has arrived yet,
     * the stricter path is the safe one.
     */
    var smartRequired: Boolean
        get() = store.decodeBool(KEY_SMART_REQUIRED, true)
        set(value) { store.encode(KEY_SMART_REQUIRED, value) }

    /**
     * True while the Smart tunnel that the *ad flow* brought up is supposed to
     * be running — from the splash bringing it up until the user's first
     * action tears it down.
     *
     * Cross-process and persistent on purpose: the tunnel lives in the daemon
     * process and survives the UI process, so an in-memory flag would forget
     * whose tunnel is up exactly when the question matters. Stale values are
     * reconciled against the actually-running service on each launch.
     */
    var smartSessionActive: Boolean
        get() = store.decodeBool(KEY_SMART_SESSION, false)
        set(value) { store.encode(KEY_SMART_SESSION, value) }

    /**
     * Whether the tun that is up actually carries this app.
     *
     * Written by the tun builder — the only place that knows, because riding
     * requires excluding the core's uplink from the tun, and whether that was
     * possible depends on the addresses available at build time. Read from
     * the UI process to decide whether the extend offer is real. Cross-process
     * for the same reason as [smartSessionActive]: the writer and the readers
     * live in different processes.
     */
    /**
     * Until when the session countdown must not cut the tunnel, on the boot
     * clock, or 0.
     *
     * Set while the user is buying more time. The rewarded ad they tapped for
     * needs a network to load through, and the only one it has is the very
     * tunnel the countdown is about to end — so a tap in the last seconds used
     * to kill the request it started, and the user got no ad, no extra time,
     * and no connection either. The countdown waits instead.
     *
     * Bounded, and cross-process: the screen sets it, the tunnel's countdown
     * reads it.
     */
    var extendHoldUntil: Long
        get() = store.decodeLong(KEY_EXTEND_HOLD, 0L)
        set(value) { store.encode(KEY_EXTEND_HOLD, value) }

    /**
     * When the ad flow last did something with its session, on the boot clock.
     *
     * The watchdog that bounds a smart session needs to know the difference
     * between a session that is *long* and one that is *stranded*, and the
     * only honest signal is whether anything is still happening: an ad shown,
     * an ad tapped, a session opened. A cap measured from the start instead
     * cut a live flow five seconds after an ad appeared, on a device where the
     * user had simply spent a while in the Play Store — the exact failure a
     * watchdog is supposed to prevent, caused by the watchdog.
     *
     * Cross-process because the flow touches it from the UI process and the
     * watchdog reads it from the tunnel's.
     */
    var smartSessionTouchedAt: Long
        get() = store.decodeLong(KEY_SMART_TOUCHED, 0L)
        set(value) { store.encode(KEY_SMART_TOUCHED, value) }

    var sessionRideActive: Boolean
        get() = store.decodeBool(KEY_SESSION_RIDE, false)
        set(value) { store.encode(KEY_SESSION_RIDE, value) }

    /**
     * The hidden guids the smart candidates are stored under, as a JSON array
     * in the panel's order. The list is what makes cleanup possible: a sync
     * that shrinks the candidate set has to know which guids to remove, and
     * profile storage itself cannot be enumerated by prefix.
     */
    var smartGuidsJson: String
        get() = store.decodeString(KEY_SMART_GUIDS).orEmpty()
        set(value) { store.encode(KEY_SMART_GUIDS, value) }

    /** When [slot] last showed an ad (epoch millis), for `min_interval_sec`. */
    fun adLastShownAt(slot: String): Long =
        store.decodeLong(KEY_AD_SHOWN_PREFIX + slot, 0L)

    fun setAdLastShownAt(slot: String, atMillis: Long) {
        store.encode(KEY_AD_SHOWN_PREFIX + slot, atMillis)
    }

    // ------------------------------------------------------------ discovery --

    /** The address discovery settled on, tried first next launch. */
    var panelUrl: String
        get() = store.decodeString(KEY_PANEL_URL).orEmpty()
        set(value) { store.encode(KEY_PANEL_URL, value) }

    /** Highest bundle version verified so far. Never goes down. */
    var bundleVersion: Long
        get() = store.decodeLong(KEY_BUNDLE_VERSION, 0L)
        set(value) { if (value > bundleVersion) store.encode(KEY_BUNDLE_VERSION, value) }

    /**
     * The working source list, as JSON.
     *
     * Persisted because it is the self-repairing part: a bundle can retire a
     * mirror or add one, and that has to survive a restart or the app would
     * fall back to the list baked in at build time every launch.
     */
    var sourcesJson: String
        get() = store.decodeString(KEY_SOURCES).orEmpty()
        set(value) { store.encode(KEY_SOURCES, value) }

    /**
     * The addresses the newest verified bundle listed, as a JSON array.
     *
     * Separate from [panelUrl], which is the single address that last worked:
     * this is everything worth trying, and it outlives a domain that stops
     * answering. Without it, learning a new address would help exactly once —
     * until the app restarted and fell back to what the build shipped with.
     */
    var endpointsJson: String
        get() = store.decodeString(KEY_ENDPOINTS).orEmpty()
        set(value) { store.encode(KEY_ENDPOINTS, value) }
}
