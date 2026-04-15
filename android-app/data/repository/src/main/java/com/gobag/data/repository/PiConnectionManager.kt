package com.gobag.data.repository

import com.gobag.core.model.DeviceState
import com.gobag.core.model.PairedBagConnection
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

internal const val CONNECTION_MODE_LOCAL = "local"
internal const val CONNECTION_MODE_REMOTE = "remote"
internal const val CONNECTION_MODE_UNKNOWN = "unknown"

private enum class EndpointHostKind {
    LOCAL_PRIVATE,
    REMOTE_MESH,
    PUBLIC
}

private data class EndpointCandidate(
    val base_url: String,
    val connection_mode: String
)

class PiConnectionManager(
    private val device_state_store: DeviceStateStore
) {
    suspend fun refresh_current_connection(): String? {
        device_state_store.initialize_phone_device_id_if_missing()
        val state = device_state_store.state.first()
        val candidates = build_candidate_endpoints(state)
        if (candidates.isEmpty()) {
            return "No bag location is saved yet."
        }

        device_state_store.set_connection_checking(state.selected_bag_id)
        var lastFailure = ""
        for (candidate in candidates) {
            try {
                val deviceStatus = RemoteDataSourceFactory.create_api(candidate.base_url).device_status()
                ensure_matching_pi(state, deviceStatus)
                apply_success(
                    state = state,
                    resolved_base_url = candidate.base_url,
                    connection_mode = resolve_connection_mode(
                        base_url = candidate.base_url,
                        fallback_mode = candidate.connection_mode,
                        device_status = deviceStatus
                    ),
                    device_status = deviceStatus,
                    bag_id = state.selected_bag_id
                )
                return null
            } catch (e: Exception) {
                lastFailure = if (e is IllegalStateException && e.cause == null) {
                    e.message.orEmpty()
                } else {
                    classify_connection_error(e)
                }
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
                status = "Unavailable",
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
        val inferredMode = infer_endpoint_mode(normalizedBaseUrl, state)
        val shouldUpdateGlobalFailure = update_global_failure
            ?: state.base_url.isBlank()
            || state.base_url.equals(normalizedBaseUrl, ignoreCase = true)
            || (address_id != null && state.active_address_id == address_id)

        if (adopt_on_success || shouldUpdateGlobalFailure) {
            device_state_store.set_connection_checking(state.selected_bag_id)
        }

        try {
            val deviceStatus = RemoteDataSourceFactory.create_api(normalizedBaseUrl).device_status()
            ensure_matching_pi(state, deviceStatus)
            val resolvedMode = resolve_connection_mode(normalizedBaseUrl, inferredMode, deviceStatus)
            val detail = build_success_detail(
                connection_mode = resolvedMode,
                has_paired_bag = state.paired_bags.isNotEmpty()
            )
            if (adopt_on_success) {
                apply_success(
                    state = state,
                    resolved_base_url = normalizedBaseUrl,
                    connection_mode = resolvedMode,
                    device_status = deviceStatus,
                    bag_id = state.selected_bag_id,
                    address_id = address_id
                )
            } else if (address_id != null) {
                device_state_store.update_saved_address_status(
                    address_id = address_id,
                    status = "Available",
                    detail = detail,
                    make_active = state.active_address_id == address_id
                )
            }
            return PairingConnectionResult(
                endpoint = normalizedBaseUrl,
                status = build_connection_status_label(
                    connection_mode = resolvedMode,
                    has_paired_bag = state.paired_bags.isNotEmpty()
                ),
                detail = detail
            )
        } catch (e: Exception) {
            val message = if (e is IllegalStateException && e.cause == null) {
                e.message.orEmpty()
            } else {
                classify_connection_error(e)
            }
            if (address_id != null) {
                device_state_store.update_saved_address_status(
                    address_id = address_id,
                    status = "Unavailable",
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
        device_status: DeviceStatusDto,
        hinted_remote_base_url: String? = null
    ) {
        val state = device_state_store.state.first()
        apply_success(
            state = state,
            resolved_base_url = normalize_base_url(base_url),
            connection_mode = resolve_connection_mode(
                base_url = base_url,
                fallback_mode = infer_endpoint_mode(base_url, state),
                device_status = device_status,
                hinted_remote_base_url = hinted_remote_base_url
            ),
            device_status = device_status,
            bag_id = bag_id,
            address_id = null,
            explicit_pi_device_id = pi_device_id,
            hinted_remote_base_url = hinted_remote_base_url
        )
    }

    private suspend fun apply_success(
        state: DeviceState,
        resolved_base_url: String,
        connection_mode: String,
        device_status: DeviceStatusDto,
        bag_id: String,
        address_id: String? = null,
        explicit_pi_device_id: String = "",
        hinted_remote_base_url: String? = null
    ) {
        val normalizedLocalBaseUrl = normalize_optional_base_url(device_status.local_base_url).ifBlank {
            state.local_base_url.takeIf { it.isNotBlank() } ?: state.paired_bags.first_matching_bag(bag_id, explicit_pi_device_id)?.local_base_url.orEmpty()
        }
        val normalizedRemoteBaseUrl = normalize_optional_base_url(hinted_remote_base_url)
            .ifBlank { normalize_optional_base_url(device_status.remote_base_url) }
            .ifBlank { state.remote_base_url.takeIf { it.isNotBlank() } ?: state.paired_bags.first_matching_bag(bag_id, explicit_pi_device_id)?.remote_base_url.orEmpty() }
        val savedAddress = device_state_store.upsert_saved_address(
            base_url = resolved_base_url,
            address_id = address_id,
            make_active = true
        )
        maybe_save_discovered_endpoint(normalizedLocalBaseUrl, active = connection_mode == CONNECTION_MODE_LOCAL)
        maybe_save_discovered_endpoint(normalizedRemoteBaseUrl, active = connection_mode == CONNECTION_MODE_REMOTE)

        val detail = build_success_detail(
            connection_mode = connection_mode,
            has_paired_bag = state.paired_bags.isNotEmpty() || bag_id.isNotBlank()
        )
        device_state_store.update_saved_address_status(
            address_id = savedAddress.id,
            status = "Available",
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
                pi_device_id = resolvedPiDeviceId.takeIf { it.isNotBlank() },
                local_base_url = normalizedLocalBaseUrl.takeIf { it.isNotBlank() },
                remote_base_url = normalizedRemoteBaseUrl.takeIf { it.isNotBlank() },
                last_connection_mode = connection_mode
            )
        }

        device_state_store.set_connection_snapshot(
            status = device_status.connection_status,
            pendingChangesCount = device_status.pending_changes_count,
            localIp = device_status.local_ip,
            lastSyncAt = if (state.auth_token.isNotBlank() || bag_id.isNotBlank()) device_status.last_sync_at else null,
            bag_id = bag_id.takeIf { it.isNotBlank() },
            resolvedBaseUrl = resolved_base_url,
            localBaseUrl = normalizedLocalBaseUrl.takeIf { it.isNotBlank() },
            remoteBaseUrl = normalizedRemoteBaseUrl.takeIf { it.isNotBlank() },
            connectionMode = connection_mode
        )
    }

    private suspend fun maybe_save_discovered_endpoint(base_url: String, active: Boolean) {
        if (base_url.isBlank()) return
        device_state_store.upsert_saved_address(
            base_url = base_url,
            make_active = active
        )
    }

    private fun build_candidate_endpoints(state: DeviceState): List<EndpointCandidate> {
        val localCandidates = linkedMapOf<String, EndpointCandidate>()
        val remoteCandidates = linkedMapOf<String, EndpointCandidate>()

        fun add_candidate(bucket: MutableMap<String, EndpointCandidate>, raw_value: String?, connection_mode: String) {
            val normalized = normalize_optional_base_url(raw_value)
            if (normalized.isBlank()) return
            if (connection_mode == CONNECTION_MODE_REMOTE && !is_secure_remote_candidate(normalized)) return
            bucket.putIfAbsent(normalized, EndpointCandidate(normalized, connection_mode))
        }

        fun add_saved_candidates(addresses: List<String>) {
            addresses.forEach { baseUrl ->
                when (infer_endpoint_mode(baseUrl, state)) {
                    CONNECTION_MODE_REMOTE -> add_candidate(remoteCandidates, baseUrl, CONNECTION_MODE_REMOTE)
                    else -> add_candidate(localCandidates, baseUrl, CONNECTION_MODE_LOCAL)
                }
            }
        }

        add_candidate(localCandidates, state.local_base_url, CONNECTION_MODE_LOCAL)
        add_candidate(localCandidates, rebuild_endpoint_with_local_ip(state.local_base_url.ifBlank { state.base_url }, state.local_ip), CONNECTION_MODE_LOCAL)
        if (infer_endpoint_mode(state.base_url, state) != CONNECTION_MODE_REMOTE) {
            add_candidate(localCandidates, state.base_url, CONNECTION_MODE_LOCAL)
        }
        add_saved_candidates(state.saved_addresses.filter { it.is_active }.map { it.base_url })
        state.paired_bags.forEach { paired ->
            add_candidate(localCandidates, paired.local_base_url, CONNECTION_MODE_LOCAL)
            add_candidate(
                localCandidates,
                rebuild_endpoint_with_local_ip(
                    paired.local_base_url ?: paired.base_url,
                    paired.local_ip.takeIf { it.isNotBlank() } ?: state.local_ip
                ),
                CONNECTION_MODE_LOCAL
            )
            if (infer_endpoint_mode(paired.base_url, state) != CONNECTION_MODE_REMOTE) {
                add_candidate(localCandidates, paired.base_url, CONNECTION_MODE_LOCAL)
            }
        }
        add_saved_candidates(state.saved_addresses.map { it.base_url })

        add_candidate(remoteCandidates, state.remote_base_url, CONNECTION_MODE_REMOTE)
        add_candidate(remoteCandidates, derive_relay_base_url(state.pi_device_id), CONNECTION_MODE_REMOTE)
        if (infer_endpoint_mode(state.base_url, state) == CONNECTION_MODE_REMOTE) {
            add_candidate(remoteCandidates, state.base_url, CONNECTION_MODE_REMOTE)
        }
        state.paired_bags.forEach { paired ->
            add_candidate(remoteCandidates, paired.remote_base_url, CONNECTION_MODE_REMOTE)
            add_candidate(remoteCandidates, derive_relay_base_url(paired.pi_device_id), CONNECTION_MODE_REMOTE)
            if (infer_endpoint_mode(paired.base_url, state) == CONNECTION_MODE_REMOTE) {
                add_candidate(remoteCandidates, paired.base_url, CONNECTION_MODE_REMOTE)
            }
        }

        return (localCandidates.values + remoteCandidates.values)
    }

    private fun build_refresh_failure_message(state: DeviceState, last_failure: String): String {
        val hasRemotePath = state.remote_base_url.isNotBlank() || state.paired_bags.any { !it.remote_base_url.isNullOrBlank() }
        return when {
            state.paired_bags.isNotEmpty() && hasRemotePath ->
                "Your bag is offline right now on both its local and remote links. ${last_failure.ifBlank { "Try reconnecting." }}"
            state.paired_bags.isNotEmpty() ->
                "Your bag is offline right now. ${last_failure.ifBlank { "Try reconnecting." }}"
            state.saved_addresses.isNotEmpty() ->
                "We could not reach the saved bag location. ${last_failure.ifBlank { "Try again." }}"
            else ->
                last_failure.ifBlank { "We could not refresh your bag status." }
        }
    }

    private fun ensure_matching_pi(state: DeviceState, device_status: DeviceStatusDto) {
        val expectedPiDeviceId = state.pi_device_id.ifBlank {
            state.paired_bags.firstOrNull()?.pi_device_id.orEmpty()
        }
        val actualPiDeviceId = device_status.pi_device_id.trim()
        if (expectedPiDeviceId.isBlank() || actualPiDeviceId.isBlank()) return
        if (!expectedPiDeviceId.equals(actualPiDeviceId, ignoreCase = true)) {
            throw IllegalStateException("That address belongs to a different GO BAG.")
        }
    }

    private fun resolve_connection_mode(
        base_url: String,
        fallback_mode: String,
        device_status: DeviceStatusDto,
        hinted_remote_base_url: String? = null
    ): String {
        val normalizedBaseUrl = normalize_optional_base_url(base_url)
        val normalizedLocalBaseUrl = normalize_optional_base_url(device_status.local_base_url)
        val normalizedRemoteBaseUrl = normalize_optional_base_url(hinted_remote_base_url)
            .ifBlank { normalize_optional_base_url(device_status.remote_base_url) }

        return when {
            urls_equivalent(normalizedBaseUrl, normalizedLocalBaseUrl) -> CONNECTION_MODE_LOCAL
            urls_equivalent(normalizedBaseUrl, normalizedRemoteBaseUrl) -> CONNECTION_MODE_REMOTE
            fallback_mode == CONNECTION_MODE_REMOTE && is_secure_remote_candidate(normalizedBaseUrl) -> CONNECTION_MODE_REMOTE
            looks_like_local_endpoint(normalizedBaseUrl) -> CONNECTION_MODE_LOCAL
            is_secure_remote_candidate(normalizedBaseUrl) -> CONNECTION_MODE_REMOTE
            else -> fallback_mode
        }
    }
}

internal fun normalize_base_url(base_url: String): String {
    val normalizedBaseUrl = base_url.trim().removeSuffix("/")
    if (normalizedBaseUrl.isBlank()) {
        throw IllegalArgumentException("Enter the bag location first.")
    }
    if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
        throw IllegalArgumentException("Use a full location like http://192.168.1.20:8080.")
    }
    return normalizedBaseUrl
}

internal fun classify_connection_error(error: Exception): String {
    val message = error.message.orEmpty()
    return when {
        error is IllegalArgumentException -> message
        error is UnknownHostException -> "We could not find that bag location. Check it and try again."
        error is ConnectException -> "We could not reach your bag. Make sure it is on and either on the same Wi-Fi or reachable through its secure remote link."
        error is SocketTimeoutException -> "Your bag took too long to respond. Try again in a moment."
        error is HttpException -> {
            val detail = parse_fastapi_detail(error.response()?.errorBody()?.string().orEmpty())
            when {
                error.code() == 401 || error.code() == 403 -> "This phone needs to reconnect to the bag."
                error.code() == 404 -> "That saved bag location is not working anymore. Check it and try again."
                detail.isNotBlank() -> "We could not connect to the bag. $detail"
                else -> "We could not connect to the bag right now."
            }
        }
        message.contains("CLEARTEXT communication", ignoreCase = true) ->
            "This phone cannot open that bag location. Try a local network location or a secure remote address."
        else -> "We could not connect to the bag. ${message.ifBlank { "Try again." }}"
    }
}

private fun build_connection_status_label(connection_mode: String, has_paired_bag: Boolean): String {
    return when {
        !has_paired_bag -> "Ready to connect"
        connection_mode == CONNECTION_MODE_REMOTE -> "Online (Remote)"
        else -> "Online (Local)"
    }
}

private fun build_success_detail(
    connection_mode: String,
    has_paired_bag: Boolean
): String {
    return when {
        has_paired_bag && connection_mode == CONNECTION_MODE_REMOTE ->
            "Your bag is online through its remote link."
        has_paired_bag ->
            "Your bag is online on this Wi-Fi."
        connection_mode == CONNECTION_MODE_REMOTE ->
            "We found the bag remotely. You can connect now."
        else ->
            "We found the bag hub. You can connect now."
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

private fun normalize_optional_base_url(base_url: String?): String {
    return base_url
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { normalize_base_url(it) }.getOrNull() }
        .orEmpty()
}

private fun derive_relay_base_url(pi_device_id: String): String {
    val relayBaseUrl = normalize_optional_base_url(BuildConfig.GOBAG_RELAY_BASE_URL)
    val normalizedPiDeviceId = pi_device_id.trim()
    if (relayBaseUrl.isBlank() || normalizedPiDeviceId.isBlank()) return ""
    return "${relayBaseUrl.removeSuffix("/")}/r/$normalizedPiDeviceId"
}

private fun urls_equivalent(left: String, right: String): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    return left.equals(right, ignoreCase = true)
}

private fun infer_endpoint_mode(base_url: String, state: DeviceState): String {
    val normalizedBaseUrl = normalize_optional_base_url(base_url)
    if (normalizedBaseUrl.isBlank()) return CONNECTION_MODE_UNKNOWN
    if (urls_equivalent(normalizedBaseUrl, state.local_base_url)) return CONNECTION_MODE_LOCAL
    if (urls_equivalent(normalizedBaseUrl, state.remote_base_url)) return CONNECTION_MODE_REMOTE
    state.paired_bags.forEach { paired ->
        if (urls_equivalent(normalizedBaseUrl, paired.local_base_url.orEmpty())) return CONNECTION_MODE_LOCAL
        if (urls_equivalent(normalizedBaseUrl, paired.remote_base_url.orEmpty())) return CONNECTION_MODE_REMOTE
    }

    val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull() ?: return CONNECTION_MODE_UNKNOWN
    if (uri.scheme.equals("https", ignoreCase = true)) return CONNECTION_MODE_REMOTE
    return when (classify_endpoint_host(uri.host.orEmpty())) {
        EndpointHostKind.LOCAL_PRIVATE -> CONNECTION_MODE_LOCAL
        EndpointHostKind.REMOTE_MESH -> CONNECTION_MODE_REMOTE
        EndpointHostKind.PUBLIC -> CONNECTION_MODE_REMOTE
    }
}

private fun classify_endpoint_host(host: String): EndpointHostKind {
    val normalizedHost = host.trim().trim('[', ']').lowercase()
    if (normalizedHost.isBlank()) return EndpointHostKind.PUBLIC
    if (normalizedHost.endsWith(".ts.net")) return EndpointHostKind.REMOTE_MESH
    if (normalizedHost.endsWith(".local") || normalizedHost.endsWith(".lan") || !normalizedHost.contains('.')) {
        return EndpointHostKind.LOCAL_PRIVATE
    }

    if (normalizedHost.contains(':')) {
        return when {
            normalizedHost == "::1" || normalizedHost.startsWith("fe80:") -> EndpointHostKind.LOCAL_PRIVATE
            normalizedHost.startsWith("fc") || normalizedHost.startsWith("fd") -> EndpointHostKind.REMOTE_MESH
            else -> EndpointHostKind.PUBLIC
        }
    }

    val octets = normalizedHost.split('.')
    if (octets.size != 4) return EndpointHostKind.PUBLIC
    val parts = octets.map { it.toIntOrNull() ?: return EndpointHostKind.PUBLIC }
    return when {
        parts[0] == 10 -> EndpointHostKind.LOCAL_PRIVATE
        parts[0] == 127 -> EndpointHostKind.LOCAL_PRIVATE
        parts[0] == 169 && parts[1] == 254 -> EndpointHostKind.LOCAL_PRIVATE
        parts[0] == 172 && parts[1] in 16..31 -> EndpointHostKind.LOCAL_PRIVATE
        parts[0] == 192 && parts[1] == 168 -> EndpointHostKind.LOCAL_PRIVATE
        parts[0] == 100 && parts[1] in 64..127 -> EndpointHostKind.REMOTE_MESH
        else -> EndpointHostKind.PUBLIC
    }
}

private fun is_secure_remote_candidate(base_url: String): Boolean {
    val normalizedBaseUrl = normalize_optional_base_url(base_url)
    if (normalizedBaseUrl.isBlank()) return false
    val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull() ?: return false
    if (uri.scheme.equals("https", ignoreCase = true)) return true
    return classify_endpoint_host(uri.host.orEmpty()) == EndpointHostKind.REMOTE_MESH
}

private fun looks_like_local_endpoint(base_url: String): Boolean {
    val normalizedBaseUrl = normalize_optional_base_url(base_url)
    if (normalizedBaseUrl.isBlank()) return false
    val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull() ?: return false
    return classify_endpoint_host(uri.host.orEmpty()) == EndpointHostKind.LOCAL_PRIVATE
}

private fun List<PairedBagConnection>.first_matching_bag(bag_id: String, pi_device_id: String): PairedBagConnection? {
    return firstOrNull {
        (bag_id.isNotBlank() && it.bag_id == bag_id) ||
            (pi_device_id.isNotBlank() && it.pi_device_id == pi_device_id)
    }
}
