package com.gobag.data.repository

import com.google.gson.Gson


data class PairQrPayload(
    val base_url: String,
    val pair_code: String,
    val pi_device_id: String
)

object PairQrParser {
    private val gson = Gson()

    fun parse(payload_json: String): PairQrPayload {
        val payload = gson.fromJson(payload_json, PairQrPayload::class.java)
            ?: throw IllegalArgumentException("QR payload is empty.")
        if (payload.base_url.isBlank() || payload.pair_code.isBlank()) {
            throw IllegalArgumentException("QR payload is missing the Raspberry Pi address or pair code.")
        }
        return payload
    }
}
