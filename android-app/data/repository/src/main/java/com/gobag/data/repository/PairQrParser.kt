package com.gobag.data.repository

import com.gobag.core.model.is_supported_bag_size_liters
import com.gobag.core.model.normalize_bag_size_liters
import com.gobag.core.model.normalize_bag_template_id
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class PairQrPayload(
    val base_url: String,
    val remote_base_url: String = "",
    val candidate_base_urls: List<String> = emptyList(),
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
            size_liters?.let(::is_supported_bag_size_liters) == true
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
            remote_base_url = payload.read_string("remote_base_url"),
            candidate_base_urls = payload.read_string_list("candidate_base_urls"),
            pair_code = pairCode,
            pi_device_id = payload.read_string("pi_device_id"),
            bag_id = payload.read_string("bag_id"),
            bag_name = payload.read_string("bag_name"),
            size_liters = payload.read_int("size_liters")
                ?.let(::normalize_bag_size_liters)
                ?.takeIf(::is_supported_bag_size_liters),
            template_id = normalize_bag_template_id(payload.read_string("template_id"))
        )
    }
}

private fun JsonObject.read_string(key: String): String {
    val value = get(key) ?: return ""
    if (!value.isJsonPrimitive) return ""
    return runCatching { value.asString.trim() }.getOrDefault("")
}

private fun JsonObject.read_string_list(key: String): List<String> {
    val value = get(key) ?: return emptyList()
    if (!value.isJsonArray) return emptyList()
    return value.asJsonArray
        .mapNotNull { element ->
            if (!element.isJsonPrimitive) return@mapNotNull null
            runCatching { element.asString.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        .distinct()
}

private fun JsonObject.read_int(key: String): Int? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asInt }.getOrNull()
}
