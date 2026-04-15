package com.gobag.data.repository

import com.gobag.core.model.BagProfile
import com.gobag.core.model.RecommendedItem
import com.gobag.core.model.SavedPiAddress
import com.gobag.data.local.RecommendedItemDao
import com.gobag.data.local.to_entity
import com.gobag.data.remote.PairRequestDto
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.PairingConnectionResult
import com.gobag.domain.repository.PairingRepository
import com.gobag.domain.repository.PairingSetupResult
import com.gobag.domain.repository.SyncRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

private data class PairingBagSeed(
    val bag_id: String,
    val bag_name: String,
    val size_liters: Int,
    val template_id: String,
    val updated_at: Long
)

class GoBagPairingRepository(
    private val device_state_store: DeviceStateStore,
    private val recommended_item_dao: RecommendedItemDao,
    private val item_repository: ItemRepository,
    private val sync_repository: SyncRepository,
    private val pi_connection_manager: PiConnectionManager
) : PairingRepository {
    override suspend fun pair_from_qr_payload(payload_json: String): PairingSetupResult {
        val payload = PairQrParser.parse(payload_json)
        return perform_pairing(
            base_url = payload.base_url,
            pair_code = payload.pair_code,
            qr_payload = payload
        )
    }

    override suspend fun pair_with_code(base_url: String, pair_code: String): PairingSetupResult {
        return perform_pairing(
            base_url = base_url,
            pair_code = pair_code
        )
    }

    override suspend fun test_connection(base_url: String): PairingConnectionResult {
        return pi_connection_manager.test_endpoint(base_url)
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
        return pi_connection_manager.test_endpoint(
            base_url = base_url,
            address_id = address_id,
            adopt_on_success = true
        )
    }

    override suspend fun unpair_bag(bag_id: String) {
        device_state_store.clear_pairing_for_bag(bag_id)
    }

    private suspend fun perform_pairing(
        base_url: String,
        pair_code: String,
        qr_payload: PairQrPayload? = null
    ): PairingSetupResult {
        device_state_store.initialize_phone_device_id_if_missing()
        val normalizedBaseUrl = normalize_base_url(base_url)
        val normalizedPairCode = normalize_pair_code(pair_code)
        pi_connection_manager.test_endpoint(normalizedBaseUrl, adopt_on_success = false)

        val state = device_state_store.state.first()
        val api = RemoteDataSourceFactory.create_api(normalizedBaseUrl)
        val bagSeed = fetch_pairing_bag_seed(api, qr_payload)
        val phone_time_ms = System.currentTimeMillis()
        val pair = try {
            api.pair(PairRequestDto(phone_device_id = state.phone_device_id, pair_code = normalizedPairCode))
        } catch (e: Exception) {
            throw IllegalStateException(classify_pairing_error(e), e)
        }

        val resolvedPiDeviceId = pair.pi_device_id
            .ifBlank { qr_payload?.pi_device_id.orEmpty() }
            .ifBlank { state.phone_device_id }
        val bagProfile = BagProfile(
            bag_id = bagSeed.bag_id,
            name = bagSeed.bag_name,
            size_liters = bagSeed.size_liters,
            template_id = bagSeed.template_id.ifBlank { template_id_for_size(bagSeed.size_liters) },
            updated_at = if (bagSeed.updated_at > 0L) bagSeed.updated_at else phone_time_ms,
            updated_by = resolvedPiDeviceId
        )
        val alreadyPaired = state.paired_bags.any { it.bag_id == bagProfile.bag_id }

        item_repository.upsert_bag(bagProfile)

        val savedAddress = device_state_store.upsert_saved_address(normalizedBaseUrl, make_active = true)
        device_state_store.upsert_paired_bag_connection(
            bag_id = bagProfile.bag_id,
            auth_token = pair.auth_token,
            base_url = normalizedBaseUrl,
            pi_device_id = pair.pi_device_id,
            time_offset_ms = pair.server_time_ms - phone_time_ms
        )
        device_state_store.set_selected_bag_id(bagProfile.bag_id)

        val warnings = mutableListOf<String>()
        runCatching {
            val deviceStatus = api.device_status()
            pi_connection_manager.adopt_successful_pairing_endpoint(
                base_url = normalizedBaseUrl,
                bag_id = bagProfile.bag_id,
                pi_device_id = pair.pi_device_id,
                device_status = deviceStatus
            )
        }.onFailure {
            warnings += "Raspberry Pi status refresh failed."
            device_state_store.update_saved_address_status(
                address_id = savedAddress.id,
                status = "Paired",
                detail = "Pair code accepted. Refresh the Raspberry Pi status or open Sync to finish setup.",
                make_active = true
            )
        }

        runCatching {
            val templates = api.templates().templates.map { template ->
                RecommendedItem(
                    template_id = template.template_id,
                    category = template.category,
                    name = template.name,
                    recommended_qty = template.recommended_qty,
                    unit = template.unit,
                    priority = template.priority,
                    tips = template.tips
                ).to_entity()
            }
            if (templates.isNotEmpty()) recommended_item_dao.upsert_all(templates)
        }.onFailure {
            warnings += "Template refresh failed."
        }

        val initialSyncCompleted = runCatching {
            sync_repository.run_sync_now()
        }.isSuccess
        if (!initialSyncCompleted) {
            device_state_store.set_connection_error(
                message = "Bag pairing succeeded, but the first inventory sync failed.",
                bag_id = bagProfile.bag_id
            )
        }

        return PairingSetupResult(
            endpoint = normalizedBaseUrl,
            initial_sync_completed = initialSyncCompleted,
            detail = build_pairing_detail(
                bag_name = bagProfile.name,
                already_paired = alreadyPaired,
                initial_sync_completed = initialSyncCompleted,
                warnings = warnings
            )
        )
    }

    private suspend fun fetch_pairing_bag_seed(
        api: com.gobag.data.remote.GoBagApi,
        qr_payload: PairQrPayload?
    ): PairingBagSeed {
        val remoteBag = runCatching { api.device_bag() }.getOrNull()
        if (remoteBag != null) {
            val sizeLiters = size_liters_for_bag_type(remoteBag.bag_type)
                ?: qr_payload?.size_liters
                ?: 44
            return PairingBagSeed(
                bag_id = remoteBag.id.trim(),
                bag_name = remoteBag.name.trim().ifBlank { qr_payload?.bag_name?.trim().orEmpty().ifBlank { "GO BAG" } },
                size_liters = sizeLiters,
                template_id = qr_payload?.template_id?.takeIf { it.isNotBlank() } ?: template_id_for_size(sizeLiters),
                updated_at = remoteBag.updated_at
            )
        }
        if (qr_payload?.has_complete_bag_identity() == true) {
            return PairingBagSeed(
                bag_id = qr_payload.bag_id.trim(),
                bag_name = qr_payload.bag_name.trim(),
                size_liters = qr_payload.size_liters ?: 44,
                template_id = qr_payload.template_id.ifBlank { template_id_for_size(qr_payload.size_liters ?: 44) },
                updated_at = 0L
            )
        }
        throw IllegalStateException("The Raspberry Pi did not provide bag details for this Pair Code. Refresh the Pi dashboard and try again.")
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

    private fun normalize_pair_code(pair_code: String): String {
        val normalized = pair_code.trim().filter { !it.isWhitespace() }
        if (!normalized.matches(Regex("\\d{6}"))) {
            throw IllegalArgumentException("Enter the 6-digit Pair Code shown on the Raspberry Pi.")
        }
        return normalized
    }

    private fun classify_pairing_error(error: Exception): String {
        if (error is IllegalArgumentException) return error.message.orEmpty()
        if (error is HttpException) {
            val detail = parse_fastapi_detail(error.response()?.errorBody()?.string().orEmpty())
            return when {
                error.code() == 400 && detail.contains("invalid or expired", ignoreCase = true) ->
                    "Pair Code invalid or expired. Generate a new code on the Raspberry Pi and try again."
                error.code() == 400 && detail.isNotBlank() -> detail
                error.code() == 404 -> "The Raspberry Pi pairing route was not found at this address."
                detail.isNotBlank() -> "Pairing failed: $detail"
                else -> "Pairing failed with HTTP ${error.code()}."
            }
        }
        return classify_connection_error(error)
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

    private fun build_pairing_detail(
        bag_name: String,
        already_paired: Boolean,
        initial_sync_completed: Boolean,
        warnings: List<String>
    ): String {
        val base = when {
            already_paired && initial_sync_completed ->
                "$bag_name was already paired. Connection details were refreshed and inventory re-synced."
            initial_sync_completed ->
                "$bag_name paired successfully and its inventory downloaded."
            already_paired ->
                "$bag_name was refreshed, but the first inventory sync failed. Open Sync to retry."
            else ->
                "$bag_name paired successfully, but the first inventory sync failed. Open Sync to retry."
        }
        return if (warnings.isEmpty()) {
            base
        } else {
            "$base ${warnings.joinToString(" ")}"
        }
    }

    private fun size_liters_for_bag_type(bag_type: String): Int? = when (bag_type.trim().lowercase()) {
        "25l" -> 25
        "44l" -> 44
        "66l" -> 66
        else -> null
    }

    private fun template_id_for_size(liters: Int): String = when (liters) {
        25 -> "template_25l"
        44 -> "template_44l"
        66 -> "template_66l"
        else -> "template_44l"
    }
}
