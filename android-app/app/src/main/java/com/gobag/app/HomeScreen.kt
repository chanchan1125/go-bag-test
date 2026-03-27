package com.gobag.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gobag.core.model.AlertModel
import com.gobag.domain.logic.PreparednessRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    view_model: HomeViewModel,
    on_inventory: () -> Unit,
    on_check_mode: () -> Unit,
    on_sync: () -> Unit,
    on_pairing: () -> Unit,
    on_settings: () -> Unit
) {
    val state by view_model.ui_state.collectAsState()
    val readinessPercent = if (state.checklist_total == 0) 0 else {
        ((state.checklist_covered.toFloat() / state.checklist_total.toFloat()) * 100f).roundToInt()
    }
    val readinessAccent = resolveReadinessAccent(readinessPercent)
    val connectionLabel = state.connection_status
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        .ifBlank { "Unknown" }
    val paired = state.device_status == "Paired"
    val primaryAlert = when {
        state.last_connection_error.isNotBlank() -> state.last_connection_error
        state.has_conflicts -> "Sync conflicts need review before auto-sync can be trusted."
        state.expiry_alerts.isNotEmpty() -> formatExpiryAlertDetail(state.expiry_alerts.first())
        state.alerts.isNotEmpty() -> state.alerts.first()
        state.sync_recommended -> "Phone changes are waiting to be synced to the Raspberry Pi."
        else -> "No critical alerts. The primary bag is stable."
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "TACTICAL READY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "GO BAG DASHBOARD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                navigationIcon = {
                    Image(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .size(34.dp),
                        painter = painterResource(id = R.drawable.gobag_icon),
                        contentDescription = "GO BAG icon"
                    )
                },
                actions = {
                    TacticalTopPill(
                        text = if (paired) "PI LINKED" else "PI OFFLINE",
                        accent = if (paired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = on_pairing) {
                        Icon(
                            Icons.Default.SettingsRemote,
                            contentDescription = "Connect Raspberry Pi",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = on_settings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = on_sync,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Sync, contentDescription = "Sync now")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HomeHeroCard(
                    bagName = state.selected_bag_name,
                    readinessPercent = readinessPercent,
                    readinessAccent = readinessAccent,
                    bagReadiness = state.bag_readiness,
                    connectionLabel = connectionLabel,
                    pendingChanges = state.pending_changes_count,
                    localIp = state.local_ip,
                    lastSync = formatTimestamp(state.last_sync_time)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        value = state.bag_count.toString(),
                        label = "Paired Bags",
                        accent = MaterialTheme.colorScheme.primary
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        value = "${state.checklist_covered}/${state.checklist_total}",
                        label = "Coverage",
                        accent = readinessAccent
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        value = state.expired_count.toString(),
                        label = "Expired",
                        accent = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusCard(
                        modifier = Modifier.weight(1f),
                        title = "Connection Node",
                        value = if (paired) "Raspberry Pi Linked" else "Pairing Required",
                        detail = if (paired) {
                            "$connectionLabel • ${state.pending_changes_count} pending"
                        } else {
                            "Save a Pi address, then scan the bag QR code to authenticate."
                        },
                        icon = if (paired) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        accent = if (paired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                    StatusCard(
                        modifier = Modifier.weight(1f),
                        title = "Primary Alert",
                        value = when {
                            state.has_conflicts -> "Conflict Review"
                            state.expiry_alerts.isNotEmpty() -> "Expiry Watch"
                            state.sync_recommended -> "Sync Needed"
                            else -> "Stable"
                        },
                        detail = primaryAlert,
                        icon = Icons.Default.Warning,
                        accent = when {
                            state.has_conflicts -> MaterialTheme.colorScheme.primary
                            state.expiry_alerts.isNotEmpty() -> MaterialTheme.colorScheme.error
                            else -> readinessAccent
                        }
                    )
                }
            }

            item {
                AlertSummaryCard(
                    message = primaryAlert,
                    missingCount = (state.checklist_total - state.checklist_covered).coerceAtLeast(0),
                    expiringCount = state.near_expiry_count,
                    conflictCount = if (state.has_conflicts) 1 else 0
                )
            }

            if (state.expiry_alerts.isNotEmpty()) {
                item {
                    ExpiryWatchCard(
                        bagName = state.selected_bag_name,
                        alerts = state.expiry_alerts
                    )
                }
            }

            item {
                SectionLabel("Quick Actions")
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        title = "Inventory",
                        detail = "Review grouped supplies and batches",
                        icon = Icons.Default.Inventory,
                        onClick = on_inventory
                    )
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        title = "Check Mode",
                        detail = "Run the departure packed review",
                        icon = Icons.AutoMirrored.Filled.FactCheck,
                        onClick = on_check_mode
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        title = "Connect Pi",
                        detail = if (paired) "Manage pairing and bag authentication" else "Link this phone to your Raspberry Pi hub",
                        icon = Icons.Default.QrCodeScanner,
                        onClick = on_pairing
                    )
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        title = "Sync Now",
                        detail = "Push local changes to the Pi hub",
                        icon = Icons.Default.Sync,
                        onClick = on_sync
                    )
                }
            }

            item {
                ActionTile(
                    modifier = Modifier.fillMaxWidth(),
                    title = "Settings",
                    detail = "Addresses, paired bags, connection tools, and appearance",
                    icon = Icons.Default.Settings,
                    onClick = on_settings
                )
            }

            item {
                SystemHubCard(
                    paired = paired,
                    detail = if (paired) {
                        "This phone is ready to exchange data with the Raspberry Pi over the saved local endpoint."
                    } else {
                        "Scan the Raspberry Pi QR code to save the local endpoint, token, and template data on this phone."
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeHeroCard(
    bagName: String,
    readinessPercent: Int,
    readinessAccent: Color,
    bagReadiness: String,
    connectionLabel: String,
    pendingChanges: Int,
    localIp: String,
    lastSync: String
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            readinessAccent.copy(alpha = 0.20f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SectionLabel("Primary Bag")
                        Text(
                            bagName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Offline-first readiness overview with local Raspberry Pi sync.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TacticalTopPill(
                        text = bagReadiness.uppercase(),
                        accent = readinessAccent
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReadinessDial(
                        percent = readinessPercent,
                        accent = readinessAccent
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HeroDetailLine("Connection", connectionLabel)
                        HeroDetailLine("Pending changes", pendingChanges.toString())
                        HeroDetailLine("Last sync", lastSync)
                        HeroDetailLine("Local endpoint", localIp.ifBlank { "Unknown" })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessDial(
    percent: Int,
    accent: Color
) {
    Box(
        modifier = Modifier
            .size(122.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "READY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun HeroDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AlertSummaryCard(
    message: String,
    missingCount: Int,
    expiringCount: Int,
    conflictCount: Int
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.34f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "Critical Needs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    value = missingCount.toString(),
                    label = "Missing",
                    accent = MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    value = expiringCount.toString(),
                    label = "Expiring",
                    accent = MaterialTheme.colorScheme.error
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    value = conflictCount.toString(),
                    label = "Conflicts",
                    accent = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun ExpiryWatchCard(
    bagName: String,
    alerts: List<AlertModel>
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Expiry Watch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Alerts for $bagName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            alerts.take(3).forEach { alert ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            alert.item_name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            formatExpiryAlertDetail(alert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (alerts.size > 3) {
                Text(
                    "+${alerts.size - 3} more item(s) need attention",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SystemHubCard(
    paired: Boolean,
    detail: String
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("System Hub")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (paired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                )
                Text(
                    if (paired) "System hub linked and reachable" else "System hub awaiting pairing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TacticalTopPill(
    text: String,
    accent: Color
) {
    Surface(
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
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

private fun resolveReadinessAccent(readinessPercent: Int): Color {
    return when {
        readinessPercent >= 90 -> Color(0xFF2ECC71)
        readinessPercent >= 51 -> Color(0xFFFF6B00)
        else -> Color(0xFFB3261E)
    }
}

private fun formatTimestamp(time: Long): String {
    if (time == 0L) return "Never"
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(time))
}

private fun formatExpiryAlertDetail(alert: AlertModel): String {
    val status = if (alert.type == "expired") "Expired" else "Expiring soon"
    val date = PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(alert.expiry_date_ms).ifBlank { "No date" }
    return "${alert.bag_name} | $status | $date"
}
