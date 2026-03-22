package com.gobag.domain.usecase

import com.gobag.core.model.Item
import com.gobag.domain.repository.ItemRepository
import kotlinx.coroutines.flow.Flow

class ObserveItemsUseCase(private val item_repository: ItemRepository) {
    operator fun invoke(bag_id: String): Flow<List<Item>> = item_repository.observe_items(bag_id)
}
