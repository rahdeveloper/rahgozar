package com.rahgozar.app.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.service.SingBoxConfig.getAsJsonArray
import com.rahgozar.app.service.SingBoxConfig.getAsJsonObject

/**
 * Turns a server's sing-box configuration into one that can be measured.
 *
 * The same surgery `postProcessForSpeedtest` performs on an Xray config, in
 * the same spirit and for the same reason: what is being measured is *this
 * server*, so everything that could answer the probe from somewhere else has
 * to go. Inbounds are replaced with a local proxy and routing rules are
 * dropped, so nothing can be sent direct.
 *
 * What stays is what an outbound may *name*. sing-box resolves tags at
 * construction and fails the whole configuration on one it cannot find — an
 * outbound carrying `domain_resolver: "local"` against a `dns` block that was
 * dropped never starts, and the server would be reported dead when the fault
 * was ours (`common/dialer/dialer.go`: "domain resolver not found"). So `dns`,
 * `certificate` and `http_clients` are carried across, and only `dns.rules`
 * are dropped — rules can redirect, a server list cannot.
 *
 * The blob arrives in whatever shape the operator saved; [SingBoxConfig]
 * settles that first.
 */
object SingBoxTestConfig {

    private const val INBOUND_TAG = "rahgozar-test-in"

    /**
     * @param listenPort loopback port the mixed (SOCKS + HTTP) inbound listens on
     * @return a configuration whose only path out is the server's own outbound
     * @throws IllegalArgumentException if the blob is not a configuration this
     *   app can run — which must fail the measurement, never quietly measure
     *   something else
     */
    fun forDelayTest(config: String, listenPort: Int): String {
        val source = SingBoxConfig.normalize(config)
        val outbounds = source.getAsJsonArray("outbounds") ?: JsonArray()
        val endpoints = source.getAsJsonArray("endpoints")
        require(outbounds.size() > 0 || (endpoints != null && endpoints.size() > 0)) {
            "the sing-box configuration has nothing to measure through"
        }
        val target = SingBoxConfig.chooseTarget(outbounds, endpoints)

        val result = JsonObject()
        // To see why a measurement failed, add `"output"` here pointing at a
        // file under the test process's own directory and raise the level to
        // "trace"; the core's log is otherwise invisible, since nothing
        // subscribes to it. That is how the missing DNS transport was found.
        result.add("log", JsonObject().apply {
            addProperty("level", if (BuildConfig.DEBUG) "debug" else "warn")
            if (BuildConfig.DEBUG) addProperty("output", SingBoxNative.tunnelLogPath())
        })

        // Everything an outbound may name by tag. Dropping any of these turns
        // "this server is fine" into "the core refused to start".
        source.getAsJsonObject("dns")?.let { dns ->
            // The servers stay so `domain_resolver` and `dns.final` resolve;
            // the rules go for the same reason route.rules do.
            result.add("dns", dns.deepCopy().apply { remove("rules") })
        }
        source.get("certificate")?.let { result.add("certificate", it) }
        source.get("http_clients")?.let { result.add("http_clients", it) }

        result.add(
            "inbounds",
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "mixed")
                    addProperty("tag", INBOUND_TAG)
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", listenPort)
                })
            }
        )
        result.add("outbounds", outbounds)
        if (endpoints != null && endpoints.size() > 0) result.add("endpoints", endpoints)

        val route = JsonObject()
        // Off because there is no tunnel in this process to steer around, and
        // this app's own traffic is excluded from any tunnel it does run.
        route.addProperty("auto_detect_interface", false)
        target?.let { route.addProperty("final", it) }
        // The route-level defaults are the other half of the tag problem: an
        // outbound with no `domain_resolver` of its own falls back to these,
        // and a name that no longer resolves fails construction just the same.
        source.getAsJsonObject("route")?.let { sourceRoute ->
            sourceRoute.get("default_domain_resolver")?.let { route.add("default_domain_resolver", it) }
            sourceRoute.get("default_http_client")?.let { route.add("default_http_client", it) }
        }
        result.add("route", route)

        return result.toString()
    }

    /** @see SingBoxConfig.endpointOf */
    fun endpointOf(config: String): Pair<String, Int>? = SingBoxConfig.endpointOf(config)
}
