package com.gobag.data.remote

import com.gobag.core.model.AlertModel
import com.gobag.core.model.AutoResolved
import com.gobag.core.model.BagProfile
import com.gobag.core.model.Conflict
import com.gobag.core.model.Item
import com.gobag.core.model.RecommendedItem
import com.gobag.core.model.SensorSectionSnapshot
import com.gobag.core.model.SensorSnapshot

fun BagProfile.to_dto(): BagDto = BagDto(
    bag_id = bag_id,
    name = name,
    size_liters = size_liters,
    template_id = template_id,
    updated_at = updated_at,
    updated_by = updated_by
)

fun BagDto.to_model(): BagProfile = BagProfile(
    bag_id = bag_id,
    name = name,
    size_liters = size_liters,
    template_id = template_id,
    updated_at = updated_at,
    updated_by = updated_by
)

fun Item.to_dto(): ItemDto = ItemDto(
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

fun ItemDto.to_model(): Item = Item(
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

fun RecommendedItemDto.to_model(): RecommendedItem = RecommendedItem(
    template_id = template_id,
    category = category,
    name = name,
    recommended_qty = recommended_qty,
    unit = unit,
    priority = priority,
    tips = tips
)

fun ConflictDto.to_model(): Conflict = Conflict(
    item_id = item_id,
    server_version = server_version.to_model(),
    reason = reason
)

fun AutoResolvedDto.to_model(): AutoResolved = AutoResolved(
    item_id = item_id,
    rule = rule
)

fun AlertDto.to_model(): AlertModel = AlertModel(
    bag_id = bag_id,
    bag_name = bag_name ?: bag_id,
    item_id = item_id,
    item_name = item_name ?: item_id,
    type = type,
    days_left = days_left,
    expiry_date_ms = expiry_date_ms
)

fun SensorSectionSnapshotDto.to_model(): SensorSectionSnapshot = SensorSectionSnapshot(
    index = index,
    name = name,
    current_weight_kg = current_weight_kg,
    full_weight_kg = full_weight_kg,
    min_detection_weight_kg = min_detection_weight_kg,
    detected = detected,
    fill_ratio = fill_ratio,
    contribution_percent = contribution_percent
)

fun SensorSnapshotDto.to_model(): SensorSnapshot = SensorSnapshot(
    source = source,
    connection_state = connection_state,
    available = available,
    connected = connected,
    message = message,
    serial_port = serial_port,
    last_read_at = last_read_at,
    last_live_at = last_live_at,
    updated_at = updated_at,
    total_percentage = total_percentage,
    total_percentage_exact = total_percentage_exact,
    raw_payload = raw_payload,
    last_error = last_error,
    sections = sections.map { it.to_model() }
)
