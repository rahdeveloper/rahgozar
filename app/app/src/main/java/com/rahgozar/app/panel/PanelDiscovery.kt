package com.rahgozar.app.panel

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rahgozar.app.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Finds an address the panel answers on.
 *
 * The app is useless without the panel — it is where servers, settings and the
 * gate come from — so a single blocked domain must not be the end of it. The
 * way out is a bundle the panel signs, listing where it can be reached, that
 * is published to several mirrors. Reaching any one live mirror is enough to
 * learn every address, including ones added after this build shipped.
 *
 * Nothing here decides what to trust: [BundleReader] does, and only an Ed25519
 * signature over the panel's own key counts. A block page, a captive portal
 * and a poisoned DNS answer are therefore identical to this code — rejected,
 * try the next source — with no attempt to *recognise* censorship, which is a
 * game that is lost within months.
 */
internal object PanelDiscovery {

    private const val TAG = "PanelDiscovery"

    /** Enough for a small JSON document; a mirror slower than this is no use. */
    private const val MAX_BODY_BYTES = 256L * 1024

    private val gson = Gson()

    /**
     * What discovery remembers between launches.
     *
     * An interface rather than a direct reach into [PanelStore] so the walk
     * across mirrors can be exercised without a device: MMKV is not available
     * to a plain unit test, and this is the part where a mistake means an app
     * that cannot find its panel.
     */
    interface Memory {
        val panelUrl: String
        var bundleVersion: Long
        var endpointsJson: String
        var sourcesJson: String
    }

    private object StoredMemory : Memory {
        override val panelUrl get() = PanelStore.panelUrl
        override var bundleVersion: Long
            get() = PanelStore.bundleVersion
            set(value) { PanelStore.bundleVersion = value }
        override var endpointsJson: String
            get() = PanelStore.endpointsJson
            set(value) { PanelStore.endpointsJson = value }
        override var sourcesJson: String
            get() = PanelStore.sourcesJson
            set(value) { PanelStore.sourcesJson = value }
    }

    /**
     * Addresses to try, best first.
     *
     * The one that worked last time leads, because on nearly every launch it
     * still works and nothing else needs to be touched. Behind it come the
     * addresses a bundle taught us, then the ones baked into the build.
     */
    fun candidates(discovery: DiscoveryDocument, memory: Memory = StoredMemory): List<String> {
        val out = LinkedHashSet<String>()
        memory.panelUrl.takeIf { it.isNotBlank() }?.let { out.add(it) }
        out.addAll(storedEndpoints(memory))
        out.addAll(discovery.directEndpoints.map { it.url })
        return out.filter { it.startsWith("https://") }
    }

    /**
     * Asks the mirrors where the panel is now.
     *
     * Sources are tried in priority order and the first bundle that verifies
     * *and* is newer than what we have wins; the rest are not fetched. On
     * success the new address list, the mirror list and the version are stored,
     * so the next launch starts from the better place even if this one goes on
     * to fail.
     *
     * @return the addresses to try, or an empty list when nothing verified
     */
    fun refresh(
        discovery: DiscoveryDocument,
        signPublicKey: ByteArray,
        nowSeconds: Long,
        memory: Memory = StoredMemory,
    ): List<String> {
        val sources = sourcesToTry(discovery, memory)
        if (sources.isEmpty()) return emptyList()

        val http = client(discovery.timeoutMs.toLong())
        val verified = ArrayList<EndpointBundle>()

        for (source in sources) {
            val document = fetch(http, source, discovery.dohResolvers) ?: continue

            when (val result = BundleReader.read(signPublicKey, document, nowSeconds)) {
                is BundleReader.Result.Rejected ->
                    // Expected on a censored network, and not worth a warning:
                    // this is what a block page looks like from here.
                    LogUtil.i(TAG, "discovery: ${source.key} rejected — ${result.reason}")

                is BundleReader.Result.Valid -> {
                    // Every mirror is asked, even after one answers.
                    //
                    // A CDN serving a copy we already have is the normal state
                    // — jsDelivr can hold `@main` for hours — and stopping
                    // there would hide a newer bundle sitting on the mirror
                    // behind it. That is precisely the moment this code exists
                    // for: the address is gone and the only way out is the
                    // newest list anyone still serves.
                    LogUtil.i(TAG, "discovery: ${source.key} served v${result.bundle.version}")
                    verified.add(result.bundle)
                }
            }
        }

        val best = BundleReader.best(verified, memory.bundleVersion)
        return when {
            best != null -> apply(best, discovery, memory)

            verified.isNotEmpty() -> {
                // Mirrors answered, but with nothing newer than what is stored.
                // The addresses in hand are all there is; the caller has tried
                // them already and will simply find nothing new to try.
                LogUtil.i(TAG, "discovery: nothing newer than v${memory.bundleVersion}")
                storedEndpoints(memory)
            }

            else -> {
                LogUtil.w(TAG, "discovery: no mirror served a usable bundle")
                emptyList()
            }
        }
    }

