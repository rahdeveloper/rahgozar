package com.rahgozar.app.panel

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rahgozar.app.AppConfig
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.dto.entities.ProfileItem
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.root.RootManager
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One launch's worth of talking to the panel.
 *
 * Register if needed, bootstrap, apply the panel's client-side switches, and
 * replace the local server list with what arrived. Everything the app shows is
 * downstream of this call — there is no other way for a configuration to enter.
 *
 * Failure is normal here, not exceptional. The panel's address is expected to
 * be filtered sometimes, so a failed sync leaves the previous configuration in
 * place and reports why; it never clears what the device already has.
 */
object PanelSync {

    private const val TAG = AppConfig.TAG

    /**
     * Bumped whenever a build starts storing something new out of the
     * bootstrap, so upgraded devices skip the 304 once and re-apply in full.
     * 1 = servers/settings/ads; 2 = the smart profile and the geo verdict;
     * 3 = the full smart candidate list.
     */
    internal const val APPLIED_SCHEMA = 3

    /**
     * Where the sync has got to.
     *
     * Emitted so the splash can show real progress instead of a timer running
     * next to work it knows nothing about. A three-second animation over a
     * request that took four hundred milliseconds is a lie the user can feel,
     * and one over a request that stalled is worse.
     */
    enum class Stage {
        /** Reading the baked discovery file and deciding where to talk. */
        RESOLVING,

        /** No usable token: introducing this device to the panel. */
        REGISTERING,

        /** Asking for the configuration. */
        FETCHING,

        /** Decrypting and writing the server list. */
        APPLYING,

        DONE,
        FAILED,
    }

    sealed interface Result {
        /** Configuration is current. [changed] is false when the panel returned 304. */
        data class Ready(val changed: Boolean, val settings: PanelSettings, val ads: AdsConfig) : Result

        /** The panel's gate refused this device. Nothing was applied. */
        data class Blocked(val decision: PanelGate.Decision, val settings: PanelSettings) : Result

        /**
         * Could not reach or verify the panel. Previous configuration stands.
         *
         * @param answered true when the panel itself replied and refused —
         *   a rejected token, say. Another address is the same panel, so it
         *   would refuse identically; only an address that never answered as
         *   the panel is worth replacing.
         */
        data class Unavailable(
            val reason: String,
            val fatal: Boolean = false,
            val answered: Boolean = false,
        ) : Result
    }

    /**
     * @param nowSeconds injected so the token and sync-interval arithmetic can
     *   be tested without waiting for a clock.
     */
    /**
     * The configuration already on the device, without asking the panel.
     *
     * The same replay a 304 uses. It exists so a launch that should not sync —
     * one where this process has already synced, or where a tunnel is up — can
     * still apply the settings and ad configuration it would otherwise get,
     * and open the app straight away.
     *
     * @return null when the device has never completed a sync
     */
    fun storedConfiguration(): Result.Ready? {
        if (PanelStore.lastSyncAt <= 0L || PanelStore.settingsJson.isEmpty()) return null
        val cached = cachedConfig()
        return Result.Ready(changed = false, cached.first, cached.second)
    }

    /**
     * Re-derives the panel's verdict when the stored one has gone stale.
     *
     * The splash is the only thing that syncs, and it skips itself whenever a
     * tunnel is already running — which is most launches, because the tunnel
     * outlives the UI process. So a device can run for days on a cached
     * answer, and one of the things that answer decides is whether ads need
     * the smart tunnel at all. A user who has travelled, or an operator who
     * has changed the policy, would keep getting the old verdict, and if the
     * old verdict was "no tunnel needed" the ads that follow go out on the
     * user's own address.
     *
     * Called when a tunnel comes down, which is the only moment this can be
     * asked honestly: through a tunnel the panel would see the exit address
     * and answer for the wrong country. Does nothing when a sync is not due,
     * so the ordinary case costs one comparison.
     *
     * @return the fresh configuration when one was fetched, else null.
     */
    suspend fun refreshIfDue(context: Context): Result.Ready? {
        if (TunnelState.isRunning(context)) return null
        val interval = PanelSettings.parse(PanelStore.settingsJson).syncIntervalMinutes
        if (!PanelStore.isSyncDue(System.currentTimeMillis() / 1000, interval)) return null

        LogUtil.i(TAG, "panel: configuration is stale, refreshing it now the tunnel is down")
        val result = runCatching { run(context) }.getOrNull()
        return (result as? Result.Ready)?.also {
            TunnelSettings.apply(it.settings)
            AdManager.apply(context, it.ads)
        }
    }

