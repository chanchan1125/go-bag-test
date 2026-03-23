package com.gobag.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gobag.core.model.BagProfile
import com.gobag.core.model.SavedPiAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    view_model: SettingsViewModel,
    on_back: () -> Unit
) {
    val state by view_model.ui_state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback_message) {
        if (state.feedback_message.isNotBlank()) {
            snackbarHost.showSnackbar(state.feedback_message)
            view_model.consume_feedback()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            item {
                StatusCard(
                    title = "Selected primary bag connection",
                    lines = listOf(
                        "Status: ${state.connection_status.replace('_', ' ')}",
                        "Current endpoint: ${state.endpoint_input.ifBlank { "Not set" }}",
                        "Authentication: ${if (state.pi_device_id.isBlank()) "Not paired" else "Paired to ${state.pi_device_id}"}",
                        "Pi local address: ${state.local_ip.ifBlank { "Unknown" }}",
                        "Pending changes on Pi: ${state.pending_changes_count}",
                        "Last sync: ${formatSettingsTime(state.last_sync_at)}",
                        "Pi device id: ${state.pi_device_id.ifBlank { "Not paired" }}"
                    )
                )
            }
            item {
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
                        Text("Connection settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Save Raspberry Pi addresses here. Saving an address does not pair a bag; QR pairing is still required before a bag becomes usable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.endpoint_input,
                            onValueChange = view_model::on_endpoint_changed,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (state.editing_address_id == null) "New Pi address" else "Edit Pi address") },
                            placeholder = { Text("http://192.168.4.1:8080") },
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = view_model::save_endpoint,
                                modifier = Modifier.weight(1f),
                                enabled = !state.running,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(if (state.editing_address_id == null) "Save address" else "Update address")
                            }
                            OutlinedButton(
                                onClick = { view_model.test_endpoint() },
                                modifier = Modifier.weight(1f),
                                enabled = !state.running,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(if (state.running) "Checking..." else "Test entered address")
                            }
                        }
                        if (state.editing_address_id != null) {
                            OutlinedButton(
                                onClick = view_model::cancel_address_edit,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("Cancel address edit")
                            }
                        }
                        OutlinedButton(
                            onClick = view_model::refresh_status,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.running,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Refresh current bag status")
                        }
                    }
                }
            }
            item {
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
                        Text("Saved Raspberry Pi addresses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (state.saved_addresses.isEmpty()) {
                            Text(
                                "No Raspberry Pi addresses saved yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.saved_addresses.forEach { address ->
                                AddressRow(
                                    address = address,
                                    onActivate = { view_model.activate_address(address) },
                                    onEdit = { view_model.start_edit_address(address) },
                                    onTest = { view_model.test_endpoint(address) },
                                    onDelete = { view_model.delete_address(address) }
                                )
                            }
                        }
                    }
                }
            }
            if (state.last_connection_error.isNotBlank()) {
                item {
                    StatusCard(
                        title = "Last connection error",
                        lines = listOf(state.last_connection_error)
                    )
                }
            }
            item {
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
                        Text("Paired bags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (state.bags.isEmpty()) {
                            Text(
                                "No bag is paired on this phone yet. Scan a bag QR from the Raspberry Pi app first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.bags.forEach { bag ->
                                PairedBagRow(
                                    bag = bag,
                                    isPrimary = bag.bag_id == state.selected_bag_id,
                                    onSelect = { view_model.select_bag(bag.bag_id) }
                                )
                            }
                            OutlinedButton(
                                onClick = view_model::unpair_selected_bag,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("Remove selected bag from phone", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: SavedPiAddress,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                buildString {
                    append(address.base_url)
                    if (address.is_active) append(" [Active]")
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${address.last_status} | ${address.last_detail.ifBlank { "No test run yet." }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Last checked: ${formatSettingsTime(address.last_checked_at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onActivate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text(if (address.is_active) "Active" else "Make active")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Edit")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Test")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PairedBagRow(
    bag: BagProfile,
    isPrimary: Boolean,
    onSelect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                buildString {
                    append(bag.name)
                    if (isPrimary) append(" [Primary]")
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${bag.size_liters}L",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (isPrimary) "Primary bag selected" else "Set as primary bag")
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    lines: List<String>
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatSettingsTime(value: Long): String {
    if (value == 0L) return "Never"
    return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(value))
}