    /**
     * Records what a verified bundle taught us.
     *
     * The mirror list is merged rather than replaced, so a bundle can retire a
     * mirror without ever leaving an install with nowhere left to look — which
     * on a phone whose only other address is already blocked would be
     * unrecoverable. See [BundleReader.mergeSources].
     */
    private fun apply(
        bundle: EndpointBundle,
        discovery: DiscoveryDocument,
        memory: Memory,
    ): List<String> {
        val endpoints = bundle.endpoints
            .map { it.url }
            .filter { it.startsWith("https://") }

        memory.bundleVersion = bundle.version
        memory.endpointsJson = gson.toJson(endpoints)
        memory.sourcesJson = gson.toJson(
            BundleReader.mergeSources(bundle.sources, discovery.sources)
        )

        LogUtil.i(TAG, "discovery: v${bundle.version} applied, ${endpoints.size} address(es)")
        return endpoints
    }

    /** The stored list, then the baked one — never nothing. */
    private fun sourcesToTry(discovery: DiscoveryDocument, memory: Memory): List<DiscoverySource> {
        val stored = runCatching {
            gson.fromJson(memory.sourcesJson, Array<DiscoverySource>::class.java)?.toList()
        }.getOrNull().orEmpty()

        return BundleReader.mergeSources(stored, discovery.sources)
            .filter { it.isHttp && it.url.isNotBlank() || it.isDohTxt && it.domain.isNotBlank() }
            .sortedBy { it.priority }
    }

    private fun storedEndpoints(memory: Memory): List<String> = runCatching {
        gson.fromJson(memory.endpointsJson, Array<String>::class.java)?.toList()
    }.getOrNull().orEmpty()

    // --------------------------------------------------------------- fetch --

    private fun client(timeoutMs: Long) = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
        // A mirror that redirects is a mirror sending us somewhere we did not
        // choose. The signature would still have to pass, but there is no
        // reason to follow, and plenty of reasons not to.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun fetch(
        http: OkHttpClient,
        source: DiscoverySource,
        dohResolvers: List<String>,
    ): String? = when {
        source.isHttp -> get(http, source.url)
        source.isDohTxt -> txt(http, source, dohResolvers)
        else -> null
    }

    private fun get(http: OkHttpClient, url: String, accept: String? = null): String? = try {
        val request = Request.Builder().url(url)
            .apply { accept?.let { header("Accept", it) } }
            .build()
        http.newCall(request).execute().use { response ->
            // A body is read even on a non-200: some mirrors answer 403 with
            // the document, and the signature is the only thing that decides.
            response.body?.byteStream()?.let { stream ->
                String(stream.readNBytes(MAX_BODY_BYTES.toInt()), Charsets.UTF_8)
            }
        }
    } catch (e: Exception) {
        LogUtil.i(TAG, "discovery: $url unreachable — ${e.message}")
        null
    }

    /**
     * A bundle carried in a DNS TXT record, fetched over DoH.
     *
     * Worth the extra shape because it survives what plain HTTPS does not: the
     * answer comes from a resolver the network cannot easily distinguish from
     * ordinary DNS traffic, and the record itself lives on a domain that need
     * not host anything.
     */
    private fun txt(
        http: OkHttpClient,
        source: DiscoverySource,
        dohResolvers: List<String>,
    ): String? {
        for (resolver in dohResolvers) {
            val separator = if (resolver.contains('?')) "&" else "?"
            val url = "$resolver${separator}name=${source.txtName}&type=TXT"
            val body = get(http, url, accept = "application/dns-json") ?: continue

            val answers = runCatching { gson.fromJson(body, DohResponse::class.java) }
                .getOrNull()?.answer.orEmpty()
                .filter { it.type == TXT_RECORD }
                .map { it.data }
            if (answers.isEmpty()) continue

            return BundleReader.joinTxtParts(answers)
        }
        return null
    }

    private const val TXT_RECORD = 16

    private data class DohResponse(@SerializedName("Answer") val answer: List<DohAnswer>? = null)

    private data class DohAnswer(
        @SerializedName("type") val type: Int = 0,
        @SerializedName("data") val data: String = "",
    )
}
