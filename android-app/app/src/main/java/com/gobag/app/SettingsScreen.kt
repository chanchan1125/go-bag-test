package com.gobag.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gobag.core.model.BagProfile
import com.gobag.core.model.SavedPiAddress
import com.gobag.domain.logic.PiConnectionSnapshot
import com.gobag.domain.logic.PiConnectionStatus
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
    val connection = state.connection
    val snackbarHost = remember { SnackbarHostState() }
    val selectedBag = state.bags.firstOrNull { it.bag_id == state.selected_bag_id }

    LaunchedEffect(state.feedback_message) {
        if (state.feedback_message.isNotBlank()) {
            snackbarHost.showSnackbar(state.feedback_message)
            view_model.consume_feedback()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "APP SETTINGS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "SETTINGS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                actions = {
                    StatusPill(
                        label = connection.primary_label,
                        accent = resolveConnectionAccent(connection)
                    )
                },
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
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsHeroCard(
                    selectedBagName = selectedBag?.name ?: "No bag selected",
                    statusLabel = connection.connection_label,
                    statusDetail = connection.detail,
                    pendingChanges = connection.pending_changes_count,
                    lastSync = formatSettingsTime(connection.last_sync_at),
                    savedLocationCount = state.saved_addresses.size,
                    bagCount = state.bags.size
                )
            }

            item {
                SettingsSectionCard(
                    title = "Appearance",
                    subtitle = "Choose how the app looks."
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ThemeModeCard(
                            modifier = Modifier.weight(1f),
                            label = "Light",
                            detail = "Bright look",
                            icon = Icons.Default.LightMode,
                            selected = !state.dark_theme_enabled,
                            onClick = { view_model.set_dark_theme(false) }
                        )
                        ThemeModeCard(
                            modifier = Modifier.weight(1f),
                            label = "Dark",
                            detail = "Dim look",
                            icon = Icons.Default.DarkMode,
                            selected = state.dark_theme_enabled,
                            onClick = { view_model.set_dark_theme(true) }
                        )
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Bag connection",
                    subtitle = "Save the bag location here, then use Connect Bag to finish setup."
                ) {
                    OutlinedTextField(
                        value = state.endpoint_input,
                        onValueChange = view_model::on_endpoint_changed,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Location") },
                        placeholder = { Text("http://192.168.4.1:8080") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = view_model::save_endpoint,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                            enabled = !state.running,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                if (state.editing_address_id == null) "Save location" else "Update location",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = { view_model.test_endpoint() },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                            enabled = !state.running,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (state.running) "Checking..." else "Check location", maxLines = 1)
                        }
                    }
                    if (state.editing_address_id != null) {
                        OutlinedButton(
                            onClick = view_model::cancel_address_edit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Cancel edit")
                        }
                    }
                    OutlinedButton(
                        onClick = view_model::refresh_status,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp),
                        enabled = !state.running,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Text("Refresh status")
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Saved locations",
                    subtitle = "Choose which saved location to use, check it again, or remove it."
                ) {
                    if (state.saved_addresses.isEmpty()) {
                        EmptyStateCard(
                            title = "No saved locations yet",
                            body = "Add a bag location here or open Connect Bag."
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

            if (connection.last_sync_error.isNotBlank() || connection.last_connection_error.isNotBlank()) {
                item {
                    ErrorCard(message = connection.last_sync_error.ifBlank { connection.last_connection_error })
                }
            }

            item {
                SettingsSectionCard(
                    title = "Bags on this phone",
                    subtitle = "Choose which bag this phone uses or remove one from this phone."
                ) {
                    if (state.bags.isEmpty()) {
                        EmptyStateCard(
                            title = "No bag saved on this phone yet",
                            body = "Open Connect Bag first."
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Text(
                                "Remove this bag from phone",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeroCard(
    selectedBagName: String,
    statusLabel: String,
    statusDetail: String,
    pendingChanges: Int,
    lastSync: String,
    savedLocationCount: Int,
    bagCount: Int
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This phone",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        selectedBagName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.SettingsEthernet,
                        label = "Status",
                        value = statusLabel,
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Router,
                        label = "Saved locations",
                        value = savedLocationCount.toString(),
                        accent = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Sync,
                        label = "Changes waiting",
                        value = pendingChanges.toString(),
                        accent = MaterialTheme.colorScheme.primary
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Palette,
                        label = "Last update",
                        value = lastSync,
                        accent = MaterialTheme.colorScheme.secondary
                    )
                }
                HeroMetric(
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Router,
                    label = "Bags on this phone",
                    value = bagCount.toString(),
                    accent = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ThemeModeCard(
    modifier: Modifier = Modifier,
    label: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 118.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        address.base_url,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        address.last_detail.ifBlank { "Not checked yet." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (address.is_active) {
                    StatusPill(
                        label = "Current",
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                "${PiConnectionStatus.saved_location_label(address.last_status)} - Checked ${formatSettingsTime(address.last_checked_at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onActivate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text(if (address.is_active) "In use" else "Use this")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text("Edit")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text("Check")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        bag.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${bag.size_liters}L bag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isPrimary) {
                    StatusPill(
                        label = "Current",
                        accent = MaterialTheme.colorScheme.primary
                    )
                }
            }
            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isPrimary) "This bag is in use" else "Use this bag")
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
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
private fun ErrorCard(message: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.32f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    accent: Color
) {
    Surface(
        modifier = Modifier.widthIn(max = 132.dp),
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun resolveConnectionAccent(connection: PiConnectionSnapshot): Color {
    return when {
        connection.last_sync_error.isNotBlank() || connection.last_connection_error.isNotBlank() -> Color(0xFFB3261E)
        connection.is_offline || connection.address_needs_attention -> Color(0xFFB3261E)
        connection.is_online -> Color(0xFF2ECC71)
        connection.is_paired -> Color(0xFFFF6B00)
        else -> Color(0xFFFFB693)
    }
}

private fun formatSettingsTime(value: Long): String {
    if (value == 0L) return "Never"
    return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(value))
}
