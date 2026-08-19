package com.rahgozar.app.ui.connect

/**
 * Where a connect tap has got to, as [ConnectingScreen] draws it.
 *
 * The stages are the *app's* real steps, not a decorative sequence: the ad
 * flow's tunnel has to be taken down before the user's can come up, the core
 * has to start, and the verification gate has to see one real answer come
 * back. Each of those is seconds long, so a screen that narrates them is
 * telling the truth about the wait rather than filling it.
 *
 * [PREPARING] is entered the moment the last full-screen ad of the tap is
 * dismissed. That ordering is the whole point of this screen — see
 * `MainActivity.beginConnect`.
 */
enum class ConnectStage {
    /**
     * The ad flow's tunnel is being drained and taken down. Nothing of the
     * user's is connecting yet, and the wording must not claim otherwise.
     */
    PREPARING,

    /** The user's own core is starting, and its first answer is being waited for. */
    DIALLING,

    /** It came up. The screen says so and hands back to the home screen. */
    DONE,

    /** It did not. The screen says so and waits for the user to leave it. */
    FAILED,
}
