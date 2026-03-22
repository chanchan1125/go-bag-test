package com.gobag.domain.repository

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
    suspend fun test_connection(base_url: String): PairingConnectionResult
    suspend fun save_endpoint(base_url: String)
    suspend fun unpair()
}
