package com.gobag.domain.logic

import com.gobag.core.model.DeviceState
import java.util.Locale

enum class PiPairingState {
    NO_PI_PAIRED,
    PI_PAIRED
}

enum class PiReachabilityState {
    UNKNOWN,
    CHECKING_CONNECTION,
    PI_ONLINE,
    PI_OFFLINE,
    ADDRESS_UNREACHABLE
}

enum class PiSyncState {
    IDLE,
    SYNCING,
    SYNC_FAILED
}

enum class PiDiscoveryState {
    NONE,
    PAIRING_READY
}

data class PiConnectionSnapshot(
    val pairing_state: PiPairingState,
    val reachability_state: PiReachabilityState,
    val sync_state: PiSyncState,
    val discovery_state: PiDiscoveryState,
    val primary_label: String,
    val connection_label: String,
    val pairing_label: String,
    val discovery_label: String,
    val detail: String,
    val active_endpoint: String,
    val local_ip: String,
    val pending_changes_count: Int,
    val last_connection_error: String,
    val last_sync_error: String,
    val last_checked_at: Long,
    val last_connected_at: Long,
    val last_sync_at: Long,
    val is_paired: Boolean,
    val is_online: Boolean,
    val is_offline: Boolean,
    val address_needs_attention: Boolean,
    val reconnect_required: Boolean,
    val can_sync: Boolean
)

object PiConnectionStatus {
    const val STATUS_NO_PI_PAIRED = "no_pi_paired"
    const val STATUS_ADDRESS_SAVED = "address_saved"
    const val STATUS_PI_PAIRED = "pi_paired"
    const val STATUS_CHECKING_CONNECTION = "checking_connection"
    const val STATUS_PI_ONLINE = "pi_online"
    const val STATUS_PI_OFFLINE = "pi_offline"
    const val STATUS_ADDRESS_UNREACHABLE = "address_unreachable"

    const val SYNC_STATUS_IDLE = "idle"
    const val SYNC_STATUS_SYNCING = "syncing"
    const val SYNC_STATUS_FAILED = "sync_failed"

    fun empty(): PiConnectionSnapshot = PiConnectionSnapshot(
        pairing_state = PiPairingState.NO_PI_PAIRED,
        reachability_state = PiReachabilityState.UNKNOWN,
        sync_state = PiSyncState.IDLE,
        discovery_state = PiDiscoveryState.NONE,
        primary_label = "No Pi Paired",
        connection_label = "No Pi Paired",
        pairing_label = "No Pi Paired",
        discovery_label = "",
        detail = "No Raspberry Pi has been paired with this phone yet.",
        active_endpoint = "",
        local_ip = "",
        pending_changes_count = 0,
        last_connection_error = "",
        last_sync_error = "",
        last_checked_at = 0L,
        last_connected_at = 0L,
        last_sync_at = 0L,
        is_paired = false,
        is_online = false,
        is_offline = false,
        address_needs_attention = false,
        reconnect_required = false,
        can_sync = false
    )

    fun normalize_connection_status(value: String?, is_paired: Boolean, has_saved_address: Boolean): String {
        val normalized = value
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(' ', '_')
        return when (normalized) {
            STATUS_NO_PI_PAIRED -> STATUS_NO_PI_PAIRED
            STATUS_ADDRESS_SAVED -> STATUS_ADDRESS_SAVED
            STATUS_PI_PAIRED, "paired" -> if (is_paired) STATUS_PI_PAIRED else fallback_status(is_paired, has_saved_address)
            STATUS_CHECKING_CONNECTION, "checking", "refreshing" -> STATUS_CHECKING_CONNECTION
            STATUS_PI_ONLINE, "online", "reachable", "connected", "synced", "waiting_for_pair" -> STATUS_PI_ONLINE
            STATUS_PI_OFFLINE, "offline", "failed", "error" -> STATUS_PI_OFFLINE
            STATUS_ADDRESS_UNREACHABLE, "unreachable", "address_outdated" -> STATUS_ADDRESS_UNREACHABLE
            "", "unknown" -> fallback_status(is_paired, has_saved_address)
            else -> fallback_status(is_paired, has_saved_address)
        }
    }

    fun normalize_sync_status(value: String?): String {
        return when (value.orEmpty().trim().lowercase(Locale.ROOT).replace(' ', '_')) {
            SYNC_STATUS_SYNCING -> SYNC_STATUS_SYNCING
            SYNC_STATUS_FAILED, "failed", "error" -> SYNC_STATUS_FAILED
            else -> SYNC_STATUS_IDLE
        }
    }

