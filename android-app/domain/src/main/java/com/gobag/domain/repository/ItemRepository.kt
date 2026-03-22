package com.gobag.domain.repository

import com.gobag.core.model.BagProfile
import com.gobag.core.model.Item
import com.gobag.core.model.RecommendedItem
import kotlinx.coroutines.flow.Flow

interface ItemRepository {
    fun observe_items(bag_id: String): Flow<List<Item>>
    fun observe_bags(): Flow<List<BagProfile>>
    suspend fun get_bag(bag_id: String): BagProfile?
    suspend fun upsert_item(item: Item)
    suspend fun upsert_items(items: List<Item>)
    suspend fun soft_delete_item(item: Item)
    suspend fun get_item(id: String): Item?
    suspend fun get_items_changed_since(last_sync_at: Long): List<Item>
    suspend fun apply_server_item(item: Item, last_sync_at: Long)
    suspend fun upsert_bag(bag: BagProfile)
    suspend fun upsert_bags(bags: List<BagProfile>)
    suspend fun get_bags_changed_since(last_sync_at: Long): List<BagProfile>
    suspend fun apply_server_bag(bag: BagProfile, last_sync_at: Long)
    suspend fun get_templates(): List<RecommendedItem>
}
