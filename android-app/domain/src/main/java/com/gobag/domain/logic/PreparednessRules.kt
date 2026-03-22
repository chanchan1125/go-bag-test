package com.gobag.domain.logic

import com.gobag.core.model.AlertModel
import com.gobag.core.model.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val EXPIRING_SOON_DAYS = 7

data class ChecklistCategoryStatus(
    val name: String,
    val checked: Boolean
)

data class ChecklistSummary(
    val categories: List<ChecklistCategoryStatus>
) {
    val covered_count: Int = categories.count { it.checked }
    val total_count: Int = categories.size
    val missing_categories: List<String> = categories.filterNot { it.checked }.map { it.name }
}

data class ExpirationSummary(
    val expired_count: Int,
    val near_expiry_count: Int,
    val critical_expired_count: Int
)

data class ReadinessSummary(
    val device_status: String,
    val bag_readiness: String,
    val checklist: ChecklistSummary,
    val expiration: ExpirationSummary,
    val alerts: List<String>,
    val is_paired: Boolean
)

object PreparednessRules {
    val checklist_categories: List<String> = listOf(
        "Water & Food",
        "Medical & Health",
        "Light & Communication",
        "Tools & Protection",
        "Hygiene",
        "Other"
    )

    val unit_options: List<String> = listOf(
        "pcs", "pack", "box", "bottle", "can", "sachet", "pair", "roll",
        "set", "tablet", "capsule", "ml", "g", "kg", "L"
    )

    private fun utc_day_number(value_ms: Long): Long = Math.floorDiv(value_ms, DAY_MS)

    fun days_until_expiry(expiry_date_ms: Long, now_ms: Long = System.currentTimeMillis()): Int =
        (utc_day_number(expiry_date_ms) - utc_day_number(now_ms)).toInt()

    fun normalize_category(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when {
            value == "water & food" || value == "water_food" || value == "water-food" -> "Water & Food"
            value == "medical & health" || value == "medical_health" || value == "medical-health" -> "Medical & Health"
            value == "light & communication" || value == "light_communication" || value == "light-communication" -> "Light & Communication"
            value == "tools & protection" || value == "tools_protection" || value == "tools-protection" -> "Tools & Protection"
            value == "hygiene" -> "Hygiene"
            value == "other" -> "Other"
            "food" in value || "water" in value || "hydration" in value || "drink" in value -> "Water & Food"
            "med" in value || "health" in value || "first aid" in value || "first-aid" in value || "sanit" in value -> "Medical & Health"
            "light" in value || "radio" in value || "whistle" in value || "communication" in value -> "Light & Communication"
            "tool" in value || "protection" in value || "map" in value || "duct tape" in value || "blanket" in value || "poncho" in value -> "Tools & Protection"
            "hygiene" in value || "toilet" in value || "soap" in value -> "Hygiene"
            else -> "Other"
        }
    }

    fun build_checklist(items: List<Item>): ChecklistSummary {
        val present = items
            .filterNot { it.deleted }
            .map { normalize_category(it.category) }
            .toSet()
        return ChecklistSummary(
            categories = checklist_categories.map { category ->
                ChecklistCategoryStatus(name = category, checked = present.contains(category))
            }
        )
    }

    fun expiration_state(item: Item, now_ms: Long = System.currentTimeMillis()): String {
        val expiry = item.expiry_date_ms ?: return "NO_EXPIRATION"
        val days_left = days_until_expiry(expiry, now_ms)
        return when {
            days_left < 0 -> "EXPIRED"
            days_left <= EXPIRING_SOON_DAYS -> "NEAR_EXPIRY"
            else -> "OK"
        }
    }

    fun build_expiration_alerts(
        items: List<Item>,
        bag_id: String,
        bag_name: String,
        now_ms: Long = System.currentTimeMillis()
    ): List<AlertModel> = items
        .filterNot { it.deleted || it.expiry_date_ms == null }
        .mapNotNull { item ->
            val expiry = item.expiry_date_ms ?: return@mapNotNull null
            val days_left = days_until_expiry(expiry, now_ms)
            when (expiration_state(item, now_ms)) {
                "EXPIRED" -> AlertModel(
                    bag_id = bag_id,
                    bag_name = bag_name,
                    item_id = item.id,
                    item_name = item.name,
                    type = "expired",
                    days_left = days_left,
                    expiry_date_ms = expiry
                )
                "NEAR_EXPIRY" -> AlertModel(
                    bag_id = bag_id,
                    bag_name = bag_name,
                    item_id = item.id,
                    item_name = item.name,
                    type = "expiring_soon",
                    days_left = days_left,
                    expiry_date_ms = expiry
                )
                else -> null
            }
        }
        .sortedWith(compareBy<AlertModel> { if (it.type == "expired") 0 else 1 }.thenBy { it.expiry_date_ms ?: Long.MAX_VALUE })

    fun build_readiness_summary(items: List<Item>, is_paired: Boolean, now_ms: Long = System.currentTimeMillis()): ReadinessSummary {
        val active = items.filterNot { it.deleted }
        val checklist = build_checklist(active)
        val expired = active.count { expiration_state(it, now_ms) == "EXPIRED" }
        val near_expiry = active.count { expiration_state(it, now_ms) == "NEAR_EXPIRY" }
        val critical_expired = active.count {
            expiration_state(it, now_ms) == "EXPIRED" && normalize_category(it.category) in setOf("Water & Food", "Medical & Health")
        }
        val bag_readiness = when {
            !is_paired -> "Incomplete"
            critical_expired > 0 -> "Attention Needed"
            checklist.missing_categories.isEmpty() -> "Ready"
            else -> "Incomplete"
        }

        val alerts = buildList {
            if (checklist.missing_categories.isNotEmpty()) add("Missing categories: ${checklist.missing_categories.joinToString()}")
            if (expired > 0) add("Expired items: $expired")
            if (near_expiry > 0) add("Near-expiry items: $near_expiry")
        }

        return ReadinessSummary(
            device_status = if (is_paired) "Paired" else "Not Paired",
            bag_readiness = bag_readiness,
            checklist = checklist,
            expiration = ExpirationSummary(
                expired_count = expired,
                near_expiry_count = near_expiry,
                critical_expired_count = critical_expired
            ),
            alerts = alerts,
            is_paired = is_paired
        )
    }

    fun parse_yyyy_mm_dd_to_epoch_ms(value: String): Long? {
        val text = value.trim()
        if (text.isBlank()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = fmt.parse(text) ?: return null
        return parsed.time
    }

    fun format_epoch_ms_to_yyyy_mm_dd(value: Long?): String {
        if (value == null) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(value))
    }
}