    fun from_device_state(state: DeviceState): PiConnectionSnapshot {
        val isPaired = state.paired_bags.isNotEmpty()
        val hasSavedAddress = state.saved_addresses.isNotEmpty() || state.base_url.isNotBlank()
        val connectionStatus = normalize_connection_status(
            value = state.connection_status,
            is_paired = isPaired,
            has_saved_address = hasSavedAddress
        )
        val syncStatus = normalize_sync_status(state.sync_status)
        val pairingState = if (isPaired) PiPairingState.PI_PAIRED else PiPairingState.NO_PI_PAIRED
        val reachabilityState = when (connectionStatus) {
            STATUS_CHECKING_CONNECTION -> PiReachabilityState.CHECKING_CONNECTION
            STATUS_PI_ONLINE -> PiReachabilityState.PI_ONLINE
            STATUS_PI_OFFLINE -> PiReachabilityState.PI_OFFLINE
            STATUS_ADDRESS_UNREACHABLE -> PiReachabilityState.ADDRESS_UNREACHABLE
            else -> PiReachabilityState.UNKNOWN
        }
        val discoveryState = if (!isPaired && reachabilityState == PiReachabilityState.PI_ONLINE) {
            PiDiscoveryState.PAIRING_READY
        } else {
            PiDiscoveryState.NONE
        }
        val syncState = when (syncStatus) {
            SYNC_STATUS_SYNCING -> PiSyncState.SYNCING
            SYNC_STATUS_FAILED -> PiSyncState.SYNC_FAILED
            else -> PiSyncState.IDLE
        }
        val connectionLabel = when (reachabilityState) {
            PiReachabilityState.CHECKING_CONNECTION -> "Connecting..."
            PiReachabilityState.PI_ONLINE -> "Pi Online"
            PiReachabilityState.PI_OFFLINE -> "Pi Offline"
            PiReachabilityState.ADDRESS_UNREACHABLE -> "Address Unreachable"
            PiReachabilityState.UNKNOWN -> when {
                isPaired -> "Pi Paired"
                hasSavedAddress -> "Address Saved"
                else -> "No Pi Paired"
            }
        }
        val pairingLabel = if (isPaired) "Pi Paired" else "No Pi Paired"
        val discoveryLabel = if (discoveryState == PiDiscoveryState.PAIRING_READY) "Pairing Ready" else ""
        val primaryLabel = when {
            syncState == PiSyncState.SYNCING -> "Syncing"
            syncState == PiSyncState.SYNC_FAILED -> "Sync Failed"
            reachabilityState == PiReachabilityState.CHECKING_CONNECTION -> "Connecting..."
            discoveryState == PiDiscoveryState.PAIRING_READY -> "Pairing Ready"
            reachabilityState == PiReachabilityState.PI_ONLINE -> "Pi Online"
            isPaired && reachabilityState in setOf(PiReachabilityState.PI_OFFLINE, PiReachabilityState.ADDRESS_UNREACHABLE) -> "Pi Paired but Offline"
            isPaired -> "Pi Paired"
            hasSavedAddress -> "Address Saved"
            else -> "No Pi Paired"
        }
        val detail = when {
            syncState == PiSyncState.SYNCING -> "Syncing with the Raspberry Pi now."
            syncState == PiSyncState.SYNC_FAILED && state.last_sync_error.isNotBlank() -> state.last_sync_error
            syncState == PiSyncState.SYNC_FAILED -> "The last sync attempt failed."
            reachabilityState == PiReachabilityState.CHECKING_CONNECTION -> "Checking the saved Raspberry Pi address."
            reachabilityState == PiReachabilityState.PI_ONLINE && state.local_ip.isNotBlank() ->
                "Raspberry Pi reachable at ${state.local_ip}."
            reachabilityState == PiReachabilityState.PI_ONLINE && state.base_url.isNotBlank() ->
                "Raspberry Pi reachable at ${state.base_url}."
            reachabilityState == PiReachabilityState.PI_ONLINE -> "Raspberry Pi reachable right now."
            reachabilityState == PiReachabilityState.ADDRESS_UNREACHABLE && state.last_connection_error.isNotBlank() ->
                state.last_connection_error
            reachabilityState == PiReachabilityState.ADDRESS_UNREACHABLE ->
                "The saved Raspberry Pi address may be outdated or unreachable. Reconnect required."
            reachabilityState == PiReachabilityState.PI_OFFLINE && state.last_connection_error.isNotBlank() ->
                state.last_connection_error
            reachabilityState == PiReachabilityState.PI_OFFLINE ->
                "The Raspberry Pi is currently offline."
            discoveryState == PiDiscoveryState.PAIRING_READY ->
                "Raspberry Pi is reachable and ready for pairing."
            isPaired ->
                "Saved pairing credentials exist, but the live connection has not been confirmed yet."
            hasSavedAddress ->
                "A Raspberry Pi address is saved. Pair a bag to enable sync."
            else ->
                "No Raspberry Pi has been paired with this phone yet."
        }
        val isOnline = reachabilityState == PiReachabilityState.PI_ONLINE
        val addressNeedsAttention = reachabilityState == PiReachabilityState.ADDRESS_UNREACHABLE
        val isOffline = reachabilityState in setOf(
            PiReachabilityState.PI_OFFLINE,
            PiReachabilityState.ADDRESS_UNREACHABLE
        )
        val reconnectRequired = isPaired && isOffline
        return PiConnectionSnapshot(
            pairing_state = pairingState,
            reachability_state = reachabilityState,
            sync_state = syncState,
            discovery_state = discoveryState,
            primary_label = primaryLabel,
            connection_label = connectionLabel,
            pairing_label = pairingLabel,
            discovery_label = discoveryLabel,
            detail = detail,
            active_endpoint = state.base_url,
            local_ip = state.local_ip,
            pending_changes_count = state.pending_changes_count,
            last_connection_error = state.last_connection_error,
            last_sync_error = state.last_sync_error,
            last_checked_at = state.last_connection_check_at,
            last_connected_at = state.last_connected_at,
            last_sync_at = state.last_sync_at,
            is_paired = isPaired,
            is_online = isOnline,
            is_offline = isOffline,
            address_needs_attention = addressNeedsAttention,
            reconnect_required = reconnectRequired,
            can_sync = isPaired && isOnline && syncState != PiSyncState.SYNCING
        )
    }

    private fun fallback_status(is_paired: Boolean, has_saved_address: Boolean): String {
        return when {
            is_paired -> STATUS_PI_PAIRED
            has_saved_address -> STATUS_ADDRESS_SAVED
            else -> STATUS_NO_PI_PAIRED
        }
    }
}
