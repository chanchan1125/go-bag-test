package com.gobag.data.repository

import android.content.Context
import com.gobag.core.common.nowMs
import com.gobag.core.model.AlertModel
import com.gobag.core.model.AutoResolved
import com.gobag.core.model.BagProfile
import com.gobag.core.model.Conflict
import com.gobag.core.model.DeviceState
import com.gobag.core.model.Item
import com.gobag.data.local.ConflictDao
import com.gobag.data.local.to_entity
import com.gobag.data.local.to_model
import com.gobag.data.remote.RemoteDataSourceFactory
import com.gobag.data.remote.SyncRequestDto
import com.gobag.data.remote.to_dto
import com.gobag.data.remote.to_model
import com.gobag.domain.logic.PiConnectionStatus
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import com.gobag.domain.repository.SyncRunResult
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

class GoBagSyncRepository(
    private val context: Context,
    private val item_repository: ItemRepository,
    private val conflict_dao: ConflictDao,
    private val device_state_store: DeviceStateStore,
    private val pi_connection_manager: PiConnectionManager
) : SyncRepository {
    override fun observe_device_state(): Flow<DeviceState> = device_state_store.state

    override fun observe_conflicts(): Flow<List<Conflict>> =
        conflict_dao.observe_conflicts().map { rows -> rows.map { it.to_model() } }

    override suspend fun set_auto_sync(enabled: Boolean) {
        val has_conflicts = observe_conflicts().first().isNotEmpty()
        if (enabled && has_conflicts) {
            device_state_store.set_auto_sync(false)
        } else {
            device_state_store.set_auto_sync(enabled)
        }
    }

    override suspend fun set_selected_bag_id(bag_id: String) {
        device_state_store.set_selected_bag_id(bag_id)
    }

    override suspend fun refresh_remote_status(): String? {
        return pi_connection_manager.refresh_current_connection()
    }

    override suspend fun resolve_conflict_keep_phone(item: Item) {
        val local_item = item_repository.get_item(item.id)
        if (local_item != null) {
            item_repository.upsert_item(local_item.copy(updated_at = nowMs()))
        }
        conflict_dao.clear_item(item.id)
        val has_conflicts = observe_conflicts().first().isNotEmpty()
        device_state_store.set_has_unresolved_conflicts(has_conflicts)
    }

    override suspend fun resolve_conflict_keep_pi(item: Item) {
        item_repository.upsert_item(item)
        conflict_dao.clear_item(item.id)
        val has_conflicts = observe_conflicts().first().isNotEmpty()
        device_state_store.set_has_unresolved_conflicts(has_conflicts)
    }

    override suspend fun resolve_conflict_keep_deleted(item: Item) {
        item_repository.soft_delete_item(item.copy(updated_at = nowMs()))
        conflict_dao.clear_item(item.id)
        val has_conflicts = observe_conflicts().first().isNotEmpty()
        device_state_store.set_has_unresolved_conflicts(has_conflicts)
    }

    override suspend fun resolve_conflict_restore(item: Item) {
        item_repository.upsert_item(item.copy(deleted = false, updated_at = nowMs()))
        conflict_dao.clear_item(item.id)
        val has_conflicts = observe_conflicts().first().isNotEmpty()
        device_state_store.set_has_unresolved_conflicts(has_conflicts)
    }

    override suspend fun run_sync_now(): SyncRunResult {
        device_state_store.initialize_phone_device_id_if_missing()
        device_state_store.set_sync_status(PiConnectionStatus.SYNC_STATUS_SYNCING)
        val state = device_state_store.state.first()
        if (state.selected_bag_id.isBlank()) {
            device_state_store.set_connection_error(
                message = "Choose a bag on this phone before updating.",
                status = if (state.paired_bags.isNotEmpty()) {
                    PiConnectionStatus.STATUS_PI_PAIRED
                } else {
                    PiConnectionStatus.STATUS_NO_PI_PAIRED
                }
            )
            device_state_store.set_sync_status(
                status = PiConnectionStatus.SYNC_STATUS_FAILED,
                error = "Choose a bag on this phone before updating."
            )
            return SyncRunResult(
                server_time_ms = state.last_sync_at,
                conflicts = emptyList(),
                auto_resolved = emptyList(),
                alerts = emptyList(),
                skipped_reason = "Update skipped because no bag is selected."
            )
        }
        if (state.auth_token.isBlank() || state.base_url.isBlank()) {
            device_state_store.set_connection_error(
                message = "This phone still needs to connect to that bag.",
                bag_id = state.selected_bag_id,
                status = PiConnectionStatus.STATUS_PI_PAIRED
            )
            device_state_store.set_sync_status(
                status = PiConnectionStatus.SYNC_STATUS_FAILED,
                error = "This phone still needs to connect to that bag."
            )
            return SyncRunResult(
                server_time_ms = state.last_sync_at,
                conflicts = emptyList(),
                auto_resolved = emptyList(),
                alerts = emptyList(),
                skipped_reason = "Update skipped because this phone is not connected to that bag yet."
            )
        }

        val selectedBagId = state.selected_bag_id
        val pairedBagIds = state.paired_bags.map { it.bag_id }.toSet()
        if (selectedBagId !in pairedBagIds) {
            device_state_store.set_connection_error(
                message = "The selected bag is not available on this phone.",
                bag_id = selectedBagId,
                status = if (pairedBagIds.isNotEmpty()) {
                    PiConnectionStatus.STATUS_PI_PAIRED
                } else {
                    PiConnectionStatus.STATUS_NO_PI_PAIRED
                }
            )
            device_state_store.set_sync_status(
                status = PiConnectionStatus.SYNC_STATUS_FAILED,
                error = "The selected bag is not available on this phone."
            )
            return SyncRunResult(
                server_time_ms = state.last_sync_at,
                conflicts = emptyList(),
                auto_resolved = emptyList(),
                alerts = emptyList(),
                skipped_reason = "Update skipped because the selected bag is not ready on this phone."
            )
        }

        val changed_items = item_repository.get_items_changed_since(state.last_sync_at).filter { it.bag_id == selectedBagId }
        val changed_bags = item_repository.get_bags_changed_since(state.last_sync_at).filter { it.bag_id == selectedBagId }
        try {
            validate_sync_payload(selectedBagId, changed_bags, changed_items)
        } catch (e: IllegalStateException) {
            device_state_store.set_connection_error(
                message = e.message ?: "We could not prepare the bag update.",
                bag_id = selectedBagId,
                status = PiConnectionStatus.STATUS_PI_PAIRED
            )
            device_state_store.set_sync_status(
                status = PiConnectionStatus.SYNC_STATUS_FAILED,
                error = e.message ?: "We could not prepare the bag update."
            )
            throw e
        }

        val api = RemoteDataSourceFactory.create_api(state.base_url, state.auth_token)
        val response = try {
            api.sync(
                SyncRequestDto(
                    phone_device_id = state.phone_device_id,
                    last_sync_at = state.last_sync_at,
                    changed_bags = changed_bags.map { it.to_dto() },
                    changed_items = changed_items.map { it.to_dto() }
                )
            )
        } catch (e: Exception) {
            val message = describe_remote_exception(e, action = "The bag update failed")
            device_state_store.set_connection_error(message, bag_id = selectedBagId, status = PiConnectionStatus.STATUS_PI_OFFLINE)
            device_state_store.set_sync_status(status = PiConnectionStatus.SYNC_STATUS_FAILED, error = message)
            throw IllegalStateException(message, e)
        }

        response.server_bag_changes
            .map { it.to_model() }
            .filter { it.bag_id == selectedBagId || it.bag_id in pairedBagIds }
            .forEach {
            item_repository.apply_server_bag(it, state.last_sync_at)
        }
        response.server_item_changes
            .map { it.to_model() }
            .filter { it.bag_id == selectedBagId || it.bag_id in pairedBagIds }
            .forEach {
            item_repository.apply_server_item(it, state.last_sync_at)
        }

        val conflicts = response.conflicts
            .map { it.to_model() }
            .filter { it.server_version.bag_id == selectedBagId }
        conflict_dao.clear_all()
        if (conflicts.isNotEmpty()) {
            conflict_dao.upsert_all(conflicts.map { it.to_entity() })
            device_state_store.set_has_unresolved_conflicts(true)
            device_state_store.set_auto_sync(false)
        } else {
            device_state_store.set_has_unresolved_conflicts(false)
        }

        val alerts = response.alerts.map { it.to_model() }.filter { it.bag_id == selectedBagId }
        if (alerts.isNotEmpty()) {
            NotificationHelper.notify_alerts(context, alerts)
        }

        val syncStatus = try {
            api.sync_status()
        } catch (_: Exception) {
            null
        }
        if (syncStatus != null) {
            device_state_store.set_connection_snapshot(
                status = syncStatus.connection_status,
                pendingChangesCount = syncStatus.pending_changes_count,
                localIp = syncStatus.local_ip,
                lastSyncAt = syncStatus.last_sync_at,
                bag_id = selectedBagId
            )
        } else {
            device_state_store.set_last_sync_at(response.server_time_ms, bag_id = selectedBagId)
        }
        device_state_store.set_sync_status(PiConnectionStatus.SYNC_STATUS_IDLE)
        return SyncRunResult(
            server_time_ms = response.server_time_ms,
            conflicts = conflicts,
            auto_resolved = response.auto_resolved.map { AutoResolved(it.item_id, it.rule) },
            alerts = alerts
        )
    }

    private suspend fun validate_sync_payload(selectedBagId: String, changed_bags: List<BagProfile>, changed_items: List<Item>) {
        val knownBagIds = (item_repository.observe_bags().first().map { it.bag_id } + changed_bags.map { it.bag_id }).toSet()
        val invalidBag = changed_bags.firstOrNull {
            it.bag_id.isBlank() ||
                it.name.isBlank() ||
                it.bag_id != selectedBagId ||
                it.size_liters !in setOf(25, 44, 66) ||
                it.template_id.isBlank() ||
                it.updated_by.isBlank()
        }
        if (invalidBag != null) {
            val label = invalidBag.name.ifBlank { invalidBag.bag_id }
            throw IllegalStateException("We could not update '$label' because some bag details are missing.")
        }

        val invalidItem = changed_items.firstOrNull {
            it.id.isBlank() ||
                it.bag_id.isBlank() ||
                it.bag_id != selectedBagId ||
                (!it.deleted && it.bag_id !in knownBagIds) ||
                it.name.isBlank() ||
                it.unit.isBlank() ||
                it.updated_by.isBlank() ||
                it.quantity <= 0.0
        }
        if (invalidItem != null) {
            val label = invalidItem.name.ifBlank { invalidItem.id }
            val reason = when {
                invalidItem.bag_id.isBlank() -> "is missing its bag"
                invalidItem.bag_id != selectedBagId -> "belongs to a different bag"
                !invalidItem.deleted && invalidItem.bag_id !in knownBagIds -> "points to a bag that is not saved on this phone"
                invalidItem.name.isBlank() -> "is missing a name"
                invalidItem.unit.isBlank() -> "is missing a unit"
                invalidItem.updated_by.isBlank() -> "is missing update details"
                invalidItem.quantity <= 0.0 -> "has an invalid amount"
                else -> "is missing required details"
            }
            throw IllegalStateException("We could not update '$label' because it $reason.")
        }
    }

    private fun describe_remote_exception(error: Exception, action: String): String {
        if (error is IllegalStateException && error.cause == null) {
            return error.message ?: action
        }
        if (error is HttpException) {
            val detail = error.response()?.errorBody()?.string()?.let(::parse_fastapi_detail)
            return when {
                error.code() == 422 && !detail.isNullOrBlank() -> "$action. $detail"
                error.code() == 422 -> "$action. The bag did not accept the update."
                !detail.isNullOrBlank() -> "$action. $detail"
                else -> "$action."
            }
        }
        return error.message?.let { "$action. $it" } ?: action
    }

    private fun parse_fastapi_detail(raw: String): String? {
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return raw.ifBlank { null }
        if (!parsed.isJsonObject) {
            return raw.ifBlank { null }
        }
        val detail = parsed.asJsonObject.get("detail") ?: return raw.ifBlank { null }
        return when {
            detail.isJsonPrimitive -> detail.asString
            detail.isJsonArray -> detail.asJsonArray
                .mapNotNull(::format_fastapi_detail_entry)
                .joinToString("; ")
                .ifBlank { null }
            else -> detail.toString()
        }
    }

    private fun format_fastapi_detail_entry(entry: JsonElement): String? {
        if (!entry.isJsonObject) {
            return entry.toString().ifBlank { null }
        }
        val message = entry.asJsonObject.get("msg")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val location = entry.asJsonObject.get("loc")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { part -> if (part.isJsonPrimitive) part.asString else null }
            ?.joinToString(".")
            .orEmpty()
        return listOfNotNull(location.ifBlank { null }, message.ifBlank { null }).joinToString(": ").ifBlank { null }
    }
}
