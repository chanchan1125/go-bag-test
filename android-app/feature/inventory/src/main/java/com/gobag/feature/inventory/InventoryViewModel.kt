package com.gobag.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobag.core.common.nowMs
import com.gobag.core.model.BagProfile
import com.gobag.core.model.Item
import com.gobag.domain.logic.PreparednessRules
import com.gobag.domain.repository.ItemRepository
import com.gobag.domain.repository.SyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class InventoryItemGroup(
    val bag_id: String,
    val name: String,
    val category: String,
    val unit: String,
    val total_quantity: Double,
    val batches: List<Item>
)

data class InventoryUiState(
    val bag_id: String = "",
    val selected_bag_name: String = "",
    val bags: List<BagProfile> = emptyList(),
    val primary_bag_name_input: String = "",
    val primary_bag_size_input: String = "44",
    val search: String = "",
    val category_filter: String = "",
    val name_input: String = "",
    val quantity_input: String = "1",
    val unit_input: String = "pcs",
    val category_input: String = "Tools & Protection",
    val notes_input: String = "",
    val expiry_input: String = "",
    val no_expiration: Boolean = false,
    val editing_item_id: String? = null,
    val item_groups: List<InventoryItemGroup> = emptyList(),
    val category_options: List<String> = PreparednessRules.checklist_categories,
    val unit_options: List<String> = PreparednessRules.unit_options,
    val bag_size_options: List<String> = listOf("25", "44", "66"),
    val feedback_message: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    private val item_repository: ItemRepository,
    private val sync_repository: SyncRepository,
    private val phone_device_id: String,
    initial_bag_id: String
) : ViewModel() {
    private val bag_id = MutableStateFlow(initial_bag_id)
    private val primary_bag_name_input = MutableStateFlow("")
    private val primary_bag_size_input = MutableStateFlow("44")
    private val search = MutableStateFlow("")
    private val category_filter = MutableStateFlow("")
    private val name_input = MutableStateFlow("")
    private val quantity_input = MutableStateFlow("1")
    private val unit_input = MutableStateFlow("pcs")
    private val category_input = MutableStateFlow("Tools & Protection")
    private val notes_input = MutableStateFlow("")
    private val expiry_input = MutableStateFlow("")
    private val no_expiration = MutableStateFlow(false)
    private val editing_item_id = MutableStateFlow<String?>(null)
    private val feedback_message = MutableStateFlow("")

    private val paired_bags = combine(item_repository.observe_bags(), sync_repository.observe_device_state()) { bags, state ->
        val pairedBagIds = state.paired_bags.map { it.bag_id }.toSet()
        bags.filter { it.bag_id in pairedBagIds }
    }

    init {
        viewModelScope.launch {
            sync_repository.observe_device_state().collect { state ->
                if (state.selected_bag_id != bag_id.value) {
                    bag_id.value = state.selected_bag_id
                    clear_form()
                }
            }
        }
        viewModelScope.launch {
            paired_bags.collect { bags ->
                val selectedBag = bags.firstOrNull { it.bag_id == bag_id.value } ?: bags.firstOrNull()
                if (selectedBag == null) {
                    bag_id.value = ""
                    primary_bag_name_input.value = ""
                    primary_bag_size_input.value = "44"
                    return@collect
                }
                if (selectedBag.bag_id != bag_id.value) {
                    bag_id.value = selectedBag.bag_id
                }
                primary_bag_name_input.value = selectedBag.name
                primary_bag_size_input.value = selectedBag.size_liters.toString()
            }
        }
    }

    val ui_state: StateFlow<InventoryUiState> = combine(
        bag_id,
        paired_bags,
        primary_bag_name_input,
        primary_bag_size_input,
        search,
        category_filter,
        name_input,
        quantity_input,
        unit_input,
        category_input,
        notes_input,
        expiry_input,
        no_expiration,
        editing_item_id,
        feedback_message,
        bag_id.flatMapLatest { item_repository.observe_items(it) }
    ) { args ->
        val currentBagId = args[0] as String
        val bags = args[1] as List<BagProfile>
        val selectedBag = bags.firstOrNull { it.bag_id == currentBagId }
        val searchQ = args[4] as String
        val filter = args[5] as String
        val visibleItems = (args[15] as List<Item>)
            .filter { !it.deleted }
            .filter {
                (searchQ.isBlank() || it.name.contains(searchQ, true) || it.category.contains(searchQ, true)) &&
                    (filter.isBlank() || PreparednessRules.normalize_category(it.category) == filter)
            }
        InventoryUiState(
            bag_id = currentBagId,
            selected_bag_name = selectedBag?.name ?: "",
            bags = bags,
            primary_bag_name_input = args[2] as String,
            primary_bag_size_input = args[3] as String,
            search = searchQ,
            category_filter = filter,
            name_input = args[6] as String,
            quantity_input = args[7] as String,
            unit_input = args[8] as String,
            category_input = args[9] as String,
            notes_input = args[10] as String,
            expiry_input = args[11] as String,
            no_expiration = args[12] as Boolean,
            editing_item_id = args[13] as String?,
            feedback_message = args[14] as String,
            item_groups = build_grouped_items(visibleItems)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InventoryUiState(bag_id = initial_bag_id))

    fun set_primary_bag(value: String) {
        viewModelScope.launch { set_primary_bag_internal(value) }
    }

    private suspend fun set_primary_bag_internal(value: String) {
        val visibleBags = paired_bags.first()
        if (visibleBags.none { it.bag_id == value }) {
            feedback_message.value = "Only QR-paired bags can become the primary bag."
            return
        }
        bag_id.value = value
        sync_repository.set_selected_bag_id(value)
        clear_form()
    }

    fun on_primary_bag_name_changed(value: String) { primary_bag_name_input.value = value }
    fun on_primary_bag_size_changed(value: String) { primary_bag_size_input.value = value }
    fun on_search_changed(value: String) { search.value = value }
    fun on_category_filter_changed(value: String) { category_filter.value = value }
    fun on_name_changed(value: String) { name_input.value = value }
    fun on_qty_changed(value: String) { quantity_input.value = value }
    fun on_unit_changed(value: String) { unit_input.value = value }
    fun on_category_changed(value: String) { category_input.value = value }
    fun on_notes_changed(value: String) { notes_input.value = value }
    fun on_expiry_changed(value: String) { expiry_input.value = value }
    fun on_no_expiration_changed(value: Boolean) { no_expiration.value = value }
    fun consume_feedback() { feedback_message.value = "" }

    fun save_primary_bag() {
        viewModelScope.launch {
            val selectedBagId = bag_id.value
            if (selectedBagId.isBlank()) {
                feedback_message.value = "Pair a bag first, then choose it as primary before editing details."
                return@launch
            }
            val currentBag = item_repository.get_bag(selectedBagId)
            if (currentBag == null) {
                feedback_message.value = "The selected paired bag could not be found."
                return@launch
            }
            val name = primary_bag_name_input.value.trim()
            val liters = primary_bag_size_input.value.toIntOrNull()
            if (name.isBlank()) {
                feedback_message.value = "Bag name is required."
                return@launch
            }
            if (liters !in allowed_bag_sizes) {
                feedback_message.value = "Bag size must be 25L, 44L, or 66L."
                return@launch
            }

            item_repository.upsert_bag(
                currentBag.copy(
                    name = name,
                    size_liters = liters!!,
                    template_id = template_id_for_size(liters),
                    updated_at = nowMs(),
                    updated_by = phone_device_id
                )
            )
            sync_to_pi("Bag updated")
        }
    }

    fun load_item_for_edit(item: Item) {
        if (item.bag_id != bag_id.value) {
            feedback_message.value = "That item does not belong to the current primary bag."
            return
        }
        editing_item_id.value = item.id
        name_input.value = item.name
        quantity_input.value = item.quantity.toString()
        unit_input.value = item.unit
        category_input.value = PreparednessRules.normalize_category(item.category)
        notes_input.value = item.notes
        expiry_input.value = PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(item.expiry_date_ms)
        no_expiration.value = item.expiry_date_ms == null
        feedback_message.value = "Editing a batch from ${primary_bag_name_input.value.ifBlank { "the primary bag" }}."
    }

    fun clear_form() {
        editing_item_id.value = null
        name_input.value = ""
        quantity_input.value = "1"
        unit_input.value = "pcs"
        category_input.value = "Tools & Protection"
        notes_input.value = ""
        expiry_input.value = ""
        no_expiration.value = false
    }

    fun save_item() {
        val name = name_input.value.trim()
        val activeBagId = bag_id.value
        if (name.isBlank() || activeBagId.isBlank()) {
            feedback_message.value = "Invalid form input: item name and a paired primary bag are required."
            return
        }
        val qty = quantity_input.value.toDoubleOrNull()
        if (qty == null || qty <= 0.0) {
            feedback_message.value = "Invalid form input: quantity must be a positive number."
            return
        }
        val normalizedCategory = PreparednessRules.normalize_category(category_input.value)
        if (normalizedCategory !in PreparednessRules.checklist_categories) {
            feedback_message.value = "Invalid form input: category must be selected from the checklist categories."
            return
        }
        if (unit_input.value !in PreparednessRules.unit_options) {
            feedback_message.value = "Invalid form input: unit must be selected from the supported list."
            return
        }
        val expiry = if (no_expiration.value) {
            null
        } else {
            PreparednessRules.parse_yyyy_mm_dd_to_epoch_ms(expiry_input.value).also {
                if (expiry_input.value.isNotBlank() && it == null) {
                    feedback_message.value = "Expiration date invalid. Use YYYY-MM-DD."
                    return
                }
            }
        }
        viewModelScope.launch {
            val activeBag = item_repository.get_bag(activeBagId)
            if (activeBag == null) {
                feedback_message.value = "Select a valid paired bag before saving items."
                return@launch
            }
            val currentId = editing_item_id.value
            val current = currentId?.let { item_repository.get_item(it) }
            if (current != null && current.bag_id != activeBagId) {
                feedback_message.value = "This item is no longer part of the current primary bag."
                clear_form()
                return@launch
            }

            val activeItems = item_repository.observe_items(activeBagId).first().filter { !it.deleted }
            val matchingIdentity = activeItems.filter {
                it.id != currentId && is_same_identity(it, name, unit_input.value, normalizedCategory)
            }
            val matchingBatch = matchingIdentity.firstOrNull { same_expiry(it.expiry_date_ms, expiry) }
            val trimmedNotes = notes_input.value.trim()

            val action = when {
                current != null && matchingBatch != null -> {
                    item_repository.upsert_item(
                        matchingBatch.copy(
                            quantity = matchingBatch.quantity + qty,
                            notes = merged_notes(matchingBatch.notes, trimmedNotes),
                            packed_status = matchingBatch.packed_status || current.packed_status,
                            updated_at = nowMs(),
                            updated_by = phone_device_id
                        )
                    )
                    item_repository.soft_delete_item(current.copy(deleted = true, updated_at = nowMs(), updated_by = phone_device_id))
                    "Item batch merged"
                }
                current != null -> {
                    item_repository.upsert_item(
                        current.copy(
                            bag_id = activeBagId,
                            name = name,
                            category = normalizedCategory,
                            quantity = qty,
                            unit = unit_input.value,
                            notes = trimmedNotes,
                            expiry_date_ms = expiry,
                            updated_at = nowMs(),
                            updated_by = phone_device_id
                        )
                    )
                    "Item updated"
                }
                matchingBatch != null -> {
                    item_repository.upsert_item(
                        matchingBatch.copy(
                            quantity = matchingBatch.quantity + qty,
                            notes = merged_notes(matchingBatch.notes, trimmedNotes),
                            updated_at = nowMs(),
                            updated_by = phone_device_id
                        )
                    )
                    "Item merged into existing batch"
                }
                else -> {
                    item_repository.upsert_item(
                        Item(
                            id = UUID.randomUUID().toString(),
                            bag_id = activeBagId,
                            name = name,
                            category = normalizedCategory,
                            quantity = qty,
                            unit = unit_input.value,
                            packed_status = false,
                            notes = trimmedNotes,
                            expiry_date_ms = expiry,
                            deleted = false,
                            updated_at = nowMs(),
                            updated_by = phone_device_id
                        )
                    )
                    if (matchingIdentity.isNotEmpty()) "Item batch added" else "Item added"
                }
            }
            sync_to_pi(action)
            clear_form()
        }
    }

    fun toggle_packed(item: Item) {
        viewModelScope.launch {
            if (item.bag_id != bag_id.value) {
                feedback_message.value = "Switch back to the matching primary bag before updating this item."
                return@launch
            }
            item_repository.upsert_item(
                item.copy(
                    packed_status = !item.packed_status,
                    updated_at = nowMs(),
                    updated_by = phone_device_id
                )
            )
            sync_to_pi("Packed status updated")
        }
    }

    fun delete_item(item: Item) {
        viewModelScope.launch {
            if (item.bag_id != bag_id.value) {
                feedback_message.value = "Switch back to the matching primary bag before deleting this item."
                return@launch
            }
            item_repository.soft_delete_item(item.copy(deleted = true, updated_at = nowMs(), updated_by = phone_device_id))
            sync_to_pi("Item deleted")
        }
    }

    private suspend fun sync_to_pi(local_action: String) {
        val state = sync_repository.observe_device_state().first()
        if (state.selected_bag_id.isBlank()) {
            feedback_message.value = "$local_action locally. Pair and select a primary bag before syncing."
            return
        }
        if (state.auth_token.isBlank() || state.base_url.isBlank()) {
            feedback_message.value = "$local_action locally. Pair the current primary bag and sync to update the Raspberry Pi."
            return
        }
        try {
            sync_repository.run_sync_now()
            feedback_message.value = "$local_action locally and synced to the Raspberry Pi."
        } catch (e: Exception) {
            feedback_message.value = "$local_action locally, but Raspberry Pi sync failed: ${e.message ?: "unknown sync error"}"
        }
    }

    private fun build_grouped_items(items: List<Item>): List<InventoryItemGroup> {
        return items
            .groupBy { grouped_identity_key(it.name, it.unit, it.category) }
            .values
            .map { group ->
                val first = group.first()
                InventoryItemGroup(
                    bag_id = first.bag_id,
                    name = first.name,
                    category = PreparednessRules.normalize_category(first.category),
                    unit = first.unit,
                    total_quantity = group.sumOf { it.quantity },
                    batches = group.sortedWith(
                        compareBy<Item> { it.expiry_date_ms ?: Long.MAX_VALUE }
                            .thenBy { it.name.lowercase() }
                    )
                )
            }
            .sortedWith(compareBy<InventoryItemGroup> { it.category }.thenBy { it.name.lowercase() })
    }

    private fun grouped_identity_key(name: String, unit: String, category: String): String {
        return listOf(name.trim().lowercase(), unit.trim().lowercase(), PreparednessRules.normalize_category(category)).joinToString("|")
    }

    private fun is_same_identity(item: Item, name: String, unit: String, category: String): Boolean {
        return grouped_identity_key(item.name, item.unit, item.category) == grouped_identity_key(name, unit, category)
    }

    private fun same_expiry(left: Long?, right: Long?): Boolean = left == right

    private fun merged_notes(existing: String, incoming: String): String {
        return when {
            incoming.isBlank() -> existing
            existing.isBlank() -> incoming
            existing.contains(incoming, ignoreCase = true) -> existing
            else -> "$existing | $incoming"
        }
    }

    private fun template_id_for_size(liters: Int): String = when (liters) {
        25 -> "template_25l"
        44 -> "template_44l"
        66 -> "template_66l"
        else -> "template_44l"
    }

    private companion object {
        val allowed_bag_sizes = setOf(25, 44, 66)
    }
}
