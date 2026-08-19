package com.rahgozar.app.ui.main

import com.rahgozar.app.dto.GroupMapItem
import com.rahgozar.app.dto.LocateTarget

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val statusText: String = "",
    val locateTarget: LocateTarget? = null,
    val doubleColumnDisplay: Boolean = false,
)

/**
 * All possible user interaction intents.
 *
 * Note what is absent: importing, editing, sharing and exporting a server
 * configuration. The server list is supplied by the panel and is read-only
 * here — see docs/SECURITY.md. Adding an action back is not a UI decision.
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object SortByTestResults : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class Search(val query: String) : MainAction

    data class LocateHandled(val target: LocateTarget) : MainAction
}
