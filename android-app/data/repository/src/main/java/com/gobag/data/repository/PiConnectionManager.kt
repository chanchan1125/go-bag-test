package com.gobag.data.repository

import com.gobag.core.model.DeviceState
import com.gobag.data.remote.DeviceStatusDto
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.domain.logic.PiConnectionStatus
import com.gobag.domain.repository.PairingConnectionResult
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

class PiConnectionManager(
    private val device_state_store: DeviceStateStore
) {
    suspend fun refresh_current_connection(): String? {
        device_state_store.initialize_phone_device_id_if_missing()
        val state = device_state_store.state.first()
        val candidates = build_candidate_endpoints(state)
        if (candidates.isEmpty()) {
            return "No Raspberry Pi address is saved yet."
        }

        device_state_store.set_connection_checking(state.selected_bag_id)
        var lastFailure = ""
        for (candidate in candidates) {
            try {
                val deviceStatus = RemoteDataSourceFactory.create_api(candidate).device_status()
                apply_success(
                    state = state,
                    resolved_base_url = candidate,
                    device_status = deviceStatus,
                    bag_id = state.selected_bag_id
                )
                return null
            } catch (e: Exception) {
                lastFailure = classify_connection_error(e)
            }
        }

        val message = build_refresh_failure_message(state, lastFailure)
        device_state_store.set_connection_error(
            message = message,
            bag_id = state.selected_bag_id,
            status = PiConnectionStatus.STATUS_ADDRESS_UNREACHABLE
        )
        if (state.active_address_id.isNotBlank()) {
            device_state_store.update_saved_address_status(
                address_id = state.active_address_id,
                status = "Failed",
                detail = message,
                make_active = true
            )
        }
        return message
    }

    suspend fun test_endpoint(
        base_url: String,
        address_id: String? = null,
        adopt_on_success: Boolean = true,
        update_global_failure: Boolean? = null
    ): PairingConnectionResult {
        device_state_store.initialize_phone_device_id_if_missing()
        val normalizedBaseUrl = normalize_base_url(base_url)
        val state = device_state_store.state.first()
        val shouldUpdateGlobalFailure = update_global_failure
            ?: state.base_url.isBlank()
            || state.base_url.equals(normalizedBaseUrl, ignoreCase = true)
            || (address_id != null && state.active_address_id == address_id)

        if (adopt_on_success || shouldUpdateGlobalFailure) {
            device_state_store.set_connection_checking(state.selected_bag_id)
        }

        try {
            val deviceStatus = RemoteDataSourceFactory.create_api(normalizedBaseUrl).device_status()
            val detail = build_success_detail(normalizedBaseUrl, deviceStatus, state.paired_bags.isNotEmpty())
            if (adopt_on_success) {
                apply_success(
                    state = state,
                    resolved_base_url = normalizedBaseUrl,
                    device_status = deviceStatus,
                    bag_id = state.selected_bag_id,
                    address_id = address_id
                )
            } else if (address_id != null) {
                device_state_store.update_saved_address_status(
                    address_id = address_id,
                    status = "Reachable",
                    detail = detail,
                    make_active = state.active_address_id == address_id
                )
            }
            return PairingConnectionResult(
                endpoint = normalizedBaseUrl,
                status = if (state.paired_bags.isNotEmpty()) "Pi Online" else "Pairing Ready",
                detail = detail
            )
        } catch (e: Exception) {
            val message = classify_connection_error(e)
            if (address_id != null) {
                device_state_store.update_saved_address_status(
                    address_id = address_id,
                    status = "Failed",
                    detail = message,
                    make_active = state.active_address_id == address_id
                )
            }
            if (shouldUpdateGlobalFailure) {
                device_state_store.set_connection_error(
                    message = message,
                    bag_id = state.selected_bag_id,
                    status = PiConnectionStatus.STATUS_ADDRESS_UNREACHABLE
                )
            }
            throw IllegalStateException(message, e)
        }
    }

    suspend fun adopt_successful_pairing_endpoint(
        base_url: String,
        bag_id: String,
        pi_device_id: String,
        device_status: DeviceStatusDto
    ) {
        val state = device_state_store.state.first()
        apply_success(
            state = state,
            resolved_base_url = base_url,
            device_status = device_status,
            bag_id = bag_id,
            address_id = null,
            explicit_pi_device_id = pi_device_id
        )
    }

    private suspend fun apply_success(
        state: DeviceState,
        resolved_base_url: String,
        device_status: DeviceStatusDto,
        bag_id: String,
        address_id: String? = null,
        explicit_pi_device_id: String = ""
    ) {
        val savedAddress = device_state_store.upsert_saved_address(
            base_url = resolved_base_url,
            address_id = address_id,
            make_active = true
        )
        val detail = build_success_detail(
            base_url = resolved_base_url,
            device_status = device_status,
            has_paired_bag = state.paired_bags.isNotEmpty() || bag_id.isNotBlank()
        )
        device_state_store.update_saved_address_status(
            address_id = savedAddress.id,
            status = "Reachable",
            detail = detail,
            make_active = true
        )

        val resolvedPiDeviceId = explicit_pi_device_id
            .ifBlank { device_status.pi_device_id }
            .ifBlank { state.pi_device_id }
        if (state.paired_bags.isNotEmpty() || bag_id.isNotBlank()) {
            device_state_store.update_paired_bag_endpoint(
                base_url = resolved_base_url,
                bag_id = bag_id.takeIf { it.isNotBlank() },
                pi_device_id = resolvedPiDeviceId.takeIf { it.isNotBlank() }
            )
        }

        device_state_store.set_connection_snapshot(
            status = device_status.connection_status,
            pendingChangesCount = device_status.pending_changes_count,
            localIp = device_status.local_ip,
            lastSyncAt = if (state.auth_token.isNotBlank() || bag_id.isNotBlank()) device_status.last_sync_at else null,
            bag_id = bag_id.takeIf { it.isNotBlank() }
        )
    }

    private fun build_candidate_endpoints(state: DeviceState): List<String> {
        val candidates = linkedSetOf<String>()
        fun add_candidate(value: String?) {
            val normalized = value
                ?.trim()
                ?.removeSuffix("/")
                .orEmpty()
            if (normalized.isNotBlank()) candidates += normalized
        }

        add_candidate(state.base_url)
        add_candidate(rebuild_endpoint_with_local_ip(state.base_url, state.local_ip))
        state.saved_addresses.filter { it.is_active }.forEach { add_candidate(it.base_url) }
        state.saved_addresses.forEach { add_candidate(it.base_url) }
        state.paired_bags.forEach { paired ->
            add_candidate(paired.base_url)
            add_candidate(rebuild_endpoint_with_local_ip(paired.base_url, state.local_ip))
        }
        return candidates.toList()
    }

    private fun build_refresh_failure_message(state: DeviceState, last_failure: String): String {
        return when {
            state.paired_bags.isNotEmpty() ->
                "Pi paired but offline. Address may be outdated. Reconnect required. ${last_failure.ifBlank { "Could not reach the Raspberry Pi." }}"
            state.saved_addresses.isNotEmpty() ->
                "Saved Raspberry Pi address is unreachable. ${last_failure.ifBlank { "Could not reach the Raspberry Pi." }}"
            else ->
                last_failure.ifBlank { "Could not refresh Raspberry Pi status." }
        }
    }
}

