package com.gobag.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.AlertModel
import com.gobag.core.model.Conflict
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
import kotlinx.coroutines.launch

data class SyncUiState(
    val last_sync_at: Long = 0L,
    val auto_sync_enabled: Boolean = false,
    val connection: PiConnectionSnapshot = PiConnectionStatus.empty(),
    val pending_phone_changes: Int = 0,
    val conflicts: List<Conflict> = emptyList(),
    val selected_bag_name: String = "No bag selected",
    val alerts: List<AlertModel> = emptyList(),
    val running: Boolean = false,
    val feedback_message: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val pending_phone_changes = combine(selectedBagId, sync_repository.observe_device_state()) { bag_id, state ->
        bag_id to state.last_sync_at
    }.flatMapLatest { (bag_id, last_sync_at) ->
        if (bag_id.isBlank()) {
            flowOf(0)
        } else {
            item_repository.observe_pending_phone_change_count(bag_id, last_sync_at)
        }
    }

    private val baseUiState = combine(
        selectedItems,
        sync_repository.observe_device_state(),
        sync_repository.observe_conflicts(),
        bags,
        selectedBagId
    ) { items, state, conflicts, bagList, selected_bag_id ->
        val selectedBag = bagList.firstOrNull { it.bag_id == selected_bag_id }
        val connection = PiConnectionStatus.from_device_state(state)
        val alerts = PreparednessRules.build_expiration_alerts(
            items = items,
            bag_id = selected_bag_id,
            bag_name = selectedBag?.name ?: "Primary bag"
        )
        SyncUiState(
            last_sync_at = state.last_sync_at,
            auto_sync_enabled = state.auto_sync_enabled,
            connection = connection,
            pending_phone_changes = 0,
            conflicts = conflicts,
            selected_bag_name = selectedBag?.name ?: "No bag selected",
            alerts = alerts,
        )
    }

    val ui_state: StateFlow<SyncUiState> = combine(
        baseUiState,
        pending_phone_changes,
        running,
        feedback_message
    ) { base_state, pending_changes, running_now, message ->
        base_state.copy(
            pending_phone_changes = pending_changes,
            running = running_now,
            feedback_message = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncUiState())

    fun sync_now() {
        viewModelScope.launch {
            running.value = true
            try {
                val result = sync_repository.run_sync_now()
                feedback_message.value = result.skipped_reason
                    ?: "Bag updated."
            } catch (e: Exception) {
                feedback_message.value = e.message ?: "The bag update did not finish."
            } finally {
                running.value = false
            }
        }
    }

    fun set_auto_sync(enabled: Boolean) {
        viewModelScope.launch {
            sync_repository.set_auto_sync(enabled)
            feedback_message.value = if (enabled) "Auto update turned on." else "Auto update turned off."
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
