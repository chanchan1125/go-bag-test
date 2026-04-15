package com.gobag.data.remote

import com.google.gson.annotations.SerializedName

data class BagDto(
    @SerializedName("bag_id") val bag_id: String,
    @SerializedName("name") val name: String,
    @SerializedName("size_liters") val size_liters: Int,
    @SerializedName("template_id") val template_id: String,
    @SerializedName("updated_at") val updated_at: Long,
    @SerializedName("updated_by") val updated_by: String
)

data class ItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("bag_id") val bag_id: String,
    @SerializedName("name") val name: String,
    @SerializedName("category") val category: String,
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("unit") val unit: String,
    @SerializedName("packed_status") val packed_status: Boolean,
    @SerializedName("notes") val notes: String,
    @SerializedName("expiry_date_ms") val expiry_date_ms: Long?,
    @SerializedName("deleted") val deleted: Boolean,
    @SerializedName("updated_at") val updated_at: Long,
    @SerializedName("updated_by") val updated_by: String
)

data class RecommendedItemDto(
    @SerializedName("template_id") val template_id: String,
    @SerializedName("category") val category: String,
    @SerializedName("name") val name: String,
    @SerializedName("recommended_qty") val recommended_qty: Double,
    @SerializedName("unit") val unit: String,
    @SerializedName("priority") val priority: String,
    @SerializedName("tips") val tips: String
)

data class PairRequestDto(
    @SerializedName("phone_device_id") val phone_device_id: String,
    @SerializedName("pair_code") val pair_code: String
)

data class PairResponseDto(
    @SerializedName("auth_token") val auth_token: String,
    @SerializedName("pi_device_id") val pi_device_id: String,
    @SerializedName("server_time_ms") val server_time_ms: Long
)

data class DeviceBagDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("bag_type") val bag_type: String,
    @SerializedName("updated_at") val updated_at: Long
)

data class HealthResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("camera_enabled") val camera_enabled: Boolean,
    @SerializedName("camera_cmd_available") val camera_cmd_available: Boolean
)

data class DeviceStatusDto(
    @SerializedName("id") val id: String,
    @SerializedName("device_name") val device_name: String,
    @SerializedName("last_sync_at") val last_sync_at: Long,
    @SerializedName("connection_status") val connection_status: String,
    @SerializedName("pending_changes_count") val pending_changes_count: Int,
    @SerializedName("local_ip") val local_ip: String,
    @SerializedName("local_base_url") val local_base_url: String = "",
    @SerializedName("remote_base_url") val remote_base_url: String = "",
    @SerializedName("updated_at") val updated_at: Long,
    @SerializedName("pi_device_id") val pi_device_id: String,
    @SerializedName("pair_code") val pair_code: String,
    @SerializedName("paired_devices") val paired_devices: Int,
    @SerializedName("database_path") val database_path: String
)

data class SyncStatusDto(
    @SerializedName("id") val id: String,
    @SerializedName("device_name") val device_name: String,
    @SerializedName("last_sync_at") val last_sync_at: Long,
    @SerializedName("connection_status") val connection_status: String,
    @SerializedName("pending_changes_count") val pending_changes_count: Int,
    @SerializedName("local_ip") val local_ip: String,
    @SerializedName("local_base_url") val local_base_url: String = "",
    @SerializedName("remote_base_url") val remote_base_url: String = "",
    @SerializedName("updated_at") val updated_at: Long
)

data class AlertDto(
    @SerializedName("bag_id") val bag_id: String,
    @SerializedName("bag_name") val bag_name: String?,
    @SerializedName("item_id") val item_id: String,
    @SerializedName("item_name") val item_name: String?,
    @SerializedName("type") val type: String,
    @SerializedName("days_left") val days_left: Int,
    @SerializedName("expiry_date_ms") val expiry_date_ms: Long?
)

data class SyncRequestDto(
    @SerializedName("phone_device_id") val phone_device_id: String,
    @SerializedName("last_sync_at") val last_sync_at: Long,
    @SerializedName("changed_bags") val changed_bags: List<BagDto>,
    @SerializedName("changed_items") val changed_items: List<ItemDto>
)

data class ConflictDto(
    @SerializedName("item_id") val item_id: String,
    @SerializedName("server_version") val server_version: ItemDto,
    @SerializedName("reason") val reason: String
)

data class AutoResolvedDto(
    @SerializedName("item_id") val item_id: String,
    @SerializedName("rule") val rule: String
)

data class SyncResponseDto(
    @SerializedName("server_time_ms") val server_time_ms: Long,
    @SerializedName("server_bag_changes") val server_bag_changes: List<BagDto>,
    @SerializedName("server_item_changes") val server_item_changes: List<ItemDto>,
    @SerializedName("conflicts") val conflicts: List<ConflictDto>,
    @SerializedName("auto_resolved") val auto_resolved: List<AutoResolvedDto>,
    @SerializedName("alerts") val alerts: List<AlertDto>
)

data class TemplatesResponseDto(
    @SerializedName("templates") val templates: List<RecommendedItemDto>
)
