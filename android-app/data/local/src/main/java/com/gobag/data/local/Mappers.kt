package com.gobag.data.local

import com.gobag.core.model.BagProfile
import com.gobag.core.model.Conflict
import com.gobag.core.model.Item
import com.gobag.core.model.RecommendedItem
import com.google.gson.Gson

private val gson = Gson()

fun BagEntity.to_model(): BagProfile = BagProfile(
    bag_id = bag_id,
    name = name,
    size_liters = size_liters,
    template_id = template_id,
    updated_at = updated_at,
    updated_by = updated_by
)

fun BagProfile.to_entity(): BagEntity = BagEntity(
    bag_id = bag_id,
    name = name,
    size_liters = size_liters,
    template_id = template_id,
    updated_at = updated_at,
    updated_by = updated_by
)

fun ItemEntity.to_model(): Item = Item(
    id = id,
    bag_id = bag_id,
    name = name,
    category = category,
    quantity = quantity,
    unit = unit,
    packed_status = packed_status,
    notes = notes,
    expiry_date_ms = expiry_date_ms,
    deleted = deleted,
    updated_at = updated_at,
    updated_by = updated_by
)

fun Item.to_entity(): ItemEntity = ItemEntity(
    id = id,
    bag_id = bag_id,
    name = name,
    category = category,
    quantity = quantity,
    unit = unit,
    packed_status = packed_status,
    notes = notes,
    expiry_date_ms = expiry_date_ms,
    deleted = deleted,
    updated_at = updated_at,
    updated_by = updated_by
)

fun RecommendedItemEntity.to_model(): RecommendedItem = RecommendedItem(
    template_id = template_id,
    category = category,
    name = name,
    recommended_qty = recommended_qty,
    unit = unit,
    priority = priority,
    tips = tips
)

fun RecommendedItem.to_entity(): RecommendedItemEntity = RecommendedItemEntity(
    template_id = template_id,
    category = category,
    name = name,
    recommended_qty = recommended_qty,
    unit = unit,
    priority = priority,
    tips = tips
)

fun ConflictEntity.to_model(): Conflict = Conflict(
    item_id = item_id,
    server_version = gson.fromJson(server_json, Item::class.java),
    reason = reason
)

fun Conflict.to_entity(): ConflictEntity = ConflictEntity(
    item_id = item_id,
    server_json = gson.toJson(server_version),
    reason = reason
)
