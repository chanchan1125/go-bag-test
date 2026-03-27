package com.gobag.domain.repository

import com.gobag.core.model.SavedPiAddress

data class PairingConnectionResult(
    val endpoint: String,
    val status: String,
    val detail: String
)

data class PairingSetupResult(
    val endpoint: String,
    val initial_sync_completed: Boolean,
    val detail: String
)

interface PairingRepository {
    suspend fun pair_from_qr_payload(payload_json: String): PairingSetupResult
    suspend fun pair_with_code(base_url: String, pair_code: String): PairingSetupResult
    suspend fun test_connection(base_url: String): PairingConnectionResult
    suspend fun save_endpoint(base_url: String, address_id: String? = null): SavedPiAddress
    suspend fun delete_endpoint(address_id: String)
    suspend fun set_active_endpoint(address_id: String)
    suspend fun refresh_endpoint(address_id: String, base_url: String): PairingConnectionResult
    suspend fun unpair_bag(bag_id: String)
}
