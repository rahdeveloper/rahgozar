package com.rahgozar.app.extension

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.ui.compose.AppSnackbarManager
import com.rahgozar.app.ui.compose.ToastType

/**
 * Resolves a string in the language the app is set to.
 *
 * Messages are usually raised on the Application context, or from a service in
 * the daemon process, and neither is wrapped with the app's locale — so every
 * one of them came out in the phone's language regardless of the FA/EN switch.
 * Doing the lookup here fixes all of them at once rather than asking every call
 * site to remember.
 *
 * Falls back to the plain context if the preference cannot be read, which can
 * happen very early in a process before MMKV is up. A message in the wrong
 * language beats a crash while reporting an error.
 */
private fun Context.localised(message: Int): CharSequence =
    runCatching {
        val config = Configuration(resources.configuration).apply {
            setLocale(SettingsManager.getLocale())
        }
        createConfigurationContext(config).getString(message)
    }.getOrElse { getString(message) }

/**
 * Shows a toast message with the given resource ID.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toast(message: Int) {
    val text = localised(message)
    dispatchMessage(text, ToastType.NORMAL) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toast(message: CharSequence) {
    dispatchMessage(message, ToastType.NORMAL) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows a toast message with the given resource ID.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toastSuccess(message: Int) {
    val text = localised(message)
    dispatchMessage(text, ToastType.SUCCESS) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toastSuccess(message: CharSequence) {
    dispatchMessage(message, ToastType.SUCCESS) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows a toast message with the given resource ID.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toastError(message: Int) {
    val text = localised(message)
    dispatchMessage(text, ToastType.ERROR) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toastError(message: CharSequence) {
    dispatchMessage(message, ToastType.ERROR) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows an info toast message with the given resource ID.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toastInfo(message: Int) {
    val text = localised(message)
    dispatchMessage(text, ToastType.INFO) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows an info toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toastInfo(message: CharSequence) {
    dispatchMessage(message, ToastType.INFO) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private inline fun runOnMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        Handler(Looper.getMainLooper()).post { block() }
    }
}

private inline fun dispatchMessage(
    message: CharSequence,
    type: ToastType,
    long: Boolean = false,
    crossinline fallback: () -> Unit
) {
    val handledBySnackbar = AppSnackbarManager.show(
        message = message,
        type = type,
        long = long
    )
    if (!handledBySnackbar) {
        runOnMain { fallback() }
    }
}
