package com.gobag.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.gobag.core.common.DeviceIdProvider
import com.gobag.core.model.DeviceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class DeviceStateStore(context: Context) {
    private val data_store = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("device_state.preferences_pb") }
    )

    companion object {
        private val PHONE_DEVICE_ID = stringPreferencesKey("phone_device_id")
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val PI_DEVICE_ID = stringPreferencesKey("pi_device_id")
        private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        private val TIME_OFFSET_MS = longPreferencesKey("time_offset_ms")
        private val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val SELECTED_BAG_ID = stringPreferencesKey("selected_bag_id")
        private val HAS_UNRESOLVED_CONFLICTS = booleanPreferencesKey("has_unresolved_conflicts")
        private val CONNECTION_STATUS = stringPreferencesKey("connection_status")
        private val PENDING_CHANGES_COUNT = longPreferencesKey("pending_changes_count")
        private val LOCAL_IP = stringPreferencesKey("local_ip")
        private val LAST_CONNECTION_ERROR = stringPreferencesKey("last_connection_error")
    }

    val state: Flow<DeviceState> = data_store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            DeviceState(
                phone_device_id = prefs[PHONE_DEVICE_ID] ?: DeviceIdProvider.generate(),
                auth_token = prefs[AUTH_TOKEN] ?: "",
                base_url = prefs[BASE_URL] ?: "",
                pi_device_id = prefs[PI_DEVICE_ID] ?: "",
                last_sync_at = prefs[LAST_SYNC_AT] ?: 0L,
                time_offset_ms = prefs[TIME_OFFSET_MS] ?: 0L,
                auto_sync_enabled = prefs[AUTO_SYNC_ENABLED] ?: false,
                selected_bag_id = prefs[SELECTED_BAG_ID] ?: "",
                has_unresolved_conflicts = prefs[HAS_UNRESOLVED_CONFLICTS] ?: false,
                connection_status = prefs[CONNECTION_STATUS] ?: "unknown",
                pending_changes_count = (prefs[PENDING_CHANGES_COUNT] ?: 0L).toInt(),
                local_ip = prefs[LOCAL_IP] ?: "",
                last_connection_error = prefs[LAST_CONNECTION_ERROR] ?: ""
            )
        }

    suspend fun initialize_phone_device_id_if_missing() {
        data_store.edit { prefs ->
            if (prefs[PHONE_DEVICE_ID].isNullOrBlank()) prefs[PHONE_DEVICE_ID] = DeviceIdProvider.generate()
            if (!prefs.contains(AUTO_SYNC_ENABLED)) prefs[AUTO_SYNC_ENABLED] = false
            if (!prefs.contains(HAS_UNRESOLVED_CONFLICTS)) prefs[HAS_UNRESOLVED_CONFLICTS] = false
            if (!prefs.contains(CONNECTION_STATUS)) prefs[CONNECTION_STATUS] = "unknown"
            if (!prefs.contains(PENDING_CHANGES_COUNT)) prefs[PENDING_CHANGES_COUNT] = 0L
            if (!prefs.contains(LOCAL_IP)) prefs[LOCAL_IP] = ""
            if (!prefs.contains(LAST_CONNECTION_ERROR)) prefs[LAST_CONNECTION_ERROR] = ""
        }
    }

    suspend fun update_pairing(auth_token: String, base_url: String, pi_device_id: String, time_offset_ms: Long) {
        data_store.edit { prefs ->
            prefs[AUTH_TOKEN] = auth_token
            prefs[BASE_URL] = base_url
            prefs[PI_DEVICE_ID] = pi_device_id
            prefs[TIME_OFFSET_MS] = time_offset_ms
            prefs[LAST_SYNC_AT] = 0L
            prefs[CONNECTION_STATUS] = "paired"
            prefs[LAST_CONNECTION_ERROR] = ""
        }
    }

    suspend fun clear_pairing() {
        data_store.edit { prefs ->
            prefs[AUTH_TOKEN] = ""
            prefs[BASE_URL] = ""
            prefs[PI_DEVICE_ID] = ""
            prefs[TIME_OFFSET_MS] = 0L
            prefs[LAST_SYNC_AT] = 0L
            prefs[CONNECTION_STATUS] = "not_paired"
            prefs[PENDING_CHANGES_COUNT] = 0L
            prefs[LOCAL_IP] = ""
            prefs[LAST_CONNECTION_ERROR] = ""
        }
    }

    suspend fun set_base_url(value: String) {
        data_store.edit { prefs ->
            prefs[BASE_URL] = value
            if ((prefs[AUTH_TOKEN] ?: "").isBlank()) {
                prefs[CONNECTION_STATUS] = "address_saved"
            }
            prefs[LAST_CONNECTION_ERROR] = ""
        }
    }

    suspend fun set_last_sync_at(value: Long) {
        data_store.edit { it[LAST_SYNC_AT] = value }
    }

    suspend fun set_auto_sync(enabled: Boolean) {
        data_store.edit { it[AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun set_selected_bag_id(bag_id: String) {
        data_store.edit { it[SELECTED_BAG_ID] = bag_id }
    }

    suspend fun set_has_unresolved_conflicts(value: Boolean) {
        data_store.edit { it[HAS_UNRESOLVED_CONFLICTS] = value }
    }

    suspend fun set_connection_snapshot(
        status: String,
        pendingChangesCount: Int,
        localIp: String,
        lastSyncAt: Long? = null
    ) {
        data_store.edit { prefs ->
            prefs[CONNECTION_STATUS] = status
            prefs[PENDING_CHANGES_COUNT] = pendingChangesCount.toLong()
            prefs[LOCAL_IP] = localIp
            prefs[LAST_CONNECTION_ERROR] = ""
            if (lastSyncAt != null) prefs[LAST_SYNC_AT] = lastSyncAt
        }
    }

    suspend fun set_connection_error(message: String) {
        data_store.edit { prefs ->
            prefs[LAST_CONNECTION_ERROR] = message
            prefs[CONNECTION_STATUS] = "offline"
        }
    }
}
