package com.gobag.data.repository

import com.google.gson.Gson


data class PairQrPayload(
    val base_url: String,
    val pair_code: String,
    val pi_device_id: String,
    val bag_id: String,
    val bag_name: String,
    val size_liters: Int,
    val template_id: String
)

object PairQrParser {
    private val gson = Gson()

    fun parse(payload_json: String): PairQrPayload {
        val payload = gson.fromJson(payload_json, PairQrPayload::class.java)
            ?: throw IllegalArgumentException("QR payload is empty.")
        if (payload.base_url.isBlank() || payload.pair_code.isBlank()) {
            throw IllegalArgumentException("QR payload is missing the Raspberry Pi address or pair code.")
        }
        if (payload.bag_id.isBlank() || payload.bag_name.isBlank()) {
            throw IllegalArgumentException("This QR code does not include a bag identity. Generate a bag QR from the Raspberry Pi app.")
        }
        if (payload.size_liters !in setOf(25, 44, 66)) {
            throw IllegalArgumentException("This QR code uses an unsupported bag size. Supported sizes are 25L, 44L, and 66L.")
        }
        return payload
    }
}
