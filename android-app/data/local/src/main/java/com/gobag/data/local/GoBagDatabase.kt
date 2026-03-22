package com.gobag.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BagEntity::class, ItemEntity::class, RecommendedItemEntity::class, ConflictEntity::class],
    version = 2,
    exportSchema = false
)
abstract class GoBagDatabase : RoomDatabase() {
    abstract fun bag_dao(): BagDao
    abstract fun item_dao(): ItemDao
    abstract fun recommended_item_dao(): RecommendedItemDao
    abstract fun conflict_dao(): ConflictDao
}