internal fun normalize_base_url(base_url: String): String {
    val normalizedBaseUrl = base_url.trim().removeSuffix("/")
    if (normalizedBaseUrl.isBlank()) {
        throw IllegalArgumentException("Enter the Raspberry Pi address first.")
    }
    if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
        throw IllegalArgumentException("Use a full address like http://192.168.1.20:8080.")
    }
    return normalizedBaseUrl
}

internal fun classify_connection_error(error: Exception): String {
    val message = error.message.orEmpty()
    return when {
        error is IllegalArgumentException -> message
        error is UnknownHostException -> "Raspberry Pi address not found. Check the IP address and that the phone is on the same network."
        error is ConnectException -> "Could not reach the Raspberry Pi. Address may be outdated or the Pi server is offline."
        error is SocketTimeoutException -> "Connection timed out. Address may be outdated or the Raspberry Pi is too slow to respond."
        error is HttpException -> {
            val detail = parse_fastapi_detail(error.response()?.errorBody()?.string().orEmpty())
            when {
                error.code() == 401 || error.code() == 403 -> "The Raspberry Pi rejected the request. Re-pair this phone and try again."
                error.code() == 404 -> "The Raspberry Pi responded, but the Go-Bag API path was not found. Check the Pi server URL."
                detail.isNotBlank() -> "Connection test failed: HTTP ${error.code()} ($detail)."
                else -> "Connection test failed: HTTP ${error.code()}."
            }
        }
        message.contains("CLEARTEXT communication", ignoreCase = true) ->
            "Android blocked an insecure HTTP request. Enable local HTTP access or use HTTPS on the Raspberry Pi."
        else -> "Connection test failed: ${message.ifBlank { "unknown network error" }}"
    }
}

private fun build_success_detail(
    base_url: String,
    device_status: DeviceStatusDto,
    has_paired_bag: Boolean
): String {
    val endpointDetail = if (device_status.local_ip.isBlank()) {
        base_url
    } else {
        device_status.local_ip
    }
    return if (has_paired_bag) {
        "Pi ${device_status.device_name} is online at $endpointDetail."
    } else {
        "Pi ${device_status.device_name} is online at $endpointDetail. Pairing is ready."
    }
}

private fun rebuild_endpoint_with_local_ip(base_url: String, local_ip: String): String? {
    if (base_url.isBlank() || local_ip.isBlank()) return null
    val uri = runCatching { URI(normalize_base_url(base_url)) }.getOrNull() ?: return null
    val scheme = uri.scheme?.ifBlank { "http" } ?: "http"
    val host = local_ip.trim()
    if (host.isBlank()) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.path?.trim()?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
    return "$scheme://$host$port$path".removeSuffix("/")
}

private fun parse_fastapi_detail(raw: String): String {
    if (raw.isBlank()) return ""
    val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return raw
    if (!parsed.isJsonObject) return raw
    val detail = parsed.asJsonObject.get("detail") ?: return raw
    return when {
        detail.isJsonPrimitive -> detail.asString
        else -> detail.toString()
    }
}
