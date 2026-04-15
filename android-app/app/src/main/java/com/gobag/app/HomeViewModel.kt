package com.gobag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.AlertModel
import com.gobag.domain.logic.ChecklistCategoryStatus
import com.gobag.domain.logic.PiConnectionSnapshot
import com.gobag.domain.logic.PiConnectionStatus
import com.gobag.domain.logic.PreparednessRules
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val last_sync_time: Long = 0L,
    val connection: PiConnectionSnapshot = PiConnectionStatus.empty(),
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
    val pending_phone_changes: Int = 0,
    val sync_recommended: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    item_repository: ItemRepository,
    sync_repository: SyncRepository
) : ViewModel() {
    private val deviceState = sync_repository.observe_device_state()
    private val bags = combine(item_repository.observe_bags(), deviceState) { bagList, state ->
        val pairedBagIds = state.paired_bags.map { it.bag_id }.toSet()
        bagList.filter { it.bag_id in pairedBagIds }
    }
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
    private val pending_phone_changes = combine(selectedBagId, deviceState) { bag_id, state ->
        bag_id to state.last_sync_at
    }.flatMapLatest { (bag_id, last_sync_at) ->
        if (bag_id.isBlank()) {
            flowOf(0)
        } else {
            item_repository.observe_pending_phone_change_count(bag_id, last_sync_at)
        }
    }

    private val base_ui_state = combine(
        selectedItems,
        deviceState,
        sync_repository.observe_conflicts(),
        bags,
        selectedBagId
    ) { items, state, conflicts, bagList, selected_bag_id ->
        val connection = PiConnectionStatus.from_device_state(state)
        val summary = PreparednessRules.build_readiness_summary(items, connection.is_paired)
        val selectedBag = bagList.firstOrNull { it.bag_id == selected_bag_id }
        val expiryAlerts = PreparednessRules.build_expiration_alerts(
            items = items,
            bag_id = selected_bag_id,
            bag_name = selectedBag?.name ?: "Primary bag"
        )

        HomeUiState(
            last_sync_time = state.last_sync_at,
            connection = connection,
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
            pending_phone_changes = 0,
            sync_recommended = false
        )
    }

    val ui_state: StateFlow<HomeUiState> = combine(base_ui_state, pending_phone_changes) { base_state, pending_changes ->
        base_state.copy(
            pending_phone_changes = pending_changes,
            sync_recommended = pending_changes > 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}
