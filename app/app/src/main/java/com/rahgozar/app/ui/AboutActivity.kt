package com.rahgozar.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.rahgozar.app.Branding
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.R
import com.rahgozar.app.core.CoreNativeManager
import com.rahgozar.app.ui.base.BaseComponentActivity
import com.rahgozar.app.ui.compose.AppTopBar
import com.rahgozar.app.ui.compose.SettingsMenuItem
import com.rahgozar.app.ui.compose.VersionInfoBlock

class AboutActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        AboutScreen(onBackClick = { finish() })
    }
}

/**
 * What this build is: its version, the core underneath it, and the licences of
 * everything it is made of.
 *
 * Deliberately *only* that. The panel's links — privacy, terms, about us,
 * support, FAQ — are one tap away in the drawer this screen is opened from,
 * and a second copy of them here would be two places to keep straight for no
 * gain. It used to render them from [AppLinksManager], which nothing has
 * written to since the panel sync began storing links elsewhere, so the list
 * had been silently empty rather than duplicated.
 */
@Composable
fun AboutScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    var showOssDialog by remember { mutableStateOf(false) }

    val versionText = "v${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"
    val appIdText = BuildConfig.APPLICATION_ID

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_about),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // The GPL obligation, made reachable. A licence notice that names
            // the source without saying where it is satisfies nobody.
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = stringResource(R.string.title_source_code),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Branding.SOURCE_URL.toUri())
                        )
                    }
                }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.license_24px),
                title = stringResource(R.string.title_oss_license),
                onClick = { showOssDialog = true }
            )
            VersionInfoBlock(
                versionText = versionText,
                appIdText = appIdText
            )
        }
    }

    if (showOssDialog) {
        AlertDialog(
            onDismissRequest = { showOssDialog = false },
            title = { Text(stringResource(R.string.title_oss_license)) },
            text = {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            loadUrl("file:///android_asset/open_source_licenses.html")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showOssDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(bottom = 60.dp)
        )
    }
}
