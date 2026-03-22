package com.gobag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.AlertModel
import com.gobag.domain.logic.ChecklistCategoryStatus
import com.gobag.domain.logic.PreparednessRules
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val last_sync_time: Long = 0L,
    val device_status: String = "Not Paired",
    val connection_status: String = "unknown",
    val local_ip: String = "",
    val pending_changes_count: Int = 0,
    val last_connection_error: String = "",
    val selected_bag_name: String = "No bag selected",
    val bag_count: Int = 0,
    val bag_readiness: String = "Incomplete",
    val checklist: List<ChecklistCategoryStatus> = PreparednessRules.checklist_categories.map {
        ChecklistCategoryStatus(name = it, checked = false)
    },
    val checklist_covered: Int = 0,
    val checklist_total: Int = PreparednessRules.checklist_categories.size,
    val expired_count: Int = 0,
    val near_expiry_count: Int = 0,
    val alerts: List<String> = emptyList(),
    val expiry_alerts: List<AlertModel> = emptyList(),
    val has_conflicts: Boolean = false,
    val sync_recommended: Boolean = false
)

class HomeViewModel(
    item_repository: ItemRepository,
    sync_repository: SyncRepository
) : ViewModel() {
    private val deviceState = sync_repository.observe_device_state()
    private val bags = item_repository.observe_bags()
    private val selectedBagId = combine(deviceState, bags) { state, bagList ->
        when {
            state.selected_bag_id.isNotBlank() && bagList.any { it.bag_id == state.selected_bag_id } -> state.selected_bag_id
            bagList.isNotEmpty() -> bagList.first().bag_id
            else -> ""
        }
    }
    private val selectedItems = selectedBagId.flatMapLatest { bag_id ->
        item_repository.observe_items(bag_id)
    }

    val ui_state: StateFlow<HomeUiState> = combine(
        selectedItems,
        deviceState,
        sync_repository.observe_conflicts(),
        bags,
        selectedBagId
    ) { items, state, conflicts, bagList, selected_bag_id ->
        val isConnected = state.auth_token.isNotBlank() && state.base_url.isNotBlank()
        val summary = PreparednessRules.build_readiness_summary(items, isConnected)
        val syncRecommended = items.any { it.updated_at > state.last_sync_at }
        val selectedBag = bagList.firstOrNull { it.bag_id == selected_bag_id }
        val expiryAlerts = PreparednessRules.build_expiration_alerts(
            items = items,
            bag_id = selected_bag_id,
            bag_name = selectedBag?.name ?: "Primary bag"
        )

        HomeUiState(
            last_sync_time = state.last_sync_at,
            device_status = summary.device_status,
            connection_status = state.connection_status,
            local_ip = state.local_ip,
            pending_changes_count = state.pending_changes_count,
            last_connection_error = state.last_connection_error,
            selected_bag_name = selectedBag?.name ?: "No bag selected",
            bag_count = bagList.size,
            bag_readiness = summary.bag_readiness,
            checklist = summary.checklist.categories,
            checklist_covered = summary.checklist.covered_count,
            checklist_total = summary.checklist.total_count,
            expired_count = summary.expiration.expired_count,
            near_expiry_count = summary.expiration.near_expiry_count,
            alerts = summary.alerts,
            expiry_alerts = expiryAlerts,
            has_conflicts = conflicts.isNotEmpty(),
            sync_recommended = syncRecommended
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}
