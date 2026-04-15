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
import com.gobag.core.model.PairedBagConnection
import com.gobag.core.model.SavedPiAddress
import com.gobag.domain.logic.PiConnectionStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

class DeviceStateStore(context: Context) {
    private val data_store = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("device_state.preferences_pb") }
    )
    private val gson = Gson()
    private val paired_bag_list_type = object : TypeToken<List<PairedBagConnection>>() {}.type
    private val saved_address_list_type = object : TypeToken<List<SavedPiAddress>>() {}.type

    companion object {
        private val PHONE_DEVICE_ID = stringPreferencesKey("phone_device_id")
        private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        private val TIME_OFFSET_MS = longPreferencesKey("time_offset_ms")
        private val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val DARK_THEME_ENABLED = booleanPreferencesKey("dark_theme_enabled")
        private val SELECTED_BAG_ID = stringPreferencesKey("selected_bag_id")
        private val HAS_UNRESOLVED_CONFLICTS = booleanPreferencesKey("has_unresolved_conflicts")
        private val CONNECTION_STATUS = stringPreferencesKey("connection_status")
        private val SYNC_STATUS = stringPreferencesKey("sync_status")
        private val PENDING_CHANGES_COUNT = longPreferencesKey("pending_changes_count")
        private val LOCAL_IP = stringPreferencesKey("local_ip")
        private val LAST_CONNECTION_ERROR = stringPreferencesKey("last_connection_error")
        private val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
        private val LAST_CONNECTION_CHECK_AT = longPreferencesKey("last_connection_check_at")
        private val LAST_CONNECTED_AT = longPreferencesKey("last_connected_at")
        private val PAIRED_BAGS_JSON = stringPreferencesKey("paired_bags_json")
        private val SAVED_ADDRESSES_JSON = stringPreferencesKey("saved_addresses_json")
        private val ACTIVE_ADDRESS_ID = stringPreferencesKey("active_address_id")
    }

    val state: Flow<DeviceState> = data_store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val pairedBags = parse_paired_bags(prefs[PAIRED_BAGS_JSON])
            val savedAddresses = normalize_saved_addresses(
                parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]),
                prefs[ACTIVE_ADDRESS_ID].orEmpty()
            )
            val storedSelectedBagId = prefs[SELECTED_BAG_ID].orEmpty()
            val selectedBagId = when {
                storedSelectedBagId.isNotBlank() && pairedBags.any { it.bag_id == storedSelectedBagId } -> storedSelectedBagId
                pairedBags.isNotEmpty() -> pairedBags.first().bag_id
                else -> ""
            }
            val activeBag = pairedBags.firstOrNull { it.bag_id == selectedBagId }
            val activeAddress = savedAddresses.firstOrNull { it.is_active }
            val hasSavedAddress = savedAddresses.isNotEmpty() || activeBag?.base_url?.isNotBlank() == true
            val normalizedConnectionStatus = PiConnectionStatus.normalize_connection_status(
                value = activeBag?.connection_status ?: prefs[CONNECTION_STATUS],
                is_paired = activeBag != null || pairedBags.isNotEmpty(),
                has_saved_address = hasSavedAddress
            )
            DeviceState(
                phone_device_id = prefs[PHONE_DEVICE_ID] ?: DeviceIdProvider.generate(),
                auth_token = activeBag?.auth_token.orEmpty(),
                base_url = activeBag?.base_url ?: activeAddress?.base_url.orEmpty(),
                pi_device_id = activeBag?.pi_device_id.orEmpty(),
                local_base_url = activeBag?.local_base_url.orEmpty(),
                remote_base_url = activeBag?.remote_base_url.orEmpty(),
                last_sync_at = activeBag?.last_sync_at ?: (prefs[LAST_SYNC_AT] ?: 0L),
                time_offset_ms = activeBag?.time_offset_ms ?: (prefs[TIME_OFFSET_MS] ?: 0L),
                auto_sync_enabled = prefs[AUTO_SYNC_ENABLED] ?: false,
                selected_bag_id = selectedBagId,
                has_unresolved_conflicts = prefs[HAS_UNRESOLVED_CONFLICTS] ?: false,
                connection_status = normalizedConnectionStatus,
                sync_status = PiConnectionStatus.normalize_sync_status(prefs[SYNC_STATUS]),
                pending_changes_count = activeBag?.pending_changes_count ?: (prefs[PENDING_CHANGES_COUNT] ?: 0L).toInt(),
                local_ip = activeBag?.local_ip ?: (prefs[LOCAL_IP] ?: ""),
                last_connection_mode = activeBag?.last_connection_mode.orEmpty(),
                last_connection_error = activeBag?.last_connection_error
                    ?: (prefs[LAST_CONNECTION_ERROR] ?: ""),
                last_sync_error = prefs[LAST_SYNC_ERROR] ?: "",
                last_connection_check_at = prefs[LAST_CONNECTION_CHECK_AT] ?: 0L,
                last_connected_at = prefs[LAST_CONNECTED_AT] ?: 0L,
                paired_bags = pairedBags,
                saved_addresses = savedAddresses,
                active_address_id = activeAddress?.id.orEmpty()
            )
        }

    val dark_theme_enabled: Flow<Boolean> = data_store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[DARK_THEME_ENABLED] ?: true }

    suspend fun initialize_phone_device_id_if_missing() {
        data_store.edit { prefs ->
            if (prefs[PHONE_DEVICE_ID].isNullOrBlank()) prefs[PHONE_DEVICE_ID] = DeviceIdProvider.generate()
            if (!prefs.contains(AUTO_SYNC_ENABLED)) prefs[AUTO_SYNC_ENABLED] = false
            if (!prefs.contains(DARK_THEME_ENABLED)) prefs[DARK_THEME_ENABLED] = true
            if (!prefs.contains(HAS_UNRESOLVED_CONFLICTS)) prefs[HAS_UNRESOLVED_CONFLICTS] = false
            if (!prefs.contains(CONNECTION_STATUS)) prefs[CONNECTION_STATUS] = PiConnectionStatus.STATUS_NO_PI_PAIRED
            if (!prefs.contains(SYNC_STATUS)) prefs[SYNC_STATUS] = PiConnectionStatus.SYNC_STATUS_IDLE
            if (!prefs.contains(PENDING_CHANGES_COUNT)) prefs[PENDING_CHANGES_COUNT] = 0L
            if (!prefs.contains(LOCAL_IP)) prefs[LOCAL_IP] = ""
            if (!prefs.contains(LAST_CONNECTION_ERROR)) prefs[LAST_CONNECTION_ERROR] = ""
            if (!prefs.contains(LAST_SYNC_ERROR)) prefs[LAST_SYNC_ERROR] = ""
            if (!prefs.contains(LAST_CONNECTION_CHECK_AT)) prefs[LAST_CONNECTION_CHECK_AT] = 0L
            if (!prefs.contains(LAST_CONNECTED_AT)) prefs[LAST_CONNECTED_AT] = 0L
            if (!prefs.contains(PAIRED_BAGS_JSON)) prefs[PAIRED_BAGS_JSON] = "[]"
            if (!prefs.contains(SAVED_ADDRESSES_JSON)) prefs[SAVED_ADDRESSES_JSON] = "[]"
            if (!prefs.contains(ACTIVE_ADDRESS_ID)) prefs[ACTIVE_ADDRESS_ID] = ""
        }
    }

    suspend fun upsert_paired_bag_connection(
        bag_id: String,
        auth_token: String,
        base_url: String,
        pi_device_id: String,
        time_offset_ms: Long,
        local_base_url: String? = null,
        remote_base_url: String? = null,
        last_connection_mode: String? = null
    ) {
        data_store.edit { prefs ->
            val existing = parse_paired_bags(prefs[PAIRED_BAGS_JSON]).toMutableList()
            val current = existing.firstOrNull { it.bag_id == bag_id }
            val updated = PairedBagConnection(
                bag_id = bag_id,
                base_url = base_url,
                auth_token = auth_token,
                pi_device_id = pi_device_id,
                last_sync_at = current?.last_sync_at ?: 0L,
                time_offset_ms = time_offset_ms,
                connection_status = PiConnectionStatus.normalize_connection_status(
                    value = current?.connection_status ?: PiConnectionStatus.STATUS_PI_PAIRED,
                    is_paired = true,
                    has_saved_address = true
                ),
                pending_changes_count = current?.pending_changes_count ?: 0,
                local_ip = current?.local_ip ?: "",
                last_connection_error = "",
                paired_at = current?.paired_at ?: System.currentTimeMillis(),
                local_base_url = local_base_url ?: current?.local_base_url,
                remote_base_url = remote_base_url ?: current?.remote_base_url,
                last_connection_mode = last_connection_mode ?: current?.last_connection_mode
            )
            existing.removeAll { it.bag_id == bag_id }
            existing += updated
            prefs[PAIRED_BAGS_JSON] = gson.toJson(existing)
            prefs[SELECTED_BAG_ID] = bag_id
            prefs[CONNECTION_STATUS] = PiConnectionStatus.STATUS_PI_PAIRED
            prefs[LAST_CONNECTION_ERROR] = ""
        }
    }

    suspend fun clear_pairing_for_bag(bag_id: String) {
        data_store.edit { prefs ->
            val pairedBags = parse_paired_bags(prefs[PAIRED_BAGS_JSON]).filterNot { it.bag_id == bag_id }
            val savedAddresses = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON])
            prefs[PAIRED_BAGS_JSON] = gson.toJson(pairedBags)
            if ((prefs[SELECTED_BAG_ID] ?: "") == bag_id) {
                prefs[SELECTED_BAG_ID] = pairedBags.firstOrNull()?.bag_id.orEmpty()
            }
            prefs[CONNECTION_STATUS] = PiConnectionStatus.normalize_connection_status(
                value = null,
                is_paired = pairedBags.isNotEmpty(),
                has_saved_address = savedAddresses.isNotEmpty()
            )
            prefs[LAST_CONNECTION_ERROR] = ""
            if (pairedBags.isEmpty()) {
                prefs[SYNC_STATUS] = PiConnectionStatus.SYNC_STATUS_IDLE
                prefs[LAST_SYNC_ERROR] = ""
            }
        }
    }

    suspend fun upsert_saved_address(base_url: String, address_id: String? = null, make_active: Boolean = true): SavedPiAddress {
        var saved = SavedPiAddress(
            id = address_id ?: UUID.randomUUID().toString(),
            base_url = base_url,
            last_status = "Saved",
            last_detail = "Location saved. Check it when you're ready.",
            last_checked_at = 0L,
            is_active = make_active
        )
        data_store.edit { prefs ->
            val addresses = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]).toMutableList()
            val duplicate = addresses.firstOrNull {
                it.base_url.equals(base_url, ignoreCase = true) && (address_id == null || it.id != address_id)
            }
            val existing = addresses.firstOrNull { it.id == address_id } ?: duplicate
            saved = SavedPiAddress(
                id = existing?.id ?: saved.id,
                base_url = base_url,
                last_status = existing?.last_status ?: saved.last_status,
                last_detail = existing?.last_detail ?: saved.last_detail,
                last_checked_at = existing?.last_checked_at ?: saved.last_checked_at,
                is_active = make_active || existing?.is_active == true
            )
            addresses.removeAll { it.id == saved.id || it.base_url.equals(base_url, ignoreCase = true) }
            addresses += saved
            val normalized = normalize_saved_addresses(addresses, if (saved.is_active) saved.id else prefs[ACTIVE_ADDRESS_ID].orEmpty())
            prefs[SAVED_ADDRESSES_JSON] = gson.toJson(normalized)
            prefs[ACTIVE_ADDRESS_ID] = normalized.firstOrNull { it.is_active }?.id.orEmpty()
            if (parse_paired_bags(prefs[PAIRED_BAGS_JSON]).isEmpty()) {
                prefs[CONNECTION_STATUS] = PiConnectionStatus.STATUS_ADDRESS_SAVED
                prefs[LAST_CONNECTION_ERROR] = ""
            }
        }
        return saved
    }

    suspend fun delete_saved_address(address_id: String) {
        data_store.edit { prefs ->
            val remaining = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]).filterNot { it.id == address_id }
            val nextActiveId = if ((prefs[ACTIVE_ADDRESS_ID] ?: "") == address_id) remaining.firstOrNull()?.id.orEmpty() else prefs[ACTIVE_ADDRESS_ID].orEmpty()
            val normalized = normalize_saved_addresses(remaining, nextActiveId)
            prefs[SAVED_ADDRESSES_JSON] = gson.toJson(normalized)
            prefs[ACTIVE_ADDRESS_ID] = normalized.firstOrNull { it.is_active }?.id.orEmpty()
        }
    }

    suspend fun set_active_address(address_id: String) {
        data_store.edit { prefs ->
            val normalized = normalize_saved_addresses(parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]), address_id)
            prefs[SAVED_ADDRESSES_JSON] = gson.toJson(normalized)
            prefs[ACTIVE_ADDRESS_ID] = normalized.firstOrNull { it.is_active }?.id.orEmpty()
            if (prefs[SELECTED_BAG_ID].isNullOrBlank()) {
                prefs[CONNECTION_STATUS] = if (normalized.isEmpty()) {
                    PiConnectionStatus.STATUS_NO_PI_PAIRED
                } else {
                    PiConnectionStatus.STATUS_ADDRESS_SAVED
                }
                prefs[LAST_CONNECTION_ERROR] = ""
            }
        }
    }

    suspend fun update_saved_address_status(
        address_id: String,
        status: String,
        detail: String,
        checked_at: Long = System.currentTimeMillis(),
        make_active: Boolean = false
    ) {
        data_store.edit { prefs ->
            val addresses = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]).map { address ->
                if (address.id == address_id) {
                    address.copy(
                        last_status = status,
                        last_detail = detail,
                        last_checked_at = checked_at,
                        is_active = make_active || address.is_active
                    )
                } else {
                    address
                }
            }
            val activeId = when {
                make_active -> address_id
                prefs[ACTIVE_ADDRESS_ID].isNullOrBlank() -> addresses.firstOrNull()?.id.orEmpty()
                else -> prefs[ACTIVE_ADDRESS_ID].orEmpty()
            }
            val normalized = normalize_saved_addresses(addresses, activeId)
            prefs[SAVED_ADDRESSES_JSON] = gson.toJson(normalized)
            prefs[ACTIVE_ADDRESS_ID] = normalized.firstOrNull { it.is_active }?.id.orEmpty()
        }
    }

    suspend fun set_last_sync_at(value: Long, bag_id: String? = null) {
        update_paired_bag_connection(bag_id.orEmpty(), preserve_when_missing = true) { it.copy(last_sync_at = value, last_connection_error = "") }
        data_store.edit { it[LAST_SYNC_AT] = value }
    }

    suspend fun set_connection_checking(bag_id: String? = null) {
        val effectiveBagId = bag_id ?: state.map { it.selected_bag_id }.first_or_default("")
        update_paired_bag_connection(effectiveBagId, preserve_when_missing = true) {
            it.copy(
                connection_status = PiConnectionStatus.STATUS_CHECKING_CONNECTION,
                last_connection_error = ""
            )
        }
        data_store.edit { prefs ->
            prefs[CONNECTION_STATUS] = PiConnectionStatus.STATUS_CHECKING_CONNECTION
            prefs[LAST_CONNECTION_ERROR] = ""
            prefs[LAST_CONNECTION_CHECK_AT] = System.currentTimeMillis()
        }
    }

    suspend fun set_auto_sync(enabled: Boolean) {
        data_store.edit { it[AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun set_dark_theme_enabled(enabled: Boolean) {
        data_store.edit { it[DARK_THEME_ENABLED] = enabled }
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
        lastSyncAt: Long? = null,
        bag_id: String? = null,
        resolvedBaseUrl: String? = null,
        localBaseUrl: String? = null,
        remoteBaseUrl: String? = null,
        connectionMode: String? = null
    ) {
        val effectiveBagId = bag_id ?: state.map { it.selected_bag_id }.first_or_default("")
        val checkedAt = System.currentTimeMillis()
        update_paired_bag_connection(effectiveBagId, preserve_when_missing = true) {
            it.copy(
                base_url = resolvedBaseUrl ?: it.base_url,
                connection_status = PiConnectionStatus.STATUS_PI_ONLINE,
                pending_changes_count = pendingChangesCount,
                local_ip = localIp,
                last_sync_at = lastSyncAt ?: it.last_sync_at,
                last_connection_error = "",
                local_base_url = localBaseUrl ?: it.local_base_url,
                remote_base_url = remoteBaseUrl ?: it.remote_base_url,
                last_connection_mode = connectionMode ?: it.last_connection_mode
            )
        }
        data_store.edit { prefs ->
            prefs[CONNECTION_STATUS] = PiConnectionStatus.normalize_connection_status(
                value = status,
                is_paired = parse_paired_bags(prefs[PAIRED_BAGS_JSON]).isNotEmpty(),
                has_saved_address = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]).isNotEmpty()
            )
            prefs[PENDING_CHANGES_COUNT] = pendingChangesCount.toLong()
            prefs[LOCAL_IP] = localIp
            prefs[LAST_CONNECTION_ERROR] = ""
            prefs[LAST_CONNECTION_CHECK_AT] = checkedAt
            prefs[LAST_CONNECTED_AT] = checkedAt
            if (lastSyncAt != null) prefs[LAST_SYNC_AT] = lastSyncAt
        }
    }

    suspend fun set_connection_error(
        message: String,
        bag_id: String? = null,
        status: String = PiConnectionStatus.STATUS_ADDRESS_UNREACHABLE
    ) {
        val effectiveBagId = bag_id ?: state.map { it.selected_bag_id }.first_or_default("")
        update_paired_bag_connection(effectiveBagId, preserve_when_missing = true) {
            it.copy(
                connection_status = PiConnectionStatus.normalize_connection_status(
                    value = status,
                    is_paired = true,
                    has_saved_address = true
                ),
                last_connection_error = message
            )
        }
        data_store.edit { prefs ->
            prefs[LAST_CONNECTION_ERROR] = message
            prefs[CONNECTION_STATUS] = PiConnectionStatus.normalize_connection_status(
                value = status,
                is_paired = parse_paired_bags(prefs[PAIRED_BAGS_JSON]).isNotEmpty(),
                has_saved_address = parse_saved_addresses(prefs[SAVED_ADDRESSES_JSON]).isNotEmpty()
            )
            prefs[LAST_CONNECTION_CHECK_AT] = System.currentTimeMillis()
        }
    }

    suspend fun set_sync_status(status: String, error: String = "") {
        data_store.edit { prefs ->
            prefs[SYNC_STATUS] = PiConnectionStatus.normalize_sync_status(status)
            prefs[LAST_SYNC_ERROR] = error
        }
    }

    suspend fun update_paired_bag_endpoint(
        base_url: String,
        bag_id: String? = null,
        pi_device_id: String? = null,
        local_base_url: String? = null,
        remote_base_url: String? = null,
        last_connection_mode: String? = null
    ) {
        if (base_url.isBlank()) return
        data_store.edit { prefs ->
            val existing = parse_paired_bags(prefs[PAIRED_BAGS_JSON])
            if (existing.isEmpty()) return@edit
            val selectedBagId = bag_id.orEmpty()
            val selectedPiId = pi_device_id.orEmpty()
            val updated = existing.map { bag ->
                val matchesBag = selectedBagId.isNotBlank() && bag.bag_id == selectedBagId
                val matchesPi = selectedPiId.isNotBlank() && bag.pi_device_id == selectedPiId
                if (matchesBag || matchesPi) {
                    bag.copy(
                        base_url = base_url,
                        local_base_url = local_base_url ?: bag.local_base_url,
                        remote_base_url = remote_base_url ?: bag.remote_base_url,
                        last_connection_mode = last_connection_mode ?: bag.last_connection_mode
                    )
                } else {
                    bag
                }
            }
            prefs[PAIRED_BAGS_JSON] = gson.toJson(updated)
        }
    }

    private suspend fun update_paired_bag_connection(
        bag_id: String,
        preserve_when_missing: Boolean,
        transform: (PairedBagConnection) -> PairedBagConnection
    ) {
        if (bag_id.isBlank()) return
        data_store.edit { prefs ->
            val existing = parse_paired_bags(prefs[PAIRED_BAGS_JSON]).toMutableList()
            val index = existing.indexOfFirst { it.bag_id == bag_id }
            if (index == -1) {
                if (!preserve_when_missing) return@edit
                return@edit
            }
            existing[index] = transform(existing[index])
            prefs[PAIRED_BAGS_JSON] = gson.toJson(existing)
        }
    }

    private fun parse_paired_bags(raw: String?): List<PairedBagConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<PairedBagConnection>>(raw, paired_bag_list_type) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    private fun parse_saved_addresses(raw: String?): List<SavedPiAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<SavedPiAddress>>(raw, saved_address_list_type) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    private fun normalize_saved_addresses(addresses: List<SavedPiAddress>, active_id: String): List<SavedPiAddress> {
        if (addresses.isEmpty()) return emptyList()
        val resolvedActiveId = when {
            active_id.isNotBlank() && addresses.any { it.id == active_id } -> active_id
            addresses.any { it.is_active } -> addresses.first { it.is_active }.id
            else -> addresses.first().id
        }
        return addresses
            .distinctBy { it.base_url.lowercase() }
            .map { address -> address.copy(is_active = address.id == resolvedActiveId) }
            .sortedBy { it.base_url.lowercase() }
    }
}

private suspend fun <T> Flow<T>.first_or_default(default: T): T {
    return runCatching { first() }.getOrDefault(default)
}
