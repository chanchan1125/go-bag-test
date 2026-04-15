package com.gobag.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.domain.logic.PiConnectionSnapshot
import com.gobag.domain.logic.PiConnectionStatus
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
    val connection: PiConnectionSnapshot = PiConnectionStatus.empty(),
    val endpoint: String = "",
    val manual_endpoint: String = "",
    val manual_pair_code: String = "",
    val paired_bag_count: Int = 0,
    val running: Boolean = false,
    val error: String = "",
    val feedback_message: String = ""
)

private data class PairingViewInputs(
    val running: Boolean,
    val error: String,
    val feedback: String,
    val endpoint_input: String,
    val pair_code_input: String
)

private data class PairingFeedbackState(
    val running: Boolean,
    val error: String,
    val feedback: String
)

private data class PairingEndpointState(
    val endpoint_input: String,
    val pair_code_input: String
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
        manualPairCode
    ) { endpointInput, pairCodeInput ->
        PairingEndpointState(
            endpoint_input = endpointInput,
            pair_code_input = pairCodeInput
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
            pair_code_input = endpointState.pair_code_input
        )
    }

    val ui_state: StateFlow<PairingUiState> = combine(
        sync_repository.observe_device_state(),
        view_inputs
    ) { state, inputs ->
        val connection = PiConnectionStatus.from_device_state(state)
        PairingUiState(
            connection = connection,
            endpoint = state.base_url,
            manual_endpoint = inputs.endpoint_input.ifBlank {
                state.saved_addresses.firstOrNull { it.is_active }?.base_url ?: state.base_url
            },
            manual_pair_code = inputs.pair_code_input,
            paired_bag_count = state.paired_bags.size,
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
                manualEndpoint.value = result.endpoint
                feedback_message.value = if (result.status == "Ready to connect") {
                    "Bag hub found. Scan the QR code or enter the 6-digit code now."
                } else {
                    "Bag hub found."
                }
            } catch (e: Exception) {
                val message = e.message ?: "We could not check that bag location."
                error.value = message
                feedback_message.value = "We could not check that bag location."
            } finally {
                running.value = false
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            val selectedBagId = sync_repository.observe_device_state().first().selected_bag_id
            if (selectedBagId.isBlank()) {
                error.value = "No bag is selected on this phone."
                return@launch
            }
            pairing_repository.unpair_bag(selectedBagId)
            error.value = ""
            feedback_message.value = "Bag removed from this phone."
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
                manualEndpoint.value = result.endpoint
                manualPairCode.value = ""
                feedback_message.value = result.detail
            } catch (e: Exception) {
                val message = e.message ?: "We could not finish setup."
                error.value = message
                feedback_message.value = message
            } finally {
                running.value = false
            }
        }
    }
}
