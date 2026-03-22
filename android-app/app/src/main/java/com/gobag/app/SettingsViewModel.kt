package com.gobag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.BagProfile
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.PairingRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val endpoint_input: String = "",
    val connection_status: String = "unknown",
    val local_ip: String = "",
    val pending_changes_count: Int = 0,
    val last_sync_at: Long = 0L,
    val last_connection_error: String = "",
    val pi_device_id: String = "",
    val selected_bag_id: String = "",
    val bags: List<BagProfile> = emptyList(),
    val running: Boolean = false,
    val feedback_message: String = ""
)

class SettingsViewModel(
    private val item_repository: ItemRepository,
    private val pairing_repository: PairingRepository,
    private val sync_repository: SyncRepository
) : ViewModel() {
    private val endpoint_input = MutableStateFlow("")
    private val running = MutableStateFlow(false)
    private val feedback_message = MutableStateFlow("")

    val ui_state: StateFlow<SettingsUiState> = combine(
        sync_repository.observe_device_state(),
        item_repository.observe_bags(),
        endpoint_input,
        running,
        feedback_message
    ) { deviceState, bags, endpoint, runningNow, feedback ->
        SettingsUiState(
            endpoint_input = endpoint.ifBlank { deviceState.base_url },
            connection_status = deviceState.connection_status,
            local_ip = deviceState.local_ip,
            pending_changes_count = deviceState.pending_changes_count,
            last_sync_at = deviceState.last_sync_at,
            last_connection_error = deviceState.last_connection_error,
            pi_device_id = deviceState.pi_device_id,
            selected_bag_id = deviceState.selected_bag_id,
            bags = bags,
            running = runningNow,
            feedback_message = feedback
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun on_endpoint_changed(value: String) {
        endpoint_input.value = value
    }

    fun save_endpoint() {
        viewModelScope.launch {
            running.value = true
            try {
                val current = sync_repository.observe_device_state().first()
                pairing_repository.save_endpoint(endpoint_input.value.ifBlank { current.base_url })
                feedback_message.value = "Saved Raspberry Pi address only. Pair with QR to authenticate this phone."
            } catch (e: Exception) {
                feedback_message.value = e.message ?: "Could not save endpoint."
            } finally {
                running.value = false
            }
        }
    }

    fun refresh_status() {
        viewModelScope.launch {
            running.value = true
            val message = sync_repository.refresh_remote_status()
            feedback_message.value = message ?: "Raspberry Pi status refreshed."
            running.value = false
        }
    }

    fun select_bag(bagId: String) {
        viewModelScope.launch { sync_repository.set_selected_bag_id(bagId) }
    }

    fun consume_feedback() {
        feedback_message.value = ""
    }
}
