package com.gobag.domain.usecase

import com.gobag.core.model.Item
import com.gobag.domain.repository.ItemRepository

class UpsertItemUseCase(private val itemRepository: ItemRepository) {
    suspend operator fun invoke(item: Item) = itemRepository.upsert_item(item)
}
