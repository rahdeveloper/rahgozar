package com.rahgozar.app.ui.perappproxy

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.home.AppTopBar

/** One installed app, as the list shows it. */
data class AppRow(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val checked: Boolean,
)

/**
 * Choosing which apps the tunnel carries, in the redesign's language.
 *
 * The geometry is the design's own `perapp` frame: a search strip over a list of
 * 28dp icon tiles, each row ending in a 20dp box that fills with the accent.
 *
 * What the design's frame does not carry — and this screen has to — is the mode
 * itself. The drawer opens this screen directly, so it cannot assume the user
 * came past the settings switch; and "selected" means the opposite thing in the
 * two modes, so the list is unreadable without saying which one is in force.
 * Hence the block above the search: the master switch, the two modes, and one
 * line spelling out what a tick will do.
 */
@Composable
fun PerAppProxyScreen(
    apps: List<AppRow>,
    loading: Boolean,
    enabled: Boolean,
    bypass: Boolean,
    onEnabled: (Boolean) -> Unit,
    onBypass: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val palette = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(query) { onSearch(query) }

    val chosen = apps.count { it.checked }
    val allChosen = apps.isNotEmpty() && chosen == apps.size

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AppTopBar(
            tag = "PER-APP",
            title = "Per-app proxy",
            onMenu = onBack,
            onToggleTheme = onToggleTheme,
            onBack = onBack,
        )

        ModeBlock(
            enabled = enabled,
            bypass = bypass,
            chosen = chosen,
            onEnabled = onEnabled,
            onBypass = onBypass,
        )

        SearchStrip(
            query = query,
            onQuery = { query = it },
            allChosen = allChosen,
            onSelectAll = onSelectAll,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    loading -> "READING APPS…"
                    else -> "$chosen OF ${apps.size} SELECTED"
                },
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
                "INVERT",
                style = actionStyle(palette.dim),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = apps.isNotEmpty(), onClick = onInvert)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (apps.isEmpty()) {
            // weight, not fillMaxSize: a child that asks for the whole height
            // pushes the action bar off the bottom of the screen.
            EmptyState(
                modifier = Modifier.weight(1f),
                loading = loading,
                searching = query.isNotBlank(),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(apps, key = { it.packageName }) { app ->
                    AppRowItem(app) { onToggleApp(app.packageName) }
                }
            }
        }

        ActionBar(onPaste = onPaste, onCopy = onCopy)
    }
}

// ------------------------------------------------------------------- mode --

/**
 * The switch, the two modes, and what a tick means under each.
 *
 * The mode line is not decoration: with the tunnel carrying only the ticked
 * apps, an empty list quietly carries *everything* — which is what the core
 * does when the allow-list is empty — so that case says so rather than letting
 * the user believe they have narrowed anything.
 */
@Composable
private fun ModeBlock(
    enabled: Boolean,
    bypass: Boolean,
    chosen: Int,
    onEnabled: (Boolean) -> Unit,
    onBypass: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current

    SectionHeader("MODE")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onEnabled(!enabled) }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Per-app proxy",
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.5.sp,
                    color = palette.text,
                ),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    enabled -> "The choices on this page are in force"
                    else -> "While this is off, the whole phone goes through the tunnel"
                },
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 11.sp,
                    color = palette.dim,
                ),
            )
        }
        Switch(enabled)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeOption(
            label = "ONLY SELECTED",
            selected = !bypass,
            onClick = { onBypass(false) },
        )
        ModeOption(
            label = "ALL BUT SELECTED",
            selected = bypass,
            onClick = { onBypass(true) },
        )
    }

    // The one line that makes a tick mean something. It changes with the mode
    // because the tick changes meaning with it.
    Text(
        when {
            !bypass && chosen == 0 ->
                "Nothing selected — as it stands, everything goes through the tunnel"
            !bypass -> "Ticked apps go through the tunnel; the rest go direct."
            else -> "Ticked apps go direct; everything else goes through the tunnel."
        },
        style = TextStyle(
            fontFamily = Brand.Vazirmatn,
            fontSize = 11.sp,
            color = if (!bypass && chosen == 0) palette.danger else palette.dim,
        ),
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
}

/** The design's radio: a 13dp ring that takes an accent core when picked. */
@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) palette.accent else palette.hair, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(palette.accent))
            }
        }
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 9.5.sp,
                letterSpacing = 1.sp,
                color = if (selected) palette.accent else palette.dim,
            ),
        )
    }
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

