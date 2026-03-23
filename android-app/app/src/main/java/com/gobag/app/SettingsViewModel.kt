package com.gobag.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.model.BagProfile
import com.gobag.core.model.SavedPiAddress
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
    val editing_address_id: String? = null,
    val connection_status: String = "unknown",
    val local_ip: String = "",
    val pending_changes_count: Int = 0,
    val last_sync_at: Long = 0L,
    val last_connection_error: String = "",
    val pi_device_id: String = "",
    val selected_bag_id: String = "",
    val bags: List<BagProfile> = emptyList(),
    val saved_addresses: List<SavedPiAddress> = emptyList(),
    val running: Boolean = false,
    val feedback_message: String = ""
)

class SettingsViewModel(
    private val item_repository: ItemRepository,
    private val pairing_repository: PairingRepository,
    private val sync_repository: SyncRepository
) : ViewModel() {
    private val endpoint_input = MutableStateFlow("")
    private val editing_address_id = MutableStateFlow<String?>(null)
    private val running = MutableStateFlow(false)
    private val feedback_message = MutableStateFlow("")

    private val base_ui_state = combine(
        sync_repository.observe_device_state(),
        item_repository.observe_bags(),
        endpoint_input,
        editing_address_id
    ) { deviceState, bags, endpoint, editingAddressId ->
        val pairedBagIds = deviceState.paired_bags.map { it.bag_id }.toSet()
        SettingsUiState(
            endpoint_input = endpoint.ifBlank { deviceState.saved_addresses.firstOrNull { it.is_active }?.base_url ?: deviceState.base_url },
            editing_address_id = editingAddressId,
            connection_status = deviceState.connection_status,
            local_ip = deviceState.local_ip,
            pending_changes_count = deviceState.pending_changes_count,
            last_sync_at = deviceState.last_sync_at,
            last_connection_error = deviceState.last_connection_error,
            pi_device_id = deviceState.pi_device_id,
            selected_bag_id = deviceState.selected_bag_id,
            bags = bags.filter { it.bag_id in pairedBagIds },
            saved_addresses = deviceState.saved_addresses,
        )
    }

    val ui_state: StateFlow<SettingsUiState> = combine(
        base_ui_state,
        running,
        feedback_message
    ) { baseState, runningNow, feedback ->
        baseState.copy(
            running = runningNow,
            feedback_message = feedback
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun on_endpoint_changed(value: String) {
        endpoint_input.value = value
    }

    fun start_edit_address(address: SavedPiAddress) {
        editing_address_id.value = address.id
        endpoint_input.value = address.base_url
    }

    fun cancel_address_edit() {
        editing_address_id.value = null
        endpoint_input.value = ""
    }

    fun save_endpoint() {
        viewModelScope.launch {
            running.value = true
            try {
                val saved = pairing_repository.save_endpoint(
                    base_url = endpoint_input.value,
                    address_id = editing_address_id.value
                )
                editing_address_id.value = null
                endpoint_input.value = saved.base_url
                feedback_message.value = if (saved.is_active) {
                    "Raspberry Pi address saved and marked active."
                } else {
                    "Raspberry Pi address saved."
                }
            } catch (e: Exception) {
                feedback_message.value = e.message ?: "Could not save endpoint."
            } finally {
                running.value = false
            }
        }
    }

    fun delete_address(address: SavedPiAddress) {
        viewModelScope.launch {
            running.value = true
            try {
                pairing_repository.delete_endpoint(address.id)
                if (editing_address_id.value == address.id) {
                    editing_address_id.value = null
                    endpoint_input.value = ""
                }
                feedback_message.value = if (address.is_active) {
                    "Active Raspberry Pi address deleted. The next saved address became active."
                } else {
                    "Raspberry Pi address deleted."
                }
            } catch (e: Exception) {
                feedback_message.value = e.message ?: "Could not delete endpoint."
            } finally {
                running.value = false
            }
        }
    }

    fun activate_address(address: SavedPiAddress) {
        viewModelScope.launch {
            pairing_repository.set_active_endpoint(address.id)
            endpoint_input.value = address.base_url
            editing_address_id.value = null
            feedback_message.value = "Active Raspberry Pi address updated."
        }
    }

    fun test_endpoint(address: SavedPiAddress? = null) {
        viewModelScope.launch {
            running.value = true
            try {
                val result = if (address != null) {
                    pairing_repository.refresh_endpoint(address.id, address.base_url)
                } else {
                    pairing_repository.test_connection(endpoint_input.value)
                }
                endpoint_input.value = result.endpoint
                feedback_message.value = result.detail
            } catch (e: Exception) {
                feedback_message.value = e.message ?: "Connection test failed."
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

    fun unpair_selected_bag() {
        viewModelScope.launch {
            val current = sync_repository.observe_device_state().first()
            if (current.selected_bag_id.isBlank()) {
                feedback_message.value = "No paired bag is selected."
                return@launch
            }
            pairing_repository.unpair_bag(current.selected_bag_id)
            feedback_message.value = "Selected bag removed from this phone."
        }
    }

    fun consume_feedback() {
        feedback_message.value = ""
    }
}
