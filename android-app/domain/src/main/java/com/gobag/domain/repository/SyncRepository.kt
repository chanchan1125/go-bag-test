package com.gobag.domain.repository

import com.gobag.core.model.AlertModel
import com.gobag.core.model.AutoResolved
import com.gobag.core.model.Conflict
import com.gobag.core.model.DeviceState
import com.gobag.core.model.Item
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    fun observe_device_state(): Flow<DeviceState>
    fun observe_conflicts(): Flow<List<Conflict>>
    suspend fun set_auto_sync(enabled: Boolean)
    suspend fun set_selected_bag_id(bag_id: String)
    suspend fun refresh_remote_status(): String?
    suspend fun resolve_conflict_keep_phone(item: Item)
    suspend fun resolve_conflict_keep_pi(item: Item)
    suspend fun resolve_conflict_keep_deleted(item: Item)
    suspend fun resolve_conflict_restore(item: Item)
    suspend fun run_sync_now(): SyncRunResult
}

data class SyncRunResult(
    val server_time_ms: Long,
    val conflicts: List<Conflict>,
    val auto_resolved: List<AutoResolved>,
    val alerts: List<AlertModel>,
    val skipped_reason: String? = null
)