// ----------------------------------------------------------------- search --

@Composable
private fun SearchStrip(
    query: String,
    onQuery: (String) -> Unit,
    allChosen: Boolean,
    onSelectAll: () -> Unit,
) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchGlyph(palette.dim)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search",
                    style = TextStyle(
                        fontFamily = Brand.Vazirmatn,
                        fontSize = 12.5.sp,
                        color = palette.dim,
                    ),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 12.5.sp,
                    color = palette.text,
                ),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            when {
                allChosen -> "NONE"
                else -> "SELECT ALL"
            },
            style = actionStyle(palette.accent),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onSelectAll)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
}

// ------------------------------------------------------------------- rows --

@Composable
private fun AppRowItem(app: AppRow, onClick: () -> Unit) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (app.checked) palette.selectionWash else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        IconTile(app.icon)

        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = if (app.checked) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.5.sp,
                    color = palette.text,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.packageName,
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontSize = 9.sp,
                    color = palette.dim,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (app.checked) palette.accent else Color.Transparent)
                .border(
                    1.5.dp,
                    if (app.checked) palette.accent else palette.hair,
                    RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (app.checked) CheckGlyph(palette.onAccent)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
}

/**
 * The 28dp tile the design draws behind every icon.
 *
 * The launcher hands back a full-resolution adaptive icon, so it is rasterised
 * once at tile size and remembered — a list of two hundred apps otherwise
 * redraws two hundred 108dp drawables on every scroll frame.
 */
@Composable
private fun IconTile(icon: Drawable?) {
    val palette = LocalPalette.current
    val px = with(LocalDensity.current) { 28.dp.roundToPx() }
    val bitmap: ImageBitmap? = remember(icon, px) {
        icon?.let { runCatching { it.toBitmap(px, px).asImageBitmap() }.getOrNull() }
    }

    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// ------------------------------------------------------------------- bars --

/**
 * The bulk actions, on the design's foot bar.
 *
 * Both only ever move **package names** through the clipboard, never a server
 * configuration — which is the reason this screen kept a clipboard path when
 * every other one lost it. See docs/SECURITY.md.
 *
 * There used to be a third: it downloaded a community list of "apps that need a
 * proxy" from a GitHub repository belonging to the upstream project. It is gone.
 * The host is unreachable from the network this app exists for, the list was
 * curated for a different country's censorship, and it meant the app quietly
 * fetched an instruction file from someone else's infrastructure.
 */
@Composable
private fun ActionBar(onPaste: () -> Unit, onCopy: () -> Unit) {
    val palette = LocalPalette.current

    Column(Modifier.fillMaxWidth().background(palette.barBackground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            BarAction(
                label = "PASTE",
                color = palette.dim,
                onClick = onPaste,
            )
            BarAction(
                label = "COPY",
                color = palette.dim,
                onClick = onCopy,
            )
        }
    }
}

@Composable
private fun BarAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = actionStyle(color),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- fittings --

@Composable
private fun SectionHeader(label: String) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 9.dp),
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

/** The design's `act` type: Vazirmatn in Persian, tracked-out mono in English. */
private fun actionStyle(color: Color) = TextStyle(
    fontFamily = Brand.JetBrainsMono,
    fontWeight = FontWeight.Bold,
    fontSize = 10.5.sp,
    letterSpacing = 1.3.sp,
    color = color,
)

@Composable
private fun EmptyState(
    modifier: Modifier,
    loading: Boolean,
    searching: Boolean,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                loading -> "Reading apps…"
                searching -> "Nothing matched"
                else -> "No apps found"
            },
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontSize = 13.sp,
                color = palette.dim,
            ),
        )
    }
}

// ----------------------------------------------------------------- glyphs --

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        drawCircle(color, radius = 7f * u, center = Offset(10.5f * u, 10.5f * u), style = Stroke(stroke))
        drawLine(color, Offset(15.5f * u, 15.5f * u), Offset(20f * u, 20f * u), stroke, StrokeCap.Round)
    }
}

@Composable
private fun CheckGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val u = size.minDimension / 24f
        val stroke = 3f * u
        drawLine(color, Offset(5f * u, 12.5f * u), Offset(10f * u, 17.5f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(10f * u, 17.5f * u), Offset(19f * u, 7f * u), stroke, StrokeCap.Round)
    }
}
