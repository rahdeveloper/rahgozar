package com.rahgozar.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette

/** One entry in the drawer. */
data class DrawerItem(
    val id: String,
    val label: String,
    /** Links the panel supplied. Empty for in-app destinations. */
    val url: String = "",
)

/**
 * The side menu.
 *
 * Five of the design's ten screens were for importing, editing, subscribing,
 * logging and backing up configurations — all removed in the security pass, so
 * none of them appear here. What is left navigates; nothing in this menu can
 * produce or export a configuration.
 *
 * The link entries come from the panel, so an operator can add a support or FAQ
 * page without an app release, and an empty URL simply means the entry is not
 * shown rather than a menu item that goes nowhere.
 */
@Composable
fun AppDrawer(
    items: List<DrawerItem>,
    onSelect: (DrawerItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current

    Box(Modifier.fillMaxSize()) {
        // Scrim. Tapping anywhere off the panel closes it.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss)
        )

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(262.dp)
                .background(palette.drawerBackground)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 18.dp)) {
                Text(
                    "RAHGOZAR",
                    style = TextStyle(
                        fontFamily = Brand.JetBrainsMono,
                        fontWeight = FontWeight.Black,
                        fontSize = 21.sp,
                        color = palette.text,
                    ),
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "PRIVATE TUNNEL",
                    style = TextStyle(
                        fontFamily = Brand.JetBrainsMono,
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp,
                        color = palette.dim,
                    ),
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(horizontal = 22.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Text(
                            item.label,
                            style = TextStyle(
                                fontFamily = Brand.Vazirmatn,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = palette.text2,
                            ),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(palette.hair)
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
                }
            }
        }
    }
}
