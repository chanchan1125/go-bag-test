package com.gobag.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BagDao {
    @Query("SELECT * FROM bags ORDER BY name")
    fun observe_bags(): Flow<List<BagEntity>>

    @Query("SELECT COUNT(*) FROM bags WHERE bag_id = :bag_id AND updated_at > :last_sync_at")
    fun observe_pending_phone_change_count(bag_id: String, last_sync_at: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bag: BagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert_all(bags: List<BagEntity>)

    @Query("SELECT * FROM bags WHERE bag_id = :bag_id LIMIT 1")
    suspend fun get_by_id(bag_id: String): BagEntity?

    @Query("SELECT * FROM bags WHERE updated_at > :last_sync_at")
    suspend fun changed_since(last_sync_at: Long): List<BagEntity>
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE bag_id = :bag_id AND deleted = 0 ORDER BY category, name")
    fun observe_items(bag_id: String): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items WHERE bag_id = :bag_id AND updated_at > :last_sync_at")
    fun observe_pending_phone_change_count(bag_id: String, last_sync_at: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert_all(items: List<ItemEntity>)

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun get_by_id(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE updated_at > :last_sync_at")
    suspend fun changed_since(last_sync_at: Long): List<ItemEntity>
}

@Dao
interface RecommendedItemDao {
    @Query("SELECT * FROM recommended_templates")
    suspend fun get_all(): List<RecommendedItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert_all(items: List<RecommendedItemEntity>)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY item_id")
    fun observe_conflicts(): Flow<List<ConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert_all(conflicts: List<ConflictEntity>)

    @Query("DELETE FROM conflicts")
    suspend fun clear_all()

    @Query("DELETE FROM conflicts WHERE item_id = :item_id")
    suspend fun clear_item(item_id: String)
}
