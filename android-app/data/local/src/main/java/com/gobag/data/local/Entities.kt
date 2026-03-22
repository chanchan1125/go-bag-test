package com.gobag.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bags")
data class BagEntity(
    @PrimaryKey val bag_id: String,
    val name: String,
    val size_liters: Int,
    val template_id: String,
    val updated_at: Long,
    val updated_by: String
)

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,
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

@Entity(tableName = "recommended_templates", primaryKeys = ["template_id", "category", "name"])
data class RecommendedItemEntity(
    val template_id: String,
    val category: String,
    val name: String,
    val recommended_qty: Double,
    val unit: String,
    val priority: String,
    val tips: String
)

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val item_id: String,
    val server_json: String,
    val reason: String
)
