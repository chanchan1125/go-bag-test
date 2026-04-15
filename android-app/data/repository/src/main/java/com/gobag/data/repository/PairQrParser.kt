package com.gobag.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class PairQrPayload(
    val base_url: String,
    val pair_code: String,
    val pi_device_id: String = "",
    val bag_id: String = "",
    val bag_name: String = "",
    val size_liters: Int? = null,
    val template_id: String = ""
) {
    fun has_complete_bag_identity(): Boolean {
        return bag_id.isNotBlank() &&
            bag_name.isNotBlank() &&
            size_liters in setOf(25, 44, 66)
    }
}

object PairQrParser {
    fun parse(payload_json: String): PairQrPayload {
        val trimmed = payload_json.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("That QR code was empty.")
        }
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrElse {
            throw IllegalArgumentException("That QR code does not contain valid bag setup data.")
        }
        if (!root.isJsonObject) {
            throw IllegalArgumentException("That QR code does not contain valid bag setup data.")
        }

        val payload = root.asJsonObject
        val baseUrl = payload.read_string("base_url")
        val pairCode = payload.read_string("pair_code")
        if (baseUrl.isBlank() || pairCode.isBlank()) {
            throw IllegalArgumentException("That QR code is missing the bag location or code.")
        }

        return PairQrPayload(
            base_url = baseUrl,
            pair_code = pairCode,
            pi_device_id = payload.read_string("pi_device_id"),
            bag_id = payload.read_string("bag_id"),
            bag_name = payload.read_string("bag_name"),
            size_liters = payload.read_int("size_liters"),
            template_id = payload.read_string("template_id")
        )
    }
}

private fun JsonObject.read_string(key: String): String {
    val value = get(key) ?: return ""
    if (!value.isJsonPrimitive) return ""
    return runCatching { value.asString.trim() }.getOrDefault("")
}

private fun JsonObject.read_int(key: String): Int? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asInt }.getOrNull()
}
