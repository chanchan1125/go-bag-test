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
                    title = "Raspberry Pi status",
                    lines = listOf(
                        "Status: ${state.connection_status.replace('_', ' ')}",
                        "Saved endpoint: ${state.endpoint_input.ifBlank { "Not set" }}",
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
                            "Use the Raspberry Pi hotspot IP or local network IP here. Saving an address does not pair this phone. QR pairing is still required for authentication and sync.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.endpoint_input,
                            onValueChange = view_model::on_endpoint_changed,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Saved Pi address") },
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
                                Text("Save address only")
                            }
                            OutlinedButton(
                                onClick = view_model::refresh_status,
                                modifier = Modifier.weight(1f),
                                enabled = !state.running,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(if (state.running) "Checking..." else "Test and refresh")
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
                StatusCard(
                    title = "Primary bag status",
                    lines = if (state.bags.isEmpty()) {
                        listOf("No bags are stored locally yet.")
                    } else {
                        state.bags.map { bag ->
                            val marker = if (bag.bag_id == state.selected_bag_id) "[Primary]" else "[Available]"
                            "$marker ${bag.name} | ${bag.size_liters}L"
                        }
                    }
                )
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
