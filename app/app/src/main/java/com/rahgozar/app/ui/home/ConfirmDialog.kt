package com.rahgozar.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette

/**
 * Asks before dropping a live tunnel.
 *
 * Connecting is not confirmed and should not be: the worst case is a moment of
 * waiting. Disconnecting is the asymmetric one — the dial is the largest tap
 * target on the screen, and a stray touch that drops the tunnel exposes traffic
 * the user believed was covered.
 */
@Composable
fun ConfirmDisconnectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.drawerBackground)
                .border(1.dp, palette.hair, RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                "Disconnect?",
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = palette.text,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Once disconnected, your traffic stops going through the tunnel.",
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 13.sp,
                    color = palette.text2,
                ),
            )
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Staying connected is the safe default, so it carries the
                // weight and sits where the thumb lands first.
                DialogButton(
                    label = "Stay",
                    filled = true,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    label = "Disconnect",
                    filled = false,
                    danger = true,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val palette = LocalPalette.current
    val tint = if (danger) palette.danger else palette.accent
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (filled) tint else tint.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (filled) palette.onAccent else tint,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}
