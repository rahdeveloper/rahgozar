package com.rahgozar.app.panel

import android.content.Context
import com.google.gson.Gson
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil

/**
 * Reads the discovery file baked into the build.
 *
 * This is the one piece of configuration that cannot come from the panel — the
 * app has to know where the panel is before it can ask it anything, and it has
 * to know which key to trust before it can believe the answer. Regenerate it
 * from the panel (کشف آدرس → دانلود discovery.json, or
 * `go run ./cmd/discoveryexport`) whenever the addresses or mirrors change.
 */
object DiscoveryAssets {

    const val FILE_NAME = "discovery.json"

    @Volatile
    private var cached: DiscoveryDocument? = null

    /** Null when the file is missing or unreadable — a build problem, not a runtime one. */
    fun load(context: Context): DiscoveryDocument? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read(context)?.also { cached = it }
        }
    }

    private fun read(context: Context): DiscoveryDocument? = try {
        context.assets.open(FILE_NAME).bufferedReader().use {
            Gson().fromJson(it, DiscoveryDocument::class.java)
        }
    } catch (e: Exception) {
        // Worth shouting about: an app shipped without this can never reach the
        // panel, and the symptom on a phone is silence.
        LogUtil.e(AppConfig.TAG, "panel: $FILE_NAME is missing or unreadable", e)
        null
    }
}