    suspend fun run(
        context: Context,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        force: Boolean = false,
        onStage: (Stage) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        onStage(Stage.RESOLVING)
        val discovery = DiscoveryAssets.load(context)
            ?: run {
                onStage(Stage.FAILED)
                return@withContext Result.Unavailable(
                    "discovery.json is missing from the build", fatal = true,
                )
            }

        val signKey = Base64Url.decodeOrNull(discovery.signPublicKey)
        val exchKey = Base64Url.decodeOrNull(discovery.exchPublicKey)
        if (signKey == null || exchKey == null || signKey.size != 32 || exchKey.size != 32) {
            onStage(Stage.FAILED)
            return@withContext Result.Unavailable("discovery.json has unusable keys", fatal = true)
        }

        val client = PanelClient(signKey, exchKey, timeoutMs = discovery.timeoutMs.toLong())
        val device = PanelStore.deviceKeyPair()

        // Every address worth trying, best first — the one that worked last
        // time, then whatever a bundle taught us, then the baked list.
        val known = PanelDiscovery.candidates(discovery)
        var outcome = attempt(context, client, device, known, nowSeconds, force, onStage)

        // Only a *verified* answer ends the search.
        //
        // Ready and Blocked both came out of a document signed with the panel's
        // key, so they are the panel speaking and there is nothing to look for.
        // Every other outcome is unverified by definition — including the ones
        // that look conclusive, because "401" and an error code are read from
        // a plain HTTP response that no signature covers. Anything holding a
        // certificate for this hostname could produce one, and treating it as
        // final would let a single intercepted address pin the app there for
        // good. So the mirrors get asked. On a device the panel really has
        // refused this costs one request: the first mirror is the panel's own
        // endpoint, it serves the version already known, and no address is
        // retried because none is new.
        if (outcome is Result.Unavailable) {
            onStage(Stage.RESOLVING)
            val discovered = PanelDiscovery.refresh(discovery, signKey, nowSeconds) - known.toSet()
            if (discovered.isNotEmpty()) {
                LogUtil.i(TAG, "panel: ${discovered.size} new address(es) from discovery")
                outcome = attempt(context, client, device, discovered, nowSeconds, force, onStage)
            }
        }

        if (outcome is Result.Unavailable) onStage(Stage.FAILED)
        outcome
    }

    /**
     * Tries each address until one completes a sync.
     *
     * Moving on is only right when an address failed to *answer as the panel*:
     * an unreachable host, a block page, a signature that did not verify. A
     * panel that answers and refuses — a blocked device, an unregistered build
     * — has given a real answer, and asking a different address the same
     * question would only get the same refusal, slower.
     */
    private suspend fun attempt(
        context: Context,
        client: PanelClient,
        device: DeviceKeyPair,
        urls: List<String>,
        nowSeconds: Long,
        force: Boolean,
        onStage: (Stage) -> Unit,
    ): Result {
        if (urls.isEmpty()) {
            return Result.Unavailable("no panel address to try", fatal = true)
        }

        var last: Result = Result.Unavailable("no panel address to try")
        for (url in urls) {
            val result = syncWith(context, client, device, url, nowSeconds, force, onStage)
            if (!worthAnotherAddress(result)) return result
            LogUtil.w(TAG, "panel: $url did not answer — ${(result as Result.Unavailable).reason}")
            last = result
        }
        return last
    }

    /**
     * Whether the next address is worth asking after this outcome.
     *
     * The rule the address walk turns on, so it is stated once and tested on
     * its own: only an address that failed to answer *as the panel* may be
     * replaced. A device the panel refuses would otherwise ask every address
     * in turn for the same refusal, turning one clear answer into a slow one.
     *
     * Note what this does **not** decide: whether to ask the mirrors. That is
     * a separate question with a stricter test, because a refusal read off an
     * unsigned HTTP response is not proof of anything. See [run].
     */
    internal fun worthAnotherAddress(result: Result): Boolean =
        result is Result.Unavailable && !result.fatal && !result.answered

