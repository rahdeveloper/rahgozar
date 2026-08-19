package com.rahgozar.app.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.countryFlag
import com.rahgozar.app.ui.home.AppTopBar
import com.rahgozar.app.ui.home.pingColorPublic

/** One row in the list. */
data class ServerRow(
    val guid: String,
    val name: String,
    /**
     * Held for searching only, never drawn. Host and port are the credential:
     * on screen they make any screenshot a working address.
     */
    val address: String,
    val country: String,
    val protocol: String,
    val pingMs: Long,
    val selected: Boolean,
    /** True only for the row being measured right now. */
    val testing: Boolean = false,
)

/**
 * Choosing a server, and nothing else.
 *
 * The design's version of this screen had add, edit, delete and a sort menu.
 * None of them are here: the list is what the panel sent, so the only verb that
 * makes sense is "use this one". Search stays, because a list of eighty servers
 * needs it and searching cannot leak anything.
 */
@Composable
fun ServerListScreen(
    rows: List<ServerRow>,
    testing: Boolean,
    onSelect: (ServerRow) -> Unit,
    onTestAll: () -> Unit,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val palette = LocalPalette.current
    var query by remember { mutableStateOf("") }

    val visible = remember(rows, query) {
        if (query.isBlank()) {
            rows
        } else {
            rows.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.country.contains(query, ignoreCase = true) ||
                    it.protocol.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AppTopBar(
            tag = "SERVERS",
            title = "Servers",
            onMenu = onBack,
            onToggleTheme = onToggleTheme,
            onBack = onBack,
        )

        SearchField(query, { query = it })

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${visible.size} SERVERS",
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.7.sp,
                    color = palette.dim,
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    testing -> "TESTING…"
                    else -> "TEST ALL"
                },
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.4.sp,
                    color = if (testing) palette.dim else palette.accent,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !testing, onClick = onTestAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (visible.isEmpty()) {
            EmptyState(hasAny = rows.isNotEmpty())
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.guid }) { row ->
                    ServerRowItem(row) { onSelect(row) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (query.isEmpty()) {
            Text(
                "Search",
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 13.sp,
                    color = palette.dim,
                ),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontSize = 13.sp,
                color = palette.text,
            ),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ServerRowItem(row: ServerRow, onClick: () -> Unit) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (row.selected) palette.selectionWash else palette.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // The selected marker is a bar, matching the design's list language.
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .background(if (row.selected) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
        )

        val flag = countryFlag(row.country)
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.hoverWash),
            contentAlignment = Alignment.Center,
        ) {
            if (flag.isNotEmpty()) {
                Text(flag, style = TextStyle(fontSize = 19.sp))
            } else {
                Text(
                    row.country.take(2).uppercase().ifEmpty { "—" },
                    style = TextStyle(
                        fontFamily = Brand.JetBrainsMono,
                        fontSize = 10.sp,
                        color = palette.dim,
                    ),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = if (row.selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    color = palette.text,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.protocol.isNotEmpty()) {
                    Text(
                        row.protocol.uppercase(),
                        style = TextStyle(
                            fontFamily = Brand.JetBrainsMono,
                            fontWeight = FontWeight.Medium,
                            fontSize = 8.5.sp,
                            letterSpacing = 0.8.sp,
                            color = palette.accent,
                        ),
                    )
                }
                if (row.country.isNotEmpty()) {
                    Text(
                        row.country.uppercase(),
                        style = TextStyle(
                            fontFamily = Brand.JetBrainsMono,
                            fontSize = 9.sp,
                            letterSpacing = 0.8.sp,
                            color = palette.dim,
                        ),
                    )
                }
            }
        }

        Text(
            when {
                // Only the row actually being measured. Marking every row while
                // a batch runs hides the progress the batch is making.
                row.testing -> "···"
                row.pingMs > 0L -> row.pingMs.toString()
                // Tested and unreachable. Without this it read as "not tested"
                // and the user would keep re-testing a server that is down.
                row.pingMs < 0L -> "×"
                else -> "—"
            },
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = if (row.testing) palette.dim else pingColorPublic(row.pingMs, palette),
            ),
        )
        Text(
            if (row.pingMs > 0L) "MS" else "  ",
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontSize = 8.sp,
                color = palette.dim,
            ),
        )
    }
}

@Composable
private fun EmptyState(hasAny: Boolean) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                hasAny -> "Nothing matched"
                // Not an error the user can fix by tapping anything: the list
                // arrives from the panel, so say that rather than offering an
                // "add" button that no longer exists.
                else -> "No servers received from the panel yet"
            },
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontSize = 13.sp,
                color = palette.dim,
            ),
        )
    }
}
