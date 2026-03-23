package com.gobag.app

import android.content.Context
import com.gobag.data.local.LocalDataSourceFactory
import com.gobag.data.repository.DeviceStateStore
import com.gobag.data.repository.GoBagItemRepository
import com.gobag.data.repository.GoBagPairingRepository
import com.gobag.data.repository.GoBagSyncRepository

class AppContainer(context: Context) {
    private val db = LocalDataSourceFactory.create_db(context)
    val device_state_store = DeviceStateStore(context)

    val item_repository = GoBagItemRepository(db.bag_dao(), db.item_dao(), db.recommended_item_dao())
    val sync_repository = GoBagSyncRepository(context, item_repository, db.conflict_dao(), device_state_store)
    val pairing_repository = GoBagPairingRepository(device_state_store, db.recommended_item_dao(), item_repository, sync_repository)
}
