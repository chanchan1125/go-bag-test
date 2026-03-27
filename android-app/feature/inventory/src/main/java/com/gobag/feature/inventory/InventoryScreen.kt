package com.gobag.feature.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gobag.core.model.BagProfile
import com.gobag.core.model.Item
import com.gobag.domain.logic.PreparednessRules

private val InventoryPanel = Color(0xFF1B1B1C)
private val InventoryPanelHigh = Color(0xFF232325)
private val InventoryPanelHighest = Color(0xFF2D2D2F)
private val InventoryOutline = Color(0xFF5A4136)
private val InventoryPrimary = Color(0xFFFF6B00)
private val InventoryPrimarySoft = Color(0xFFFFB693)
private val InventorySecondary = Color(0xFF8CCDFF)
private val InventorySuccess = Color(0xFF78DC77)
private val InventorySuccessDeep = Color(0xFF0A3410)
private val InventoryCritical = Color(0xFF93000A)
private val InventoryCriticalSoft = Color(0xFFFFDAD6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(view_model: InventoryViewModel, on_back: () -> Unit) {
    val state by view_model.ui_state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Item?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    val totalBatches = state.item_groups.sumOf { it.batches.size }
    val packedBatches = state.item_groups.sumOf { group -> group.batches.count { it.packed_status } }
    val progress = if (totalBatches == 0) 0f else packedBatches.toFloat() / totalBatches.toFloat()
    val groupedSections = state.item_groups.groupBy { it.category }
    val hasActiveFilters = state.search.isNotBlank() || state.category_filter.isNotBlank()

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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
        topBar = {
            InventoryTopBar(
                bagName = state.selected_bag_name,
                groupedCount = state.item_groups.size,
                onBack = on_back
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = view_model::save_item,
                containerColor = InventoryPrimary,
                contentColor = Color(0xFF351000)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (state.editing_item_id == null) "Save item" else "Update item"
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                InventoryHeroCard(
                    bagName = state.selected_bag_name,
                    groupedCount = state.item_groups.size,
                    packedBatches = packedBatches,
                    totalBatches = totalBatches,
                    progress = progress,
                    noBagSelected = state.bag_id.isBlank()
                )
            }

            item {
                InventoryBagCard(
                    state = state,
                    onSelectPrimaryBag = view_model::set_primary_bag,
                    onPrimaryBagNameChange = view_model::on_primary_bag_name_changed,
                    onPrimaryBagSizeChange = view_model::on_primary_bag_size_changed,
                    onSavePrimaryBag = view_model::save_primary_bag
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

            if (state.item_groups.isEmpty()) {
                item {
                    EmptyInventoryCard(
                        noBagSelected = state.bag_id.isBlank(),
                        hasFilters = hasActiveFilters
                    )
                }
            } else {
                groupedSections.forEach { (category, groups) ->
                    item {
                        CategorySectionHeader(
                            category = category,
                            groupCount = groups.size,
                            batchCount = groups.sumOf { it.batches.size }
                        )
                    }
                    items(
                        items = groups,
                        key = { "${it.category}|${it.name}|${it.unit}" }
                    ) { group ->
                        InventoryGroupCard(
                            group = group,
                            onToggle = view_model::toggle_packed,
                            onDelete = { pendingDelete = it },
                            onEdit = view_model::load_item_for_edit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryTopBar(
    bagName: String,
    groupedCount: Int,
    onBack: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "TACTICAL READY",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = InventoryPrimarySoft
                    )
                    Text(
                        if (bagName.isBlank()) "INVENTORY" else bagName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusPill(
                text = if (groupedCount == 0) "STAGING" else "$groupedCount GROUPED",
                accent = InventoryPrimarySoft
            )
        }
    }
}

@Composable
private fun InventoryHeroCard(
    bagName: String,
    groupedCount: Int,
    packedBatches: Int,
    totalBatches: Int,
    progress: Float,
    noBagSelected: Boolean
) {
    val readyPercent = if (totalBatches == 0) 0 else (progress * 100f).toInt()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            InventoryPrimary.copy(alpha = 0.24f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionLabel("Primary Loadout")
                Text(
                    if (noBagSelected) "NO PRIMARY BAG" else bagName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (noBagSelected) {
                        "Pair a Raspberry Pi bag and select it as primary before editing local inventory."
                    } else {
                        "Inventory edits stay on this phone first, then sync back to the Raspberry Pi when the link is available."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "$packedBatches/$totalBatches",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = InventorySuccess
                        )
                        Text(
                            if (totalBatches == 0) "BATCHES READY TO STAGE" else "BATCHES MARKED PACKED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = InventorySuccess
                        )
                    }
                    HeroMetricStack(
                        primary = groupedCount.toString(),
                        secondary = "$readyPercent% READY"
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = InventoryPrimary,
                    trackColor = InventoryPanelHighest
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TacticalStatChip(
                        modifier = Modifier.weight(1f),
                        label = "Grouped",
                        value = groupedCount.toString(),
                        accent = InventoryPrimarySoft
                    )
                    TacticalStatChip(
                        modifier = Modifier.weight(1f),
                        label = "Packed",
                        value = packedBatches.toString(),
                        accent = InventorySuccess
                    )
                    TacticalStatChip(
                        modifier = Modifier.weight(1f),
                        label = "Batches",
                        value = totalBatches.toString(),
                        accent = InventorySecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMetricStack(
    primary: String,
    secondary: String
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            primary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            secondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = InventoryPrimarySoft
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryBagCard(
    state: InventoryUiState,
    onSelectPrimaryBag: (String) -> Unit,
    onPrimaryBagNameChange: (String) -> Unit,
    onPrimaryBagSizeChange: (String) -> Unit,
    onSavePrimaryBag: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLabel("Loadout Profile")
            Text(
                "Primary Bag Setup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (state.bag_id.isBlank()) {
                    "Pair a physical GO BAG by QR before it becomes editable here."
                } else {
                    "These details define the current primary bag shown across inventory, check mode, and sync."
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
                    SimpleOptionDropdown(
                        label = "Volume",
                        selected = "${state.primary_bag_size_input}L",
                        options = state.bag_size_options.map { "${it}L" },
                        modifier = Modifier.weight(0.56f),
                        onSelected = { onPrimaryBagSizeChange(it.removeSuffix("L")) }
                    )
                }
                Button(
                    onClick = onSavePrimaryBag,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InventoryPrimary,
                        contentColor = Color(0xFF351000)
                    )
                ) {
                    Text("Save primary bag changes", fontWeight = FontWeight.Bold)
                }
            } else {
                EmptyMessageCard(
                    title = "No paired bags available",
                    body = "Open Pairing and scan a bag QR from the Raspberry Pi hub before editing bag details."
                )
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("Field Editor")
                    Text(
                        if (state.editing_item_id == null) "Add Supply Batch" else "Edit Supply Batch",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                StatusPill(
                    text = if (state.editing_item_id == null) "NEW" else "EDITING",
                    accent = if (state.editing_item_id == null) InventoryPrimarySoft else InventorySecondary
                )
            }

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

            OutlinedTextField(
                value = state.expiry_input,
                onValueChange = onExpiryChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.no_expiration,
                label = { Text("Expiration date") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickDate,
                    enabled = !state.no_expiration,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Pick date")
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = InventoryPanel,
                    border = BorderStroke(1.dp, InventoryOutline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = state.no_expiration,
                            onCheckedChange = onNoExpirationChange
                        )
                        Text(
                            "No expiration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.notes_input,
                onValueChange = onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                minLines = 2
            )

            if (state.bag_id.isBlank()) {
                EmptyMessageCard(
                    title = "No primary bag selected",
                    body = "Choose a paired primary bag above before saving or updating local inventory batches."
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = state.bag_id.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InventoryPrimary,
                        contentColor = Color(0xFF351000)
                    )
                ) {
                    Text(
                        if (state.editing_item_id == null) "Save batch" else "Update batch",
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, InventoryOutline)
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
    val chipScroll = rememberScrollState()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLabel("Search & Filter")
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search inventory") },
                placeholder = { Text("SEARCH INVENTORY...") },
                singleLine = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterPill(
                    text = "All",
                    selected = state.category_filter.isBlank(),
                    onClick = { onCategoryFilterChange("") }
                )
                state.category_options.forEach { option ->
                    FilterPill(
                        text = option,
                        selected = state.category_filter == option,
                        onClick = { onCategoryFilterChange(option) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${state.item_groups.size} grouped item(s) shown",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.search.isNotBlank()) {
                    StatusPill(
                        text = "SEARCH ACTIVE",
                        accent = InventorySecondary
                    )
                }
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
private fun CategoryDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
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
private fun UnitDropdown(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier
) {
    SimpleOptionDropdown(
        label = "Unit",
        selected = selected,
        options = options,
        modifier = modifier,
        onSelected = onSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleOptionDropdown(
    label: String,
    selected: String,
    options: List<String>,
    modifier: Modifier,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
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
private fun CategorySectionHeader(
    category: String,
    groupCount: Int,
    batchCount: Int
) {
    val accent = categoryAccent(category)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accent)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        category.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = accent
                    )
                    Text(
                        "$groupCount grouped item(s) | $batchCount batch(es)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(
                text = "$batchCount BATCHES",
                accent = accent
            )
        }
    }
}

@Composable
private fun InventoryGroupCard(
    group: InventoryItemGroup,
    onToggle: (Item) -> Unit,
    onDelete: (Item) -> Unit,
    onEdit: (Item) -> Unit
) {
    val normalizedCategory = PreparednessRules.normalize_category(group.category)
    val allPacked = group.batches.all { it.packed_status }
    val hasUnpacked = group.batches.any { !it.packed_status }
    val expiryState = group.batches
        .map { PreparednessRules.expiration_state(it) }
        .firstOrNull { it == "EXPIRED" || it == "NEAR_EXPIRY" }
    val statusLabel = if (hasUnpacked) "NEEDS PACK" else "PACKED"
    val statusColor = if (hasUnpacked) InventoryPrimarySoft else InventorySuccess
    val expiryLabel = when (expiryState) {
        "EXPIRED" -> "EXPIRED"
        "NEAR_EXPIRY" -> "EXPIRING SOON"
        else -> null
    }
    val expiryColor = when (expiryState) {
        "EXPIRED" -> InventoryCriticalSoft
        "NEAR_EXPIRY" -> InventoryPrimarySoft
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allPacked) InventoryPanelHigh else InventoryPanel
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(categoryAccent(normalizedCategory).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        categoryToken(normalizedCategory),
                        color = categoryAccent(normalizedCategory),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        normalizedCategory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(text = statusLabel, accent = statusColor)
                StatusPill(
                    text = "TOTAL ${formatQuantity(group.total_quantity)} ${group.unit.uppercase()}",
                    accent = InventorySecondary
                )
                if (expiryLabel != null) {
                    StatusPill(text = expiryLabel, accent = expiryColor)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.batches.forEach { batch ->
                    InventoryBatchCard(
                        batch = batch,
                        onToggle = { onToggle(batch) },
                        onEdit = { onEdit(batch) },
                        onDelete = { onDelete(batch) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InventoryBatchCard(
    batch: Item,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val packedAccent = if (batch.packed_status) InventorySuccess else InventoryPrimarySoft
    val expiryState = PreparednessRules.expiration_state(batch)
    val expiryAccent = when (expiryState) {
        "EXPIRED" -> InventoryCriticalSoft
        "NEAR_EXPIRY" -> InventoryPrimarySoft
        else -> InventorySecondary
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHighest),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${formatQuantity(batch.quantity)} ${batch.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        formatBatchExpiry(batch.expiry_date_ms),
                        style = MaterialTheme.typography.bodySmall,
                        color = expiryAccent
                    )
                }
                StatusPill(
                    text = if (batch.packed_status) "PACKED" else "UNPACKED",
                    accent = packedAccent
                )
            }

            if (batch.notes.isNotBlank()) {
                Text(
                    batch.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (batch.packed_status) InventoryPanel else InventoryPrimary,
                        contentColor = if (batch.packed_status) MaterialTheme.colorScheme.onSurface else Color(0xFF351000)
                    )
                ) {
                    Text(if (batch.packed_status) "Mark unpacked" else "Mark packed")
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, InventoryOutline)
                ) {
                    Text("Edit batch")
                }
            }

            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, InventoryCritical.copy(alpha = 0.6f))
            ) {
                Text("Delete batch", color = InventoryCriticalSoft)
            }
        }
    }
}

@Composable
private fun TacticalStatChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanelHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) InventoryPrimary else InventoryPanelHighest,
        border = BorderStroke(1.dp, if (selected) InventoryPrimary else InventoryOutline)
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFF351000) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyMessageCard(
    title: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InventoryPanel),
        border = BorderStroke(1.dp, InventoryOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyInventoryCard(
    noBagSelected: Boolean,
    hasFilters: Boolean
) {
    val title = when {
        noBagSelected -> "No primary bag available"
        hasFilters -> "No inventory matches"
        else -> "No items yet"
    }
    val body = when {
        noBagSelected -> "Create or sync a primary bag above before tracking offline inventory."
        hasFilters -> "Clear the current search or category filter to see the rest of this bag's grouped inventory."
        else -> "Add the first supply batch above, then sync the inventory back to the Raspberry Pi when ready."
    }
    EmptyMessageCard(title = title, body = body)
}

private fun bagLabel(bag: BagProfile): String = bag.name

private fun categoryToken(category: String): String = when (category) {
    "Water & Food" -> "WF"
    "Medical & Health" -> "MH"
    "Light & Communication" -> "LC"
    "Tools & Protection" -> "TP"
    "Hygiene" -> "HY"
    else -> "OT"
}

private fun categoryAccent(category: String): Color = when (category) {
    "Water & Food" -> InventorySecondary
    "Medical & Health" -> InventoryCriticalSoft
    "Light & Communication" -> InventorySuccess
    "Tools & Protection" -> InventoryPrimarySoft
    "Hygiene" -> InventorySuccess
    else -> InventoryPrimarySoft
}

private fun formatBatchExpiry(expiryDateMs: Long?): String {
    return PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(expiryDateMs).ifBlank { "No expiration" }
}

private fun formatQuantity(quantity: Double): String {
    return if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
}
