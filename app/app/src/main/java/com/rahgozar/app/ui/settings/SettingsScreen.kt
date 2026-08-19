package com.rahgozar.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.home.AppTopBar

/** One row of the settings list. */
sealed interface SettingsRow {

    val key: String

    /** A switch. */
    data class Toggle(
        override val key: String,
        val label: String,
        val value: Boolean,
        val hint: String = "",
        val enabled: Boolean = true,
    ) : SettingsRow

    /** A row that opens somewhere else, or cycles through a short list of values. */
    data class Link(
        override val key: String,
        val label: String,
        val valueLabel: String = "",
        val enabled: Boolean = true,
    ) : SettingsRow
}

/** A titled group of rows. */
data class SettingsSection(
    val title: String,
    val rows: List<SettingsRow>,
    val note: String = "",
)

/**
 * Settings, in the redesign's language.
 *
 * Only the phone's owner's own preferences live here: how the app looks, how it
 * behaves when they tap it, and which apps go through the tunnel. The v2ray core
 * options this screen used to carry — MUX, fragment, IPv6, sniffing, log level,
 * DNS, SOCKS — belong to the panel now. They are the settings where a wrong
 * value produces a connection that half works, and the operator watching the
 * servers is in a far better position to choose them than the user is.
 *
 * Nothing here can reach a server configuration: no import, no editor, no
 * export. See docs/SECURITY.md.
 */
@Composable
fun SettingsScreen(
    sections: List<SettingsSection>,
    onToggle: (String, Boolean) -> Unit,
    onLink: (String) -> Unit,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val palette = LocalPalette.current

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AppTopBar(
            tag = "SETTINGS",
            title = "Settings",
            onMenu = onBack,
            onToggleTheme = onToggleTheme,
            onBack = onBack,
        )

        Column(Modifier.verticalScroll(rememberScrollState())) {
            sections.forEach { section ->
                SectionHeader(section.title)

                val note = section.note
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        style = TextStyle(
                            fontFamily = Brand.Vazirmatn,
                            fontSize = 11.5.sp,
                            color = palette.dim,
                        ),
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }

                section.rows.forEach { row ->
                    when (row) {
                        is SettingsRow.Toggle -> ToggleRow(row, onToggle)
                        is SettingsRow.Link -> LinkRow(row, onLink)
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
                color = palette.accent,
            ),
        )
        Box(Modifier.weight(1f).height(1.dp).background(palette.hair))
    }
}

@Composable
private fun ToggleRow(setting: SettingsRow.Toggle, onToggle: (String, Boolean) -> Unit) {
    val palette = LocalPalette.current
    val hint = setting.hint

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = setting.enabled) { onToggle(setting.key, !setting.value) }
            .alpha(if (setting.enabled) 1f else 0.45f)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                setting.label,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.5.sp,
                    color = palette.text,
                ),
            )
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    hint,
                    style = TextStyle(
                        fontFamily = Brand.Vazirmatn,
                        fontSize = 11.sp,
                        color = palette.dim,
                    ),
                )
            }
        }
        Switch(setting.value)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
}

/** The design's switch: a track that fills with the accent, no material chrome. */
@Composable
private fun Switch(on: Boolean) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(if (on) palette.accent else palette.trackOff),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) palette.onAccent else palette.knobOff)
        )
    }
}

@Composable
private fun LinkRow(setting: SettingsRow.Link, onLink: (String) -> Unit) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = setting.enabled) { onLink(setting.key) }
            .alpha(if (setting.enabled) 1f else 0.45f)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            setting.label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                color = palette.text,
            ),
            modifier = Modifier.weight(1f),
        )
        if (setting.valueLabel.isNotEmpty()) {
            Text(
                setting.valueLabel,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 12.sp,
                    color = palette.dim,
                ),
            )
        }
        Text(
            "›",
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontSize = 16.sp,
                color = palette.dim,
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
}
