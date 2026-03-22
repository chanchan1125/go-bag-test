package com.gobag.data.repository

import com.gobag.data.local.RecommendedItemDao
import com.gobag.data.local.to_entity
import com.gobag.data.remote.PairRequestDto
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.data.remote.to_model
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
    private val sync_repository: SyncRepository
) : PairingRepository {
    override suspend fun pair_from_qr_payload(payload_json: String): PairingSetupResult {
        device_state_store.initialize_phone_device_id_if_missing()
        val payload = PairQrParser.parse(payload_json)
        test_connection(payload.base_url)
        val state = device_state_store.state.first()
        val api = RemoteDataSourceFactory.create_api(payload.base_url)
        val phone_time_ms = System.currentTimeMillis()
        val pair = api.pair(PairRequestDto(phone_device_id = state.phone_device_id, pair_code = payload.pair_code))
        device_state_store.update_pairing(
            auth_token = pair.auth_token,
            base_url = payload.base_url,
            pi_device_id = pair.pi_device_id,
            time_offset_ms = pair.server_time_ms - phone_time_ms
        )
        val deviceStatus = api.device_status()
        device_state_store.set_connection_snapshot(
            status = deviceStatus.connection_status,
            pendingChangesCount = deviceStatus.pending_changes_count,
            localIp = deviceStatus.local_ip
        )
        val templates = api.templates().templates.map { it.to_model().to_entity() }
        if (templates.isNotEmpty()) recommended_item_dao.upsert_all(templates)
        return try {
            sync_repository.run_sync_now()
            PairingSetupResult(
                endpoint = payload.base_url,
                initial_sync_completed = true,
                detail = "Pairing succeeded and the first inventory download completed."
            )
        } catch (e: Exception) {
            device_state_store.set_connection_error("Pairing succeeded, but the first inventory download failed.")
            PairingSetupResult(
                endpoint = payload.base_url,
                initial_sync_completed = false,
                detail = "Pairing succeeded, but the first inventory download failed. Open Sync to retry."
            )
        }
    }

    override suspend fun test_connection(base_url: String): PairingConnectionResult {
        val normalizedBaseUrl = base_url.trim()
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("Enter the Raspberry Pi address first.")
        }
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            throw IllegalArgumentException("Use a full address like http://192.168.1.20:8080.")
        }

        return try {
            val status = RemoteDataSourceFactory.create_api(normalizedBaseUrl).device_status()
            PairingConnectionResult(
                endpoint = normalizedBaseUrl,
                status = "Reachable",
                detail = if (status.local_ip.isBlank()) {
                    "Pi ${status.device_name} is ${status.connection_status}. Pair with QR to authenticate this phone."
                } else {
                    "Pi ${status.device_name} is ${status.connection_status} at ${status.local_ip}."
                }
            )
        } catch (e: Exception) {
            throw IllegalStateException(classifyConnectionError(e), e)
        }
    }

    override suspend fun save_endpoint(base_url: String) {
        val normalizedBaseUrl = base_url.trim()
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("Enter the Raspberry Pi address first.")
        }
        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            throw IllegalArgumentException("Use a full address like http://192.168.1.20:8080.")
        }
        device_state_store.set_base_url(normalizedBaseUrl)
    }

    override suspend fun unpair() {
        device_state_store.clear_pairing()
    }

    private fun classifyConnectionError(error: Exception): String {
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
}
