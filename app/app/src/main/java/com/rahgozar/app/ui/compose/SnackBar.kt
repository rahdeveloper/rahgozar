package com.rahgozar.app.ui.compose

import com.rahgozar.app.ui.brand.ThemeMode
import com.rahgozar.app.ui.brand.BrandPalette
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.AppPreferences
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class ToastType {
    NORMAL, SUCCESS, ERROR, INFO
}

data class AppSnackbarMessage(
    val message: CharSequence,
    val type: ToastType = ToastType.NORMAL,
    val long: Boolean = false,
)

object AppSnackbarManager {
    private val _messages = MutableSharedFlow<AppSnackbarMessage>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages = _messages.asSharedFlow()

    fun hasActiveHost(): Boolean = _messages.subscriptionCount.value > 0

    fun show(
        message: CharSequence,
        type: ToastType = ToastType.NORMAL,
        long: Boolean = false,
    ): Boolean {
        if (!hasActiveHost()) return false
        return _messages.tryEmit(
            AppSnackbarMessage(
                message = message,
                type = type,
                long = long
            )
        )
    }
}

class AppSnackbarController(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    private var currentId = 0
    private var currentShowTime = 0L

    fun show(message: CharSequence, type: ToastType = ToastType.NORMAL, long: Boolean = false) {
        val id = ++currentId
        scope.launch {
            if (currentShowTime != 0L) {
                val elapsed = System.currentTimeMillis() - currentShowTime
                if (elapsed < SnackbarThrottleMs) {
                    delay((SnackbarThrottleMs - elapsed).milliseconds)
                }
            }

            hostState.currentSnackbarData?.dismiss()

            launch {
                hostState.showSnackbar(
                    AppSnackbarVisuals(
                        message = message.toString(),
                        type = type,
                        duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                )
                if (id == currentId) {
                    currentShowTime = 0L
                }
            }

            currentShowTime = System.currentTimeMillis()
        }
    }
}

private data class AppSnackbarVisuals(
    override val message: String,
    val type: ToastType,
    override val duration: SnackbarDuration,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false
) : SnackbarVisuals

val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarController> {
    error("AppSnackbarController not provided. Wrap your content in AppTheme.")
}

@Composable
fun rememberAppSnackbarController(): AppSnackbarController {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { AppSnackbarController(hostState, scope) }
}

@Composable
fun AppSnackbarBridge(
    controller: AppSnackbarController
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AppSnackbarManager.messages.collect { event ->
                controller.show(
                    message = event.message,
                    type = event.type,
                    long = event.long
                )
            }
        }
    }
}

private val ToastCornerRadius = 24.dp
private val ToastHorizontalPad = 16.dp
private val ToastVerticalPad = 12.dp
private const val ToastMaxLines = 8
private const val ToastMaxWidthFraction = 0.75f
private val ToastBottomOffset = 100.dp
private const val SnackbarThrottleMs = 2000L

@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val maxSnackbarWidth = maxWidth * ToastMaxWidthFraction
        val density = LocalDensity.current
        val navigationBarHeight = with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }

        SnackbarHost(
            hostState = hostState,
            modifier = Modifier.fillMaxSize()
        ) { data ->
            val type = (data.visuals as? AppSnackbarVisuals)?.type ?: ToastType.NORMAL

            // The palette is resolved here rather than taken from LocalPalette:
            // this host is mounted by AppTheme, which sits outside BrandTheme,
            // so the composition local would hand back the default instead of
            // the theme the user is actually looking at. Reading it per message
            // also means a toast raised right after a theme switch is drawn in
            // the new theme.
            val dark = when (AppPreferences.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val palette = if (dark) BrandPalette.Dark else BrandPalette.Light

            // The design has no toast, so this follows its other floating
            // surface — the drawer: a solid panel, a hairline edge, and a
            // coloured bar carrying the meaning instead of a coloured fill.
            val accent = when (type) {
                ToastType.NORMAL -> palette.dim
                ToastType.SUCCESS -> palette.accent
                ToastType.ERROR -> palette.danger
                ToastType.INFO -> palette.accent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = ToastBottomOffset + navigationBarHeight),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .wrapContentWidth()
                        .widthIn(max = maxSnackbarWidth)
                        .border(1.dp, palette.hair, RoundedCornerShape(ToastCornerRadius)),
                    shape = RoundedCornerShape(ToastCornerRadius),
                    color = palette.drawerBackground,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = ToastHorizontalPad,
                            vertical = ToastVerticalPad
                        ),
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(22.dp)
                                .background(accent)
                        )
                        Text(
                            text = data.visuals.message,
                            color = palette.text,
                            fontFamily = Brand.Vazirmatn,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            maxLines = ToastMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.wrapContentWidth()
                        )
                    }
                }
            }
        }
    }
}