    private suspend fun syncWith(
        context: Context,
        client: PanelClient,
        device: DeviceKeyPair,
        url: String,
        nowSeconds: Long,
        force: Boolean,
        onStage: (Stage) -> Unit,
        alreadyRetriedRateLimit: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!PanelStore.hasUsableToken(nowSeconds)) {
                onStage(Stage.REGISTERING)
                val registration = client.register(url, device, registerRequest(context))
                PanelStore.token = registration.token
                PanelStore.tokenExpiresAt = registration.expiresAt
                PanelStore.deviceId = registration.deviceId
                // A fresh registration invalidates any cached ETag: the new
                // device has never been told anything.
                PanelStore.etag = ""
                LogUtil.i(TAG, "panel: registered as ${registration.deviceId}")
            }

            onStage(Stage.FETCHING)
            // An old-schema device must take the full response even when the
            // panel's content has not changed — the 304 replay can only hand
            // back what a previous build thought worth storing.
            val etag = if (force || PanelStore.appliedSchema < APPLIED_SCHEMA) "" else PanelStore.etag
            val result = client.bootstrap(url, PanelStore.token, device, etag)

            PanelStore.panelUrl = url
            PanelStore.lastSyncAt = nowSeconds

            if (result.notModified) {
                // 304 carries no body. Answering with empty settings here would
                // switch ads off and clear every panel switch on the most
                // common launch there is, so the last response is replayed.
                val cached = cachedConfig()
                LogUtil.i(TAG, "panel: configuration unchanged (v${PanelStore.configVersion})")
                onStage(Stage.DONE)
                return@withContext Result.Ready(changed = false, cached.first, cached.second)
            }

            val settings = result.settings
            val gate = PanelGate.evaluate(settings, BuildConfig.VERSION_CODE, isRooted(settings))
            if (gate != PanelGate.Decision.Allow) {
                // Nothing is applied. A device that must update, or that the
                // panel will not serve, keeps whatever it had rather than being
                // handed a fresh list it is not allowed to use.
                LogUtil.w(TAG, "panel: refused by gate — $gate")
                onStage(Stage.FAILED)
                return@withContext Result.Blocked(gate, settings)
            }

            onStage(Stage.APPLYING)
            applyServers(result.serversJson)
            applySmart(result.smartJson)
            // Only a full response carries the geo verdict; a 304 never gets
            // here, so the last verdict simply stands until the panel speaks.
            PanelStore.smartRequired = result.smartRequired
            PanelStore.etag = result.etag.orEmpty()
            PanelStore.appliedSchema = APPLIED_SCHEMA
            PanelStore.configVersion = result.bootstrap?.configVersion ?: 0
            // Kept so the next launch can answer a 304 without losing them.
            PanelStore.settingsJson = result.bootstrap?.settings?.toString().orEmpty()
            PanelStore.adsJson = result.bootstrap?.ads?.toString().orEmpty()

