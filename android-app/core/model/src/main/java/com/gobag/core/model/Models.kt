package com.gobag.core.model

data class BagProfile(
    val bag_id: String,
    val name: String,
    val size_liters: Int,
    val template_id: String,
    val updated_at: Long,
    val updated_by: String
)

data class Item(
    val id: String,
    val bag_id: String,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
    val packed_status: Boolean,
    val notes: String,
    val expiry_date_ms: Long?,
    val deleted: Boolean,
    val updated_at: Long,
    val updated_by: String
)

data class RecommendedItem(
    val template_id: String,
    val category: String,
    val name: String,
    val recommended_qty: Double,
    val unit: String,
    val priority: String,
    val tips: String
)

data class AlertModel(
    val bag_id: String,
    val bag_name: String,
    val item_id: String,
    val item_name: String,
    val type: String,
    val days_left: Int,
    val expiry_date_ms: Long?
)

data class Conflict(
    val item_id: String,
    val server_version: Item,
    val reason: String
)

data class AutoResolved(
    val item_id: String,
    val rule: String
)

data class SensorSectionSnapshot(
    val index: Int,
    val name: String,
    val current_weight_kg: Double? = null,
    val full_weight_kg: Double,
    val min_detection_weight_kg: Double,
    val detected: Boolean = false,
    val fill_ratio: Double? = null,
    val contribution_percent: Double? = null
)

data class SensorSnapshot(
    val source: String = "esp32_usb_serial",
    val connection_state: String = "disconnected",
    val available: Boolean = false,
    val connected: Boolean = false,
    val message: String = "",
    val serial_port: String = "",
    val last_read_at: Long = 0L,
    val last_live_at: Long = 0L,
    val updated_at: Long = 0L,
    val total_percentage: Int? = null,
    val total_percentage_exact: Double? = null,
    val raw_payload: String = "",
    val last_error: String = "",
    val sections: List<SensorSectionSnapshot> = emptyList()
)

data class PairedBagConnection(
    val bag_id: String,
    val base_url: String,
    val auth_token: String,
    val pi_device_id: String,
    val last_sync_at: Long,
    val time_offset_ms: Long,
    val connection_status: String,
    val pending_changes_count: Int,
    val local_ip: String,
    val last_connection_error: String,
    val paired_at: Long,
    val local_base_url: String? = null,
    val remote_base_url: String? = null,
    val last_connection_mode: String? = null,
    val sensor_snapshot: SensorSnapshot? = null
)

data class SavedPiAddress(
    val id: String,
    val base_url: String,
    val last_status: String,
    val last_detail: String,
    val last_checked_at: Long,
    val is_active: Boolean
)

data class DeviceState(
    val phone_device_id: String,
    val auth_token: String,
    val base_url: String,
    val pi_device_id: String,
    val local_base_url: String,
    val remote_base_url: String,
    val last_sync_at: Long,
    val time_offset_ms: Long,
    val auto_sync_enabled: Boolean,
    val selected_bag_id: String,
    val has_unresolved_conflicts: Boolean,
    val connection_status: String,
    val sync_status: String,
    val pending_changes_count: Int,
    val local_ip: String,
    val last_connection_mode: String,
    val last_connection_error: String,
    val last_sync_error: String,
    val last_connection_check_at: Long,
    val last_connected_at: Long,
    val sensor_snapshot: SensorSnapshot?,
    val paired_bags: List<PairedBagConnection>,
    val saved_addresses: List<SavedPiAddress>,
    val active_address_id: String
)
