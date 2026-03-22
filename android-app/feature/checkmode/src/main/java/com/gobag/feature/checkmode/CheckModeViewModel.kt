package com.gobag.feature.checkmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.common.nowMs
import com.gobag.core.model.Item
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CheckModeUiState(
    val items: List<Item> = emptyList(),
    val feedback_message: String = ""
)

class CheckModeViewModel(
    private val item_repository: ItemRepository,
    private val sync_repository: SyncRepository,
    private val phone_device_id: String,
    initial_bag_id: String
) : ViewModel() {
    private val feedback_message = MutableStateFlow("")
    private val selected_bag_id = MutableStateFlow(initial_bag_id)

    init {
        viewModelScope.launch {
            sync_repository.observe_device_state().collect { state ->
                selected_bag_id.value = state.selected_bag_id
            }
        }
    }

    val ui_state: StateFlow<CheckModeUiState> = combine(
        selected_bag_id.flatMapLatest { bagId ->
            item_repository.observe_items(bagId).map { list -> list.filter { !it.deleted } }
        },
        feedback_message
    ) { items, message ->
        CheckModeUiState(items = items, feedback_message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CheckModeUiState())

    fun toggle(item: Item) {
        viewModelScope.launch {
            if (item.bag_id != selected_bag_id.value) {
                feedback_message.value = "This item is no longer in the current primary bag."
                return@launch
            }
            item_repository.upsert_item(
                item.copy(
                    packed_status = !item.packed_status,
                    updated_at = nowMs(),
                    updated_by = phone_device_id
                )
            )
            val state = sync_repository.observe_device_state().first()
            if (state.auth_token.isBlank() || state.base_url.isBlank()) {
                feedback_message.value = "Checklist updated locally. Pair and sync to update the Raspberry Pi."
                return@launch
            }
            try {
                sync_repository.run_sync_now()
                feedback_message.value = "Checklist updated locally and synced to the Raspberry Pi."
            } catch (e: Exception) {
                feedback_message.value = "Checklist updated locally, but Raspberry Pi sync failed: ${e.message ?: "unknown sync error"}"
            }
        }
    }

    fun consume_feedback() {
        feedback_message.value = ""
    }
}
