package com.gobag.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.AlertModel
import com.gobag.core.model.Conflict
import com.gobag.domain.logic.PreparednessRules
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val last_sync_at: Long = 0L,
    val auto_sync_enabled: Boolean = false,
    val connection_status: String = "unknown",
    val pending_changes_count: Int = 0,
    val local_ip: String = "",
    val last_connection_error: String = "",
    val conflicts: List<Conflict> = emptyList(),
    val selected_bag_name: String = "No bag selected",
    val alerts: List<AlertModel> = emptyList(),
    val running: Boolean = false,
    val feedback_message: String = ""
)

class SyncViewModel(
    item_repository: ItemRepository,
    private val sync_repository: SyncRepository
) : ViewModel() {

    private val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val feedback_message = kotlinx.coroutines.flow.MutableStateFlow("")
    private val bags = combine(item_repository.observe_bags(), sync_repository.observe_device_state()) { bagList, state ->
        val pairedBagIds = state.paired_bags.map { it.bag_id }.toSet()
        bagList.filter { it.bag_id in pairedBagIds }
    }
    private val selectedBagId = combine(sync_repository.observe_device_state(), bags) { state, bagList ->
        when {
            state.selected_bag_id.isNotBlank() && bagList.any { it.bag_id == state.selected_bag_id } -> state.selected_bag_id
            bagList.isNotEmpty() -> bagList.first().bag_id
            else -> ""
        }
    }
    private val selectedItems = selectedBagId.flatMapLatest { bag_id -> item_repository.observe_items(bag_id) }

    private val baseUiState = combine(
        selectedItems,
        sync_repository.observe_device_state(),
        sync_repository.observe_conflicts(),
        bags,
        selectedBagId,
    ) { items, state, conflicts, bagList, selected_bag_id ->
        val selectedBag = bagList.firstOrNull { it.bag_id == selected_bag_id }
        val alerts = PreparednessRules.build_expiration_alerts(
            items = items,
            bag_id = selected_bag_id,
            bag_name = selectedBag?.name ?: "Primary bag"
        )
        SyncUiState(
            last_sync_at = state.last_sync_at,
            auto_sync_enabled = state.auto_sync_enabled,
            connection_status = state.connection_status,
            pending_changes_count = state.pending_changes_count,
            local_ip = state.local_ip,
            last_connection_error = state.last_connection_error,
            conflicts = conflicts,
            selected_bag_name = selectedBag?.name ?: "No bag selected",
            alerts = alerts,
        )
    }

    val ui_state: StateFlow<SyncUiState> = combine(
        baseUiState,
        running,
        feedback_message
    ) { base_state, running_now, message ->
        base_state.copy(
            running = running_now,
            feedback_message = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncUiState())

    fun sync_now() {
        viewModelScope.launch {
            running.value = true
            try {
                sync_repository.refresh_remote_status()
                val result = sync_repository.run_sync_now()
                feedback_message.value = result.skipped_reason
                    ?: "Sync completed. Raspberry Pi inventory is up to date."
            } catch (e: Exception) {
                feedback_message.value = "Sync failed: ${e.message ?: "unknown error"}"
            } finally {
                running.value = false
            }
        }
    }

    fun set_auto_sync(enabled: Boolean) {
        viewModelScope.launch {
            sync_repository.set_auto_sync(enabled)
            feedback_message.value = if (enabled) "Auto-sync enabled." else "Auto-sync disabled."
        }
    }

    fun keep_phone(conflict: Conflict) {
        viewModelScope.launch { sync_repository.resolve_conflict_keep_phone(conflict.server_version) }
    }

    fun keep_pi(conflict: Conflict) {
        viewModelScope.launch { sync_repository.resolve_conflict_keep_pi(conflict.server_version) }
    }

    fun keep_deleted(conflict: Conflict) {
        viewModelScope.launch { sync_repository.resolve_conflict_keep_deleted(conflict.server_version) }
    }

    fun restore(conflict: Conflict) {
        viewModelScope.launch { sync_repository.resolve_conflict_restore(conflict.server_version) }
    }

    fun consume_feedback() {
        feedback_message.value = ""
    }
}
