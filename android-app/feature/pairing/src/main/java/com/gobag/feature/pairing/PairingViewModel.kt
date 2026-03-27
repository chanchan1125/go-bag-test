package com.gobag.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.domain.repository.PairingConnectionResult
import com.gobag.domain.repository.PairingRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val status: String = "Not paired",
    val endpoint: String = "",
    val manual_endpoint: String = "",
    val manual_pair_code: String = "",
    val endpoint_status: String = "Unknown",
    val endpoint_detail: String = "Scan the Raspberry Pi QR code or test a local address.",
    val auth_status: String = "Not authenticated",
    val pairing_detail: String = "No Raspberry Pi has been paired with this phone yet.",
    val paired_bag_count: Int = 0,
    val selected_bag_name: String = "",
    val running: Boolean = false,
    val error: String = "",
    val feedback_message: String = ""
)

private data class PairingViewInputs(
    val running: Boolean,
    val error: String,
    val feedback: String,
    val endpoint_input: String,
    val pair_code_input: String,
    val endpoint_status: String,
    val endpoint_detail: String
)

private data class PairingFeedbackState(
    val running: Boolean,
    val error: String,
    val feedback: String
)

private data class PairingEndpointState(
    val endpoint_input: String,
    val pair_code_input: String,
    val endpoint_status: String,
    val endpoint_detail: String
)

class PairingViewModel(
    private val pairing_repository: PairingRepository,
    private val sync_repository: SyncRepository
) : ViewModel() {
    private val running = MutableStateFlow(false)
    private val error = MutableStateFlow("")
    private val feedback_message = MutableStateFlow("")
    private val manualEndpoint = MutableStateFlow("")
    private val manualPairCode = MutableStateFlow("")
    private val endpointStatus = MutableStateFlow("Unknown")
    private val endpointDetail = MutableStateFlow("Scan the Raspberry Pi QR code or test a local address.")

    private val feedback_state = combine(
        running,
        error,
        feedback_message
    ) { running_now, error_text, feedback ->
        PairingFeedbackState(
            running = running_now,
            error = error_text,
            feedback = feedback
        )
    }

    private val endpoint_state = combine(
        manualEndpoint,
        manualPairCode,
        endpointStatus,
        endpointDetail
    ) { endpointInput, pairCodeInput, statusText, detailText ->
        PairingEndpointState(
            endpoint_input = endpointInput,
            pair_code_input = pairCodeInput,
            endpoint_status = statusText,
            endpoint_detail = detailText
        )
    }

    private val view_inputs = combine(
        feedback_state,
        endpoint_state
    ) { feedbackState, endpointState ->
        PairingViewInputs(
            running = feedbackState.running,
            error = feedbackState.error,
            feedback = feedbackState.feedback,
            endpoint_input = endpointState.endpoint_input,
            pair_code_input = endpointState.pair_code_input,
            endpoint_status = endpointState.endpoint_status,
            endpoint_detail = endpointState.endpoint_detail
        )
    }

    val ui_state: StateFlow<PairingUiState> = combine(
        sync_repository.observe_device_state(),
        view_inputs
    ) { state, inputs ->
        val paired = state.paired_bags.isNotEmpty()
        val savedEndpointOnly = state.saved_addresses.isNotEmpty() && state.paired_bags.isEmpty()
        PairingUiState(
            status = when {
                paired -> "Bag pairing ready"
                savedEndpointOnly -> "Address saved only"
                else -> "Not paired"
            },
            endpoint = state.base_url,
            manual_endpoint = inputs.endpoint_input.ifBlank {
                state.saved_addresses.firstOrNull { it.is_active }?.base_url ?: state.base_url
            },
            manual_pair_code = inputs.pair_code_input,
            endpoint_status = inputs.endpoint_status,
            endpoint_detail = inputs.endpoint_detail,
            auth_status = if (state.auth_token.isNotBlank()) "Authenticated for selected bag" else "Not authenticated",
            pairing_detail = when {
                paired && state.selected_bag_id.isNotBlank() ->
                    "This phone has ${state.paired_bags.size} paired bag(s). The selected bag is currently ready for sync."
                paired -> "This phone has ${state.paired_bags.size} paired bag(s). Choose one as primary before syncing."
                savedEndpointOnly -> "This phone knows the Raspberry Pi address, but it still needs a QR scan or Pair Code to authenticate."
                else -> "No Raspberry Pi has been paired with this phone yet."
            },
            paired_bag_count = state.paired_bags.size,
            selected_bag_name = state.selected_bag_id,
            running = inputs.running,
            error = inputs.error,
            feedback_message = inputs.feedback
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PairingUiState())

    fun on_manual_endpoint_changed(value: String) {
        manualEndpoint.value = value
    }

    fun on_pair_code_changed(value: String) {
        manualPairCode.value = value.filter { it.isDigit() }.take(6)
    }

    fun on_qr_payload(payload_json: String) {
        execute_pairing {
            pairing_repository.pair_from_qr_payload(payload_json)
        }
    }

    fun pair_with_code() {
        execute_pairing {
            pairing_repository.pair_with_code(
                base_url = manualEndpoint.value,
                pair_code = manualPairCode.value
            )
        }
    }

    fun test_connection(value: String) {
        viewModelScope.launch {
            running.value = true
            error.value = ""
            try {
                val result = pairing_repository.test_connection(value)
                updateConnectionState(result)
                feedback_message.value = "Address test succeeded. Use QR scan or the Pair Code to authenticate this phone."
            } catch (e: Exception) {
                val message = e.message ?: "Connection test failed."
                error.value = message
                endpointStatus.value = "Failed"
                endpointDetail.value = message
                feedback_message.value = "Address test failed."
            } finally {
                running.value = false
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            val selectedBagId = sync_repository.observe_device_state().first().selected_bag_id
            if (selectedBagId.isBlank()) {
                error.value = "No paired bag is selected."
                return@launch
            }
            pairing_repository.unpair_bag(selectedBagId)
            error.value = ""
            endpointStatus.value = "Unknown"
            endpointDetail.value = "Selected bag removed from this phone."
            feedback_message.value = "Bag unpaired."
        }
    }

    fun consume_feedback() {
        feedback_message.value = ""
    }

    private fun execute_pairing(action: suspend () -> com.gobag.domain.repository.PairingSetupResult) {
        viewModelScope.launch {
            running.value = true
            error.value = ""
            try {
                val result = action()
                endpointStatus.value = if (result.initial_sync_completed) "Paired" else "Paired with warning"
                endpointDetail.value = result.detail
                manualEndpoint.value = result.endpoint
                manualPairCode.value = ""
                feedback_message.value = result.detail
            } catch (e: Exception) {
                val message = e.message ?: "Pair failed"
                error.value = message
                endpointStatus.value = "Failed"
                endpointDetail.value = message
                feedback_message.value = message
            } finally {
                running.value = false
            }
        }
    }

    private fun updateConnectionState(result: PairingConnectionResult) {
        manualEndpoint.value = result.endpoint
        endpointStatus.value = result.status
        endpointDetail.value = result.detail
    }
}
