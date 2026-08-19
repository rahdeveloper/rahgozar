package com.rahgozar.app.ui.main

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rahgozar.app.R

/**
 * The overflow menu.
 *
 * Everything that used to read a configuration in (QR, clipboard, file, manual
 * entry) or write one out (share, export, per-server edit and delete) is gone.
 * What is left only measures or reorders what the panel already sent.
 */
@Composable
fun MoreMenuContent(
    onAction: (MainAction) -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.title_service_restart)) },
        onClick = { onAction(MainAction.RestartService) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.title_locate_selected_config)) },
        onClick = { onAction(MainAction.LocateSelectedServer) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.title_sort_by_test_results)) },
        onClick = { onAction(MainAction.SortByTestResults) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.title_ping_all_server)) },
        onClick = { onAction(MainAction.TestAllServers) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.title_real_ping_all_server)) },
        onClick = { onAction(MainAction.TestRealAllServers) }
    )
}
