package com.gobag.feature.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gobag.core.model.BagProfile
import com.gobag.core.model.Item
import com.gobag.domain.logic.PreparednessRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(view_model: InventoryViewModel, on_back: () -> Unit) {
    val state by view_model.ui_state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Item?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    LaunchedEffect(state.feedback_message) {
        if (state.feedback_message.isNotBlank()) {
            snackbarHost.showSnackbar(state.feedback_message)
            view_model.consume_feedback()
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete item") },
            text = { Text("Delete '${pendingDelete!!.name}' from this bag?") },
            confirmButton = {
                TextButton(onClick = {
                    view_model.delete_item(pendingDelete!!)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = datePickerState.selectedDateMillis
                    if (selected != null) {
                        view_model.on_expiry_changed(PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(selected))
                    }
                    showDatePicker = false
                }) { Text("Use date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Inventory", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = view_model::save_item,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Save item")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (state.bag_id.isBlank()) "Create or sync a primary bag to get started." else "Personal Go Bag Inventory",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            if (state.bag_id.isBlank()) {
                                "This screen stays local-first and can sync back to the Raspberry Pi whenever the phone is paired."
                            } else {
                                "${state.items.size} visible item(s) in ${state.selected_bag_name}. Changes save here first, then sync to the Raspberry Pi."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    InventoryBagCard(
                        state = state,
                        onSelectPrimaryBag = view_model::set_primary_bag,
                        onPrimaryBagNameChange = view_model::on_primary_bag_name_changed,
                        onPrimaryBagSizeChange = view_model::on_primary_bag_size_changed,
                        onSavePrimaryBag = view_model::save_primary_bag,
                        onCreateBag = view_model::create_bag,
                        onCreateBagNameChange = view_model::on_new_bag_name_changed,
                        onCreateBagSizeChange = view_model::on_new_bag_size_changed
                    )
                }
                item {
                    InventoryFormCard(
                        state = state,
                        onNameChange = view_model::on_name_changed,
                        onQtyChange = view_model::on_qty_changed,
                        onUnitChange = view_model::on_unit_changed,
                        onCategoryChange = view_model::on_category_changed,
                        onExpiryChange = view_model::on_expiry_changed,
                        onNoExpirationChange = view_model::on_no_expiration_changed,
                        onNotesChange = view_model::on_notes_changed,
                        onClear = view_model::clear_form,
                        onPickDate = { showDatePicker = true },
                        onSave = view_model::save_item
                    )
                }
                item {
                    InventoryFilterCard(
                        state = state,
                        onSearchChange = view_model::on_search_changed,
                        onCategoryFilterChange = view_model::on_category_filter_changed
                    )
                }
                if (state.items.isEmpty()) {
                    item {
                        EmptyInventoryCard(noBagSelected = state.bag_id.isBlank())
                    }
                } else {
                    items(state.items) { item ->
                        InventoryItemRow(
                            item = item,
                            onToggle = { view_model.toggle_packed(item) },
                            onDelete = { pendingDelete = item },
                            onEdit = { view_model.load_item_for_edit(item) }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(88.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryBagCard(
    state: InventoryUiState,
    onSelectPrimaryBag: (String) -> Unit,
    onPrimaryBagNameChange: (String) -> Unit,
    onPrimaryBagSizeChange: (String) -> Unit,
    onSavePrimaryBag: () -> Unit,
    onCreateBag: () -> Unit,
    onCreateBagNameChange: (String) -> Unit,
    onCreateBagSizeChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Bag setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (state.bag_id.isBlank()) {
                    "Choose or create the bag that should act as the phone's current primary bag."
                } else {
                    "Inventory below shows items from the current primary bag only."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.bags.isNotEmpty()) {
                BagDropdown(
                    label = "Primary bag",
                    selected_bag_id = state.bag_id,
                    bags = state.bags,
                    onSelected = onSelectPrimaryBag
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = state.primary_bag_name_input,
                        onValueChange = onPrimaryBagNameChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Bag name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.primary_bag_size_input,
                        onValueChange = onPrimaryBagSizeChange,
                        modifier = Modifier.weight(0.45f),
                        label = { Text("Liters") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Button(
                    onClick = onSavePrimaryBag,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Save primary bag changes")
                }
            }

            Text(
                if (state.bags.isEmpty()) "Create your first bag" else "Add another bag",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.new_bag_name,
                    onValueChange = onCreateBagNameChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("New bag name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.new_bag_size_input,
                    onValueChange = onCreateBagSizeChange,
                    modifier = Modifier.weight(0.45f),
                    label = { Text("Liters") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            Button(
                onClick = onCreateBag,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Create bag and set as primary")
            }
        }
    }
}

@Composable
private fun InventoryFormCard(
    state: InventoryUiState,
    onNameChange: (String) -> Unit,
    onQtyChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onExpiryChange: (String) -> Unit,
    onNoExpirationChange: (Boolean) -> Unit,
    onNotesChange: (String) -> Unit,
    onClear: () -> Unit,
    onPickDate: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (state.editing_item_id == null) "Add supply item" else "Edit supply item",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.name_input,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Item name") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.quantity_input,
                    onValueChange = onQtyChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                UnitDropdown(
                    selected = state.unit_input,
                    options = state.unit_options,
                    onSelected = onUnitChange,
                    modifier = Modifier.weight(1f)
                )
            }

            CategoryDropdown(
                label = "Category",
                selected = state.category_input,
                options = state.category_options,
                onSelected = onCategoryChange
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.expiry_input,
                    onValueChange = onExpiryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.no_expiration,
                    label = { Text("Expiration date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = onPickDate,
                    enabled = !state.no_expiration,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Pick date")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = state.no_expiration, onCheckedChange = onNoExpirationChange)
                Text("No expiration date", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = state.notes_input,
                onValueChange = onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                minLines = 2
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = state.bag_id.isNotBlank(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (state.editing_item_id == null) "Save item" else "Update item")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Clear form")
                }
            }
        }
    }
}

@Composable
private fun InventoryFilterCard(
    state: InventoryUiState,
    onSearchChange: (String) -> Unit,
    onCategoryFilterChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search supplies") },
                singleLine = true
            )
            CategoryDropdown(
                label = "Filter category",
                selected = state.category_filter,
                options = listOf("") + state.category_options,
                onSelected = onCategoryFilterChange
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(onClick = { onCategoryFilterChange("") }, label = { Text("All") })
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${state.items.size} item(s) shown",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BagDropdown(
    label: String,
    selected_bag_id: String,
    bags: List<BagProfile>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedBag = bags.firstOrNull { it.bag_id == selected_bag_id }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedBag?.let(::bagLabel) ?: "No bag selected",
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bags.forEach { bag ->
                DropdownMenuItem(
                    text = { Text(bagLabel(bag)) },
                    onClick = {
                        onSelected(bag.bag_id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(label: String, selected: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected.ifBlank { "All" },
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { "All" }) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(selected: String, options: List<String>, onSelected: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun InventoryItemRow(item: Item, onToggle: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    val normalizedCategory = PreparednessRules.normalize_category(item.category)
    val expiryState = PreparednessRules.expiration_state(item)
    val statusLabel = when {
        !item.packed_status -> "Needs pack"
        else -> "Packed"
    }
    val statusColor = when {
        !item.packed_status -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val expiryLabel = when (expiryState) {
        "EXPIRED" -> "Expired"
        "NEAR_EXPIRY" -> "Expiring soon"
        else -> null
    }
    val expiryColor = when (expiryState) {
        "EXPIRED" -> MaterialTheme.colorScheme.error
        "NEAR_EXPIRY" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.packed_status) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        categoryToken(normalizedCategory),
                        color = statusColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusBadge(text = statusLabel, color = statusColor)
                            if (expiryLabel != null) {
                                StatusBadge(text = expiryLabel, color = expiryColor)
                            }
                        }
                    }
                    Text(normalizedCategory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${item.quantity} ${item.unit} | Expiry: ${PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(item.expiry_date_ms).ifBlank { "No expiration" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.notes.isNotBlank()) {
                Text(item.notes, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onToggle, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                    Text(if (item.packed_status) "Mark unpacked" else "Mark packed")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                    Text("Edit")
                }
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyInventoryCard(noBagSelected: Boolean) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (noBagSelected) "No primary bag available" else "No items yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (noBagSelected) {
                    "Create a primary bag above or sync one from the Raspberry Pi."
                } else {
                    "Add the first supply item above, then sync the inventory back to the Raspberry Pi."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun bagLabel(bag: BagProfile): String = "${bag.name} | ${bag.size_liters}L"

private fun categoryToken(category: String): String = when (category) {
    "Water & Food" -> "WF"
    "Medical & Health" -> "MH"
    "Light & Communication" -> "LC"
    "Tools & Protection" -> "TP"
    "Hygiene" -> "HY"
    else -> "OT"
}
