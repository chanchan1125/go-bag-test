package com.gobag.data.repository

import com.gobag.core.model.BagProfile
import com.gobag.core.model.RecommendedItem
import com.gobag.core.model.SavedPiAddress
import com.gobag.core.model.is_supported_bag_size_liters
import com.gobag.core.model.normalize_bag_size_liters
import com.gobag.core.model.normalize_bag_template_id
import com.gobag.core.model.template_id_for_bag_size_liters
import com.gobag.data.local.RecommendedItemDao
import com.gobag.data.local.to_entity
import com.gobag.data.remote.PairRequestDto
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.data.remote.UnpairRequestDto
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.PairingConnectionResult
import com.gobag.domain.repository.PairingRepository
import com.gobag.domain.repository.PairingSetupResult
import com.gobag.domain.repository.SyncRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.net.URI

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
    override suspend fun pair_from_qr_payload(payload_json: String, preferred_base_url: String): PairingSetupResult {
        val payload = PairQrParser.parse(payload_json)
        val normalizedQrBaseUrl = runCatching { normalize_base_url(payload.base_url) }.getOrNull()
        val candidateEndpoints = build_qr_pairing_candidates(
            qr_base_url = payload.base_url,
            qr_remote_base_url = payload.remote_base_url,
            qr_candidate_base_urls = payload.candidate_base_urls,
            preferred_base_url = preferred_base_url
        ).ifEmpty { listOf(normalize_base_url(payload.base_url)) }
        var lastFailure: Exception? = null

        for (candidate in candidateEndpoints) {
            try {
                val result = perform_pairing(
                    base_url = candidate,
                    pair_code = payload.pair_code,
                    qr_payload = payload
                )
                return if (normalizedQrBaseUrl != null && candidate.equals(normalizedQrBaseUrl, ignoreCase = true)) {
                    result
                } else {
                    result.copy(
                        detail = "${result.detail} We used another bag location because the first QR location was not reachable."
                    )
                }
            } catch (e: Exception) {
                if (!is_different_bag_error(e) || lastFailure == null) {
                    lastFailure = e
                }
            }
        }

        throw lastFailure ?: IllegalStateException("We could not finish setup.")
    }

    override suspend fun pair_with_code(base_url: String, pair_code: String): PairingSetupResult {
        return perform_pairing(
            base_url = base_url,
            pair_code = pair_code
        )
    }

    override suspend fun test_connection(base_url: String, allow_different_bag: Boolean): PairingConnectionResult {
        return if (allow_different_bag) {
            probe_pairing_endpoint(base_url)
        } else {
            pi_connection_manager.test_endpoint(base_url)
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
        return pi_connection_manager.test_endpoint(
            base_url = base_url,
            address_id = address_id,
            adopt_on_success = true
        )
    }

    override suspend fun unpair_bag(bag_id: String) {
        val state = device_state_store.state.first()
        val bagConnection = state.paired_bags.firstOrNull { it.bag_id == bag_id }
        if (bagConnection != null && bagConnection.base_url.isNotBlank() && bagConnection.auth_token.isNotBlank()) {
            runCatching {
                RemoteDataSourceFactory
                    .create_api(bagConnection.base_url, bagConnection.auth_token)
                    .unpair(UnpairRequestDto(phone_device_id = state.phone_device_id))
            }
        }
        device_state_store.clear_pairing_for_bag(bag_id)
    }

    private suspend fun perform_pairing(
        base_url: String,
        pair_code: String,
        qr_payload: PairQrPayload? = null
    ): PairingSetupResult {
        device_state_store.initialize_phone_device_id_if_missing()
        val normalizedBaseUrl = normalize_base_url(base_url)
        val normalizedRemoteBaseUrl = normalize_optional_base_url(qr_payload?.remote_base_url)
            .ifBlank { if (is_remote_candidate(normalizedBaseUrl)) normalizedBaseUrl else "" }
        val normalizedLocalBaseUrl = if (is_remote_candidate(normalizedBaseUrl)) {
            normalize_optional_base_url(qr_payload?.base_url)
                .takeIf { it.isNotBlank() && !is_remote_candidate(it) }
                .orEmpty()
        } else {
            normalizedBaseUrl
        }
        val connectionMode = if (normalizedRemoteBaseUrl.isNotBlank() &&
            normalizedBaseUrl.equals(normalizedRemoteBaseUrl, ignoreCase = true)
        ) {
            CONNECTION_MODE_REMOTE
        } else {
            CONNECTION_MODE_LOCAL
        }
        val normalizedPairCode = normalize_pair_code(pair_code)
        probe_pairing_endpoint(
            base_url = normalizedBaseUrl,
            expected_pi_device_id = qr_payload?.pi_device_id.orEmpty()
        )

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
            size_liters = normalize_bag_size_liters(bagSeed.size_liters),
            template_id = normalize_bag_template_id(bagSeed.template_id).ifBlank {
                template_id_for_bag_size_liters(bagSeed.size_liters)
            },
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
            time_offset_ms = pair.server_time_ms - phone_time_ms,
            local_base_url = normalizedLocalBaseUrl.ifBlank { null },
            remote_base_url = normalizedRemoteBaseUrl.ifBlank { null },
            last_connection_mode = connectionMode
        )
        device_state_store.set_selected_bag_id(bagProfile.bag_id)

        val warnings = mutableListOf<String>()
        runCatching {
            val deviceStatus = api.device_status()
            pi_connection_manager.adopt_successful_pairing_endpoint(
                base_url = normalizedBaseUrl,
                bag_id = bagProfile.bag_id,
                pi_device_id = pair.pi_device_id,
                device_status = deviceStatus,
                hinted_remote_base_url = normalizedRemoteBaseUrl.ifBlank { null }
            )
        }.onFailure {
            warnings += "Bag status refresh failed."
            device_state_store.update_saved_address_status(
                address_id = savedAddress.id,
                status = "Ready",
                detail = "Your bag is connected, but the first update still needs to finish.",
                make_active = true
            )
        }

        runCatching {
            val templates = api.templates().templates.map { template ->
                RecommendedItem(
                    template_id = normalize_bag_template_id(template.template_id),
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
                message = "Your bag is connected, but the first update did not finish.",
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
        val qrSizeLiters = qr_payload?.size_liters
            ?.let(::normalize_bag_size_liters)
            ?.takeIf(::is_supported_bag_size_liters)
        val qrTemplateId = normalize_bag_template_id(qr_payload?.template_id.orEmpty())
        if (remoteBag != null) {
            val sizeLiters = size_liters_for_bag_type(remoteBag.bag_type)
                ?: qrSizeLiters
                ?: 46
            return PairingBagSeed(
                bag_id = remoteBag.id.trim(),
                bag_name = remoteBag.name.trim().ifBlank { qr_payload?.bag_name?.trim().orEmpty().ifBlank { "GO BAG" } },
                size_liters = sizeLiters,
                template_id = qrTemplateId.ifBlank { template_id_for_bag_size_liters(sizeLiters) },
                updated_at = remoteBag.updated_at
            )
        }
        if (qr_payload?.has_complete_bag_identity() == true) {
            return PairingBagSeed(
                bag_id = qr_payload.bag_id.trim(),
                bag_name = qr_payload.bag_name.trim(),
                size_liters = qrSizeLiters ?: 46,
                template_id = qrTemplateId.ifBlank {
                    template_id_for_bag_size_liters(qrSizeLiters ?: 46)
                },
                updated_at = 0L
            )
        }
            throw IllegalStateException("The bag did not send its details. Refresh the bag screen and try again.")
    }

    private suspend fun build_qr_pairing_candidates(
        qr_base_url: String,
        qr_remote_base_url: String,
        qr_candidate_base_urls: List<String>,
        preferred_base_url: String
    ): List<String> {
        val state = device_state_store.state.first()
        val localCandidates = linkedSetOf<String>()
        val remoteCandidates = linkedSetOf<String>()

        fun add_candidate(bucket: MutableSet<String>, raw: String?) {
            val normalized = raw
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { normalize_base_url(it) }.getOrNull() }
                .orEmpty()
            if (normalized.isNotBlank()) {
                bucket += normalized
            }
        }

        fun add_candidate_auto(raw: String?) {
            val normalized = raw
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { normalize_base_url(it) }.getOrNull() }
                .orEmpty()
            if (normalized.isBlank()) return
            if (is_remote_candidate(normalized)) {
                remoteCandidates += normalized
            } else {
                localCandidates += normalized
            }
        }

        add_candidate(localCandidates, preferred_base_url)
        qr_candidate_base_urls.forEach(::add_candidate_auto)
        add_candidate(localCandidates, qr_base_url)
        add_candidate(localCandidates, state.local_base_url)
        add_candidate(localCandidates, state.base_url.takeIf { !is_remote_candidate(it) })
        state.saved_addresses.filter { it.is_active && !is_remote_candidate(it.base_url) }
            .forEach { add_candidate(localCandidates, it.base_url) }
        state.saved_addresses.filter { !is_remote_candidate(it.base_url) }
            .forEach { add_candidate(localCandidates, it.base_url) }

        add_candidate(remoteCandidates, qr_remote_base_url)
        add_candidate(remoteCandidates, state.remote_base_url)
        add_candidate(remoteCandidates, state.base_url.takeIf { is_remote_candidate(it) })
        state.saved_addresses.filter { is_remote_candidate(it.base_url) }
            .forEach { add_candidate(remoteCandidates, it.base_url) }

        return (localCandidates + remoteCandidates).toList()
    }

    private suspend fun probe_pairing_endpoint(
        base_url: String,
        expected_pi_device_id: String = ""
    ): PairingConnectionResult {
        return pi_connection_manager.test_endpoint(
            base_url = base_url,
            adopt_on_success = false,
            update_global_failure = false,
            require_current_pi_match = false,
            expected_pi_device_id = expected_pi_device_id,
            pairing_probe = true
        )
    }

    private fun normalize_optional_base_url(base_url: String?): String {
        return base_url
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { normalize_base_url(it) }.getOrNull() }
            .orEmpty()
    }

    private fun is_remote_candidate(base_url: String?): Boolean {
        val normalizedBaseUrl = normalize_optional_base_url(base_url)
        if (normalizedBaseUrl.isBlank()) return false
        val uri = runCatching { URI(normalizedBaseUrl) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().trim().trim('[', ']').lowercase()
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        if (host.endsWith(".ts.net")) return true
        if (host.contains(':')) {
            return host.startsWith("fc") || host.startsWith("fd")
        }
        val octets = host.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        return parts[0] == 100 && parts[1] in 64..127
    }

    private fun normalize_pair_code(pair_code: String): String {
        val normalized = pair_code.trim().filter { !it.isWhitespace() }
        if (!normalized.matches(Regex("\\d{6}"))) {
            throw IllegalArgumentException("Enter the 6-digit code shown on the bag.")
        }
        return normalized
    }

    private fun classify_pairing_error(error: Exception): String {
        if (error is IllegalArgumentException) return error.message.orEmpty()
        if (error is HttpException) {
            val detail = parse_fastapi_detail(error.response()?.errorBody()?.string().orEmpty())
            return when {
                error.code() == 400 && detail.contains("invalid or expired", ignoreCase = true) ->
                    "That code is no longer valid. Get a new code from the bag and try again."
                error.code() == 400 && detail.isNotBlank() -> detail
                error.code() == 404 -> "That bag location is not working for setup."
                detail.isNotBlank() -> "We could not finish setup. $detail"
                else -> "We could not finish setup right now."
            }
        }
        return classify_connection_error(error)
    }

    private fun is_different_bag_error(error: Exception): Boolean {
        return error.message.orEmpty().contains("different GO BAG", ignoreCase = true)
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
                "$bag_name is ready on this phone."
            initial_sync_completed ->
                "$bag_name is connected and ready."
            already_paired ->
                "$bag_name is connected, but the first update did not finish. Open Update Bag to try again."
            else ->
                "$bag_name is connected, but the first update did not finish. Open Update Bag to try again."
        }
        return if (warnings.isEmpty()) {
            base
        } else {
            "$base Some setup steps still need attention."
        }
    }

    private fun size_liters_for_bag_type(bag_type: String): Int? = when (bag_type.trim().lowercase()) {
        "25l", "44l", "46l" -> 46
        "66l" -> 66
        else -> null
    }
}
