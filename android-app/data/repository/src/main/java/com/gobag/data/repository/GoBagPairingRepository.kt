package com.gobag.data.repository

import com.gobag.core.model.BagProfile
import com.gobag.core.model.SavedPiAddress
import com.gobag.data.local.RecommendedItemDao
import com.gobag.data.local.to_entity
import com.gobag.data.remote.PairRequestDto
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.data.remote.to_model
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.PairingConnectionResult
import com.gobag.domain.repository.PairingRepository
import com.gobag.domain.repository.PairingSetupResult
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.flow.first
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GoBagPairingRepository(
    private val device_state_store: DeviceStateStore,
    private val recommended_item_dao: RecommendedItemDao,
    private val item_repository: ItemRepository,
    private val sync_repository: SyncRepository
) : PairingRepository {
    override suspend fun pair_from_qr_payload(payload_json: String): PairingSetupResult {
        device_state_store.initialize_phone_device_id_if_missing()
        val payload = PairQrParser.parse(payload_json)
        val normalizedBaseUrl = normalize_base_url(payload.base_url)
        test_connection(normalizedBaseUrl)
        val state = device_state_store.state.first()
        val alreadyPaired = state.paired_bags.any { it.bag_id == payload.bag_id }
        val api = RemoteDataSourceFactory.create_api(normalizedBaseUrl)
        val phone_time_ms = System.currentTimeMillis()
        val pair = api.pair(PairRequestDto(phone_device_id = state.phone_device_id, pair_code = payload.pair_code))

        item_repository.upsert_bag(
            BagProfile(
                bag_id = payload.bag_id,
                name = payload.bag_name.trim(),
                size_liters = payload.size_liters,
                template_id = payload.template_id.ifBlank { template_id_for_size(payload.size_liters) },
                updated_at = phone_time_ms,
                updated_by = payload.pi_device_id.ifBlank { state.phone_device_id }
            )
        )

        val savedAddress = device_state_store.upsert_saved_address(normalizedBaseUrl, make_active = true)
        device_state_store.upsert_paired_bag_connection(
            bag_id = payload.bag_id,
            auth_token = pair.auth_token,
            base_url = normalizedBaseUrl,
            pi_device_id = pair.pi_device_id,
            time_offset_ms = pair.server_time_ms - phone_time_ms
        )
        device_state_store.set_selected_bag_id(payload.bag_id)

        val deviceStatus = api.device_status()
        device_state_store.update_saved_address_status(
            address_id = savedAddress.id,
            status = "Reachable",
            detail = if (deviceStatus.local_ip.isBlank()) {
                "Pi ${deviceStatus.device_name} is ${deviceStatus.connection_status}."
            } else {
                "Pi ${deviceStatus.device_name} is ${deviceStatus.connection_status} at ${deviceStatus.local_ip}."
            },
            make_active = true
        )
        device_state_store.set_connection_snapshot(
            status = deviceStatus.connection_status,
            pendingChangesCount = deviceStatus.pending_changes_count,
            localIp = deviceStatus.local_ip,
            bag_id = payload.bag_id
        )
        val templates = api.templates().templates.map { it.to_model().to_entity() }
        if (templates.isNotEmpty()) recommended_item_dao.upsert_all(templates)

        return try {
            sync_repository.run_sync_now()
            PairingSetupResult(
                endpoint = normalizedBaseUrl,
                initial_sync_completed = true,
                detail = if (alreadyPaired) {
                    "${payload.bag_name} was already paired. Connection details were refreshed and inventory re-synced."
                } else {
                    "${payload.bag_name} paired successfully and its inventory downloaded."
                }
            )
        } catch (e: Exception) {
            device_state_store.set_connection_error(
                message = "Bag pairing succeeded, but the first inventory sync failed.",
                bag_id = payload.bag_id
            )
            PairingSetupResult(
                endpoint = normalizedBaseUrl,
                initial_sync_completed = false,
                detail = if (alreadyPaired) {
                    "${payload.bag_name} was refreshed, but the inventory sync failed. Open Sync to retry."
                } else {
                    "${payload.bag_name} paired successfully, but the first inventory sync failed. Open Sync to retry."
                }
            )
        }
    }

    override suspend fun test_connection(base_url: String): PairingConnectionResult {
        val normalizedBaseUrl = normalize_base_url(base_url)
        return try {
            val status = RemoteDataSourceFactory.create_api(normalizedBaseUrl).device_status()
            PairingConnectionResult(
                endpoint = normalizedBaseUrl,
                status = "Reachable",
                detail = if (status.local_ip.isBlank()) {
                    "Pi ${status.device_name} is ${status.connection_status}. Scan a bag QR to pair this phone."
                } else {
                    "Pi ${status.device_name} is ${status.connection_status} at ${status.local_ip}."
                }
            )
        } catch (e: Exception) {
            throw IllegalStateException(classify_connection_error(e), e)
        }
    }

    override suspend fun save_endpoint(base_url: String, address_id: String?): SavedPiAddress {
        val normalizedBaseUrl = normalize_base_url(base_url)
        return device_state_store.upsert_saved_address(
            base_url = normalizedBaseUrl,
            address_id = address_id,
            make_active = true
        )
    }

    override suspend fun delete_endpoint(address_id: String) {
        device_state_store.delete_saved_address(address_id)
    }

    override suspend fun set_active_endpoint(address_id: String) {
        device_state_store.set_active_address(address_id)
    }

    override suspend fun refresh_endpoint(address_id: String, base_url: String): PairingConnectionResult {
        val result = test_connection(base_url)
        device_state_store.update_saved_address_status(
            address_id = address_id,
            status = result.status,
            detail = result.detail,
            make_active = true
        )
        return result
    }

    override suspend fun unpair_bag(bag_id: String) {
        device_state_store.clear_pairing_for_bag(bag_id)
    }

    private fun normalize_base_url(base_url: String): String {
        val normalizedBaseUrl = base_url.trim().removeSuffix("/")
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("Enter the Raspberry Pi address first.")
        }
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            throw IllegalArgumentException("Use a full address like http://192.168.1.20:8080.")
        }
        return normalizedBaseUrl
    }

    private fun classify_connection_error(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            error is IllegalArgumentException -> message
            error is UnknownHostException -> "Raspberry Pi address not found. Check the IP address and that the phone is on the same network."
            error is ConnectException -> "Could not reach the Raspberry Pi. Check that the Pi server is running and the port is correct."
            error is SocketTimeoutException -> "Connection timed out. The Raspberry Pi is too slow to respond or not reachable from this phone."
            message.contains("CLEARTEXT communication", ignoreCase = true) ->
                "Android blocked an insecure HTTP request. Enable local HTTP access or use HTTPS on the Raspberry Pi."
            message.contains("401") || message.contains("403") ->
                "The Raspberry Pi rejected the request. Recreate the QR code and pair again."
            message.contains("404") ->
                "The Raspberry Pi responded, but the Go-Bag API path was not found. Check the Pi server URL."
            else -> "Connection test failed: ${message.ifBlank { "unknown network error" }}"
        }
    }

    private fun template_id_for_size(liters: Int): String = when (liters) {
        25 -> "template_25l"
        44 -> "template_44l"
        66 -> "template_66l"
        else -> "template_44l"
    }
}
