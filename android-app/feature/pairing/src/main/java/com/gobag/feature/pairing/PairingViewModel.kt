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
    val hero_status_label: String = "",
    val hero_status_value: String = "",
    val hero_detail: String = "",
    val running: Boolean = false,
    val error: String = "",
    val feedback_message: String = ""
)

private data class PairingViewInputs(
    val running: Boolean,
    val error: String,
    val feedback: String,
    val hero_status_label: String,
    val hero_status_value: String,
    val hero_detail: String,
    val endpoint_input: String,
    val pair_code_input: String
)

private data class PairingFeedbackState(
    val running: Boolean,
    val error: String,
    val feedback: String,
    val hero_status_label: String,
    val hero_status_value: String,
    val hero_detail: String
)

private data class PairingHeroState(
    val label: String,
    val value: String,
    val detail: String
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
    private val heroStatusLabel = MutableStateFlow("")
    private val heroStatusValue = MutableStateFlow("")
    private val heroDetail = MutableStateFlow("")
    private val manualEndpoint = MutableStateFlow("")
    private val manualPairCode = MutableStateFlow("")
    private val hero_state = combine(
        heroStatusLabel,
        heroStatusValue,
        heroDetail
    ) { heroLabel, heroValue, heroDetailText ->
        PairingHeroState(
            label = heroLabel,
            value = heroValue,
            detail = heroDetailText
        )
    }

    private val feedback_state = combine(
        running,
        error,
        feedback_message,
        hero_state
    ) { running_now, error_text, feedback, heroState ->
        PairingFeedbackState(
            running = running_now,
            error = error_text,
            feedback = feedback,
            hero_status_label = heroState.label,
            hero_status_value = heroState.value,
            hero_detail = heroState.detail
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
            hero_status_label = feedbackState.hero_status_label,
            hero_status_value = feedbackState.hero_status_value,
            hero_detail = feedbackState.hero_detail,
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
            hero_status_label = inputs.hero_status_label.ifBlank { connection.primary_label },
            hero_status_value = inputs.hero_status_value.ifBlank { connection.connection_label },
            hero_detail = inputs.hero_detail.ifBlank { connection.detail },
            running = inputs.running,
            error = inputs.error,
            feedback_message = inputs.feedback
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PairingUiState())

    fun on_manual_endpoint_changed(value: String) {
        manualEndpoint.value = value
        clear_hero_status()
    }

    fun on_pair_code_changed(value: String) {
        manualPairCode.value = value.filter { it.isDigit() }.take(6)
    }

    fun on_qr_payload(payload_json: String) {
        execute_pairing {
            pairing_repository.pair_from_qr_payload(payload_json)
        }
    }

    fun on_scan_permission_denied() {
        feedback_message.value = "Camera permission is needed to scan the bag QR. You can still type the 6-digit code."
    }

    fun on_scan_launch_failed() {
        error.value = "The QR scanner could not open on this phone."
        feedback_message.value = "The QR scanner could not open on this phone. Type the 6-digit code for now."
    }

    fun pair_with_code() {
        execute_pairing {
            pairing_repository.pair_with_code(
                base_url = resolve_manual_endpoint(),
                pair_code = manualPairCode.value
            )
        }
    }

    fun test_connection(value: String) {
        viewModelScope.launch {
            running.value = true
            error.value = ""
            try {
                val result = pairing_repository.test_connection(
                    base_url = value.ifBlank { resolve_manual_endpoint() },
                    allow_different_bag = true
                )
                manualEndpoint.value = result.endpoint
                heroStatusLabel.value = "Ready"
                heroStatusValue.value = result.status.ifBlank { "Ready to connect" }
                heroDetail.value = result.detail
                feedback_message.value = result.detail
            } catch (e: Exception) {
                val message = e.message ?: "We could not check that bag location."
                error.value = message
                heroStatusLabel.value = "Attention"
                heroStatusValue.value = "Check location"
                heroDetail.value = message
                feedback_message.value = message
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
                heroStatusLabel.value = if (result.initial_sync_completed) "Connected" else "Setup started"
                heroStatusValue.value = if (result.initial_sync_completed) "Ready" else "Needs update"
                heroDetail.value = result.detail
                feedback_message.value = result.detail
            } catch (e: Exception) {
                val message = e.message ?: "We could not finish setup."
                error.value = message
                heroStatusLabel.value = "Attention"
                heroStatusValue.value = "Setup failed"
                heroDetail.value = message
                feedback_message.value = message
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun resolve_manual_endpoint(): String {
        val state = sync_repository.observe_device_state().first()
        return manualEndpoint.value.ifBlank {
            state.saved_addresses.firstOrNull { it.is_active }?.base_url ?: state.base_url
        }
    }

    private fun clear_hero_status() {
        heroStatusLabel.value = ""
        heroStatusValue.value = ""
        heroDetail.value = ""
    }
}