            LogUtil.i(TAG, "panel: applied config v${PanelStore.configVersion}")
            onStage(Stage.DONE)
            Result.Ready(changed = true, settings, result.ads)
        } catch (e: PanelException) {
            when {
                // The panel is shedding load, which on the addresses this app
                // lives behind means a carrier-NAT pool registering too many
                // installs at once — not a panel that is down. It named a
                // wait, so wait it out once and ask the *same* address again.
                //
                // Giving up must not amplify. The failure this replaces did:
                // a 429 read as "unreachable", so the walk asked every other
                // address — the same panel, the same limiter, another token of
                // the same shared budget spent per address — and then fired
                // the whole discovery machinery. `answered = true` is what
                // stops that: a refusal from the panel is the panel speaking.
                e.isRateLimited -> {
                    val wait = rateLimitWaitMillis(e.retryAfterSeconds, alreadyRetriedRateLimit)
                    if (wait != null) {
                        LogUtil.w(TAG, "panel: rate limited, retrying once in ${wait}ms")
                        delay(wait)
                        return@withContext syncWith(
                            context, client, device, url, nowSeconds, force, onStage,
                            alreadyRetriedRateLimit = true,
                        )
                    }
                    LogUtil.w(TAG, "panel: still rate limited — keeping the stored configuration")
                    Result.Unavailable("the panel is shedding load", answered = true)
                }
                // The token went stale or the device row is gone. Drop it so
                // the next launch registers again — the app's only recovery.
                e.needsRegistration -> {
                    PanelStore.clearRegistration()
                    LogUtil.w(TAG, "panel: token rejected, will register again")
                    Result.Unavailable("registration expired", answered = true)
                }
                e.isBlocked -> Result.Unavailable("this device is blocked", fatal = true)
                e.isAttestationFailure ->
                    Result.Unavailable("this build is not registered with the panel", fatal = true)
                else -> Result.Unavailable(e.message ?: "panel unreachable")
            }
        } catch (e: Exception) {
            LogUtil.i(TAG, "panel: $url failed — ${e.message}")
            Result.Unavailable(e.message ?: "sync failed")
        }
    }

    /**
     * How long a rate-limited sync waits before its one retry, or null when
     * giving up is right.
     *
     * Every rule here exists to keep a crowd from synchronising itself:
     *
     *  - **One retry.** The panel refills a shared address's budget at a fixed
     *    rate; a client that keeps retrying inside one launch spends that
     *    refill on a user who is already staring at the splash, instead of on
     *    the next user's first attempt.
     *  - **Only when the panel named a wait.** A 429 without Retry-After is
     *    something older or stranger than our panel, and guessing a wait
     *    against an unknown limiter is how retry storms start.
     *  - **Capped.** Past [RATE_LIMIT_WAIT_CAP_SECONDS] the honest move is to
     *    open the app on the stored configuration — the sync machinery treats
     *    failure as normal by design — rather than hold the splash hostage.
     *  - **Jittered.** The devices behind one carrier address that were
     *    refused in the same instant were told the same wait; the jitter is
     *    what keeps their retries from arriving as the same stampede that got
     *    them refused.
     */
    internal fun rateLimitWaitMillis(
        retryAfterSeconds: Long?,
        alreadyRetried: Boolean,
        jitterMillis: Long = kotlin.random.Random.nextLong(RATE_LIMIT_JITTER_MS),
    ): Long? {
        if (alreadyRetried) return null
        val seconds = retryAfterSeconds ?: return null
        if (seconds < 0 || seconds > RATE_LIMIT_WAIT_CAP_SECONDS) return null
        return seconds * 1000 + jitterMillis
    }

    /** Longest wait worth holding the splash for; past this the stored config wins. */
    internal const val RATE_LIMIT_WAIT_CAP_SECONDS = 8L

    /** Spread added to every rate-limit retry, so refused crowds de-synchronise. */
    internal const val RATE_LIMIT_JITTER_MS = 1_000L

    /** The last full response, for launches the panel answers with a 304. */
    private fun cachedConfig(): Pair<PanelSettings, AdsConfig> =
        PanelSettings.parse(PanelStore.settingsJson) to AdsConfig.parse(PanelStore.adsJson)

    /**
     * Only probes for root when the panel actually asks.
     *
     * The probe touches the filesystem and can spawn a shell; running it on a
     * launch where the answer would be ignored is cost for nothing.
     */
    private fun isRooted(settings: PanelSettings): Boolean =
        settings.blockRootedDevices && RootManager.isRootAvailable()

    private fun registerRequest(context: Context) = PanelClient.RegisterRequest(
        // Overwritten by the client with the real public key; the field exists
        // so the request shape is complete in one place.
        publicKey = "",
        packageName = context.packageName,
        certSha256 = AppSignature.primaryCertificateHash(context).orEmpty(),
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        androidSdk = Build.VERSION.SDK_INT,
    )

    // ------------------------------------------------------------- servers --

    private data class PanelServer(
        @SerializedName("id") val id: String = "",
        @SerializedName("protocol") val protocol: String = "",
        @SerializedName("remarks") val remarks: String = "",
        @SerializedName("description") val description: String = "",
        @SerializedName("country") val country: String = "",
        @SerializedName("sort") val sort: Int = 0,
        @SerializedName("config") val config: ProfileItem? = null,
    )

    /**
     * Replaces the local list with the panel's.
     *
     * A replace, not a merge: the panel is the only source, so anything on the
     * device that is not in this list is a server that was retired, and keeping
     * it would mean a removed server stays reachable forever.
     *
     * The panel's `id` becomes the local guid, so a server keeps its selection
     * and its measured latency across syncs instead of looking new every time.
     */
    private fun applyServers(json: String?) {
        if (json == null) return
        val servers = try {
            Gson().fromJson(json, Array<PanelServer>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            LogUtil.e(TAG, "panel: server list did not parse", e)
            return
        }

        val kept = mutableListOf<String>()
        val countries = mutableMapOf<String, String>()
        for (entry in servers.sortedBy { it.sort }) {
            val profile = entry.config ?: continue
            if (entry.id.isEmpty()) continue
            profile.remarks = entry.remarks.ifEmpty { profile.remarks }
            profile.subscriptionId = ""
            MmkvManager.encodeServerConfig(entry.id, profile)
            if (entry.country.isNotEmpty()) countries[entry.id] = entry.country.uppercase()
            kept.add(entry.id)
        }

        for (guid in MmkvManager.decodeAllServerList().toList()) {
            if (guid !in kept) MmkvManager.removeServer(guid)
        }
        MmkvManager.encodeServerList(kept.toMutableList(), "")

        // A selection pointing at a server that is no longer in the list has to
        // be cleared, or the app points at a ghost. But nothing is chosen in
        // its place: picking one for the user is a guess, and if it guesses a
        // server that is down, the user taps connect, fails, and concludes the
        // app is broken. An empty selection is honest and the home screen says
        // so plainly.
        val selected = MmkvManager.getSelectServer()
        if (!selected.isNullOrEmpty() && selected !in kept) {
            MmkvManager.setSelectServer("")
        }
        PanelStore.serverCountries = Gson().toJson(countries)
        LogUtil.i(TAG, "panel: ${kept.size} servers applied")
    }

    // --------------------------------------------------------------- smart --

    /**
     * Stores every Smart tunnel candidate, or retires them all.
     *
     * Smart servers are not user servers: they exist so ad traffic can leave
     * through a working path before AdMob is asked for a fill. All of them
     * are kept, in the panel's order, as hidden profiles under
     * `panel-smart-<index>` — never added to the visible list, and
     * [applyServers]' cleanup never touches them because that walk only
     * visits guids that are *in* the list. Keeping the whole set is what lets
     * the ad flow measure them and connect to one that actually answers,
     * instead of being married to whichever the panel listed first.
     *
     * The panel omits the smart box entirely when it has no smart servers, so
     * on a full response `null` means retired — keeping old profiles would
     * keep pointing ad traffic at tunnels the operator took down. A list that
     * arrives but does not parse keeps the previous profiles, same as
     * [applyServers].
     */
    private fun applySmart(json: String?) {
        val previous = smartGuids(PanelStore.smartGuidsJson)
        if (json == null) {
            clearSmart(previous)
            return
        }
        val profiles = pickSmartProfiles(json) ?: return
        if (profiles.isEmpty()) {
            clearSmart(previous)
            return
        }

        val guids = profiles.indices.map { AppConfig.SMART_PROFILE_PREFIX + it }
        profiles.forEachIndexed { index, profile ->
            MmkvManager.encodeServerConfig(guids[index], profile)
        }
        // A shrinking list must retire its tail, and the one-profile guid of
        // older builds goes with it.
        for (stale in (previous + AppConfig.LEGACY_SMART_PROFILE_GUID) - guids.toSet()) {
            MmkvManager.removeServer(stale)
        }
        PanelStore.smartGuidsJson = Gson().toJson(guids)
        LogUtil.i(TAG, "panel: ${guids.size} smart candidate(s) stored")
    }

    private fun clearSmart(previous: List<String>) {
        for (guid in previous + AppConfig.LEGACY_SMART_PROFILE_GUID) {
            MmkvManager.removeServer(guid)
        }
        PanelStore.smartGuidsJson = ""
    }

    /** The stored candidate guids, tolerating an empty or malformed value. */
    internal fun smartGuids(json: String): List<String> = runCatching {
        Gson().fromJson(json, Array<String>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Every runnable smart profile, in the panel's order.
     *
     * Internal so the rules — sorted by the panel's `sort`, entries without a
     * config skipped, panel remarks win — can be tested without MMKV. Null
     * means the list did not parse, which callers treat as "keep what you
     * have"; an empty list is a real answer meaning "there are none".
     */
    internal fun pickSmartProfiles(json: String): List<ProfileItem>? {
        val servers = try {
            Gson().fromJson(json, Array<PanelServer>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            LogUtil.e(TAG, "panel: smart list did not parse", e)
            return null
        }
        return servers.sortedBy { it.sort }.mapNotNull { entry ->
            entry.config?.also {
                it.remarks = entry.remarks.ifEmpty { it.remarks }
                it.subscriptionId = ""
            }
        }
    }
}
