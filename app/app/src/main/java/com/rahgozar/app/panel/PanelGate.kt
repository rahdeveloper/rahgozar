package com.rahgozar.app.panel

/**
 * The panel's client-side switches, applied on every launch.
 *
 * Some rules the panel cannot enforce from its side — it cannot tell whether a
 * device is rooted, and it cannot stop an old build from running. Those are
 * decided here, from the settings that arrived in the bootstrap response, so
 * an operator can flip them in the panel and every device honours it on its
 * next launch without an app release.
 *
 * Kept free of Android types so the rules can be unit-tested directly. The
 * caller supplies what it observed; this decides what happens.
 */
object PanelGate {

    sealed interface Decision {
        /** Carry on. */
        data object Allow : Decision

        /** Stop, and tell the user to update. Retrying will not help. */
        data class UpdateRequired(val minVersionCode: Int, val currentVersionCode: Int) : Decision

        /** Stop: the panel does not serve rooted devices. */
        data object RootBlocked : Decision
    }

    /**
     * @param settings what the panel sent on this launch.
     * @param versionCode this build.
     * @param isRooted what the root probe found. Pass the *observed* value —
     *   this function does not probe, so a caller that skips the check cannot
     *   accidentally look like a clean device to a reader of this code.
     */
    fun evaluate(settings: PanelSettings, versionCode: Int, isRooted: Boolean): Decision {
        // Update first. A build that is too old may not understand the rest of
        // the configuration correctly, so telling the user to update beats
        // acting on rules it may be misreading.
        if (settings.forceUpdate && versionCode < settings.minVersionCode) {
            return Decision.UpdateRequired(settings.minVersionCode, versionCode)
        }
        if (settings.blockRootedDevices && isRooted) {
            return Decision.RootBlocked
        }
        return Decision.Allow
    }

    /**
     * Whether an update is *available* but not mandatory.
     *
     * Separate from [evaluate] because the two are different products: this one
     * is a dismissible prompt, the other stops the app. The panel expresses the
     * difference with `force_update`.
     */
    fun updateSuggested(settings: PanelSettings, versionCode: Int): Boolean =
        !settings.forceUpdate && versionCode < settings.minVersionCode
}
