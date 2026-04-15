package com.gobag.data.repository

import com.gobag.core.model.BagProfile
import com.gobag.core.model.Item
import com.gobag.core.model.RecommendedItem
import com.gobag.data.local.BagDao
import com.gobag.data.local.ItemDao
import com.gobag.data.local.RecommendedItemDao
import com.gobag.data.local.to_entity
import com.gobag.data.local.to_model
import com.gobag.domain.repository.ItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class GoBagItemRepository(
    private val bag_dao: BagDao,
    private val item_dao: ItemDao,
    private val recommended_item_dao: RecommendedItemDao
) : ItemRepository {
    override fun observe_items(bag_id: String): Flow<List<Item>> =
        item_dao.observe_items(bag_id).map { rows -> rows.map { it.to_model() } }

    override fun observe_bags(): Flow<List<BagProfile>> =
        bag_dao.observe_bags().map { rows -> rows.map { it.to_model() } }

    override fun observe_pending_phone_change_count(bag_id: String, last_sync_at: Long): Flow<Int> =
        combine(
            bag_dao.observe_pending_phone_change_count(bag_id, last_sync_at),
            item_dao.observe_pending_phone_change_count(bag_id, last_sync_at)
        ) { bag_changes, item_changes ->
            bag_changes + item_changes
        }

    override suspend fun get_bag(bag_id: String): BagProfile? = bag_dao.get_by_id(bag_id)?.to_model()

    override suspend fun upsert_item(item: Item) {
        item_dao.upsert(item.to_entity())
    }

    override suspend fun upsert_items(items: List<Item>) {
        if (items.isNotEmpty()) item_dao.upsert_all(items.map { it.to_entity() })
    }

    override suspend fun soft_delete_item(item: Item) {
        item_dao.upsert(item.copy(deleted = true).to_entity())
    }

    override suspend fun get_item(id: String): Item? = item_dao.get_by_id(id)?.to_model()

    override suspend fun get_items_changed_since(last_sync_at: Long): List<Item> =
        item_dao.changed_since(last_sync_at).map { it.to_model() }

    override suspend fun apply_server_item(item: Item, last_sync_at: Long) {
        val local = item_dao.get_by_id(item.id)
        if (local == null || local.updated_at <= last_sync_at) item_dao.upsert(item.to_entity())
    }

    override suspend fun upsert_bag(bag: BagProfile) {
        bag_dao.upsert(bag.to_entity())
    }

    override suspend fun upsert_bags(bags: List<BagProfile>) {
        if (bags.isNotEmpty()) bag_dao.upsert_all(bags.map { it.to_entity() })
    }

    override suspend fun get_bags_changed_since(last_sync_at: Long): List<BagProfile> =
        bag_dao.changed_since(last_sync_at).map { it.to_model() }

    override suspend fun apply_server_bag(bag: BagProfile, last_sync_at: Long) {
        val local = bag_dao.get_by_id(bag.bag_id)
        if (local == null || local.updated_at <= last_sync_at) bag_dao.upsert(bag.to_entity())
    }

    override suspend fun get_templates(): List<RecommendedItem> =
        recommended_item_dao.get_all().map { it.to_model() }
}
