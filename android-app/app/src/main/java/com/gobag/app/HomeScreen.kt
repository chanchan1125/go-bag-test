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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inventory
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gobag.core.model.AlertModel
import com.gobag.domain.logic.PiConnectionSnapshot
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
    val connection = state.connection
    val missingCategories = state.checklist.filterNot { it.checked }.map { it.name }
    val readinessPercent = if (state.checklist_total == 0) 0 else {
        ((state.checklist_covered.toFloat() / state.checklist_total.toFloat()) * 100f).roundToInt()
    }
    val readinessAccent = resolveReadinessAccent(readinessPercent)
    val connectionAccent = resolveConnectionAccent(connection)
    val primaryAlert = when {
        connection.last_sync_error.isNotBlank() -> connection.last_sync_error
        connection.last_connection_error.isNotBlank() -> connection.last_connection_error
        state.has_conflicts -> "Review items before turning automatic updates back on."
        state.expiry_alerts.isNotEmpty() -> "Review the items in Expiry Watch below."
        state.alerts.isNotEmpty() -> state.alerts.first()
        state.sync_recommended -> "Phone changes are ready to send to your bag."
        else -> "Everything looks steady right now."
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            modifier = Modifier.size(34.dp),
                            painter = painterResource(id = R.drawable.gobag_icon),
                            contentDescription = "GO BAG icon"
                        )
                        Text(
                            "GO BAG DASHBOARD",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                actions = {
                    TacticalTopPill(
                        text = connection.primary_label,
                        accent = connectionAccent
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    IconButton(onClick = on_pairing) {
                        Icon(
                            Icons.Default.SettingsRemote,
                            contentDescription = "Connect bag",
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HomeHeroCard(
                    bagName = state.selected_bag_name,
                    readinessPercent = readinessPercent,
                    readinessAccent = readinessAccent,
                    bagReadiness = state.bag_readiness,
                    connectionLabel = connection.connection_label,
                    pendingChanges = state.pending_phone_changes,
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
                        label = "Bags",
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
                StatusCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "Bag status",
                    value = connection.connection_label,
                    detail = connection.detail,
                    icon = if (connection.is_online) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    accent = connectionAccent
                )
            }

            item {
                AlertSummaryCard(
                    message = primaryAlert,
                    missingCount = (state.checklist_total - state.checklist_covered).coerceAtLeast(0),
                    expiringCount = state.near_expiry_count,
                    conflictCount = if (state.has_conflicts) 1 else 0,
                    missingCategories = missingCategories,
                    alerts = state.expiry_alerts
                )
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
                            "Your bag summary and recent updates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TacticalTopPill(
                        text = formatBagReadinessLabel(bagReadiness),
                        accent = readinessAccent,
                        maxWidth = 168.dp
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
                        HeroDetailLine("Bag status", connectionLabel)
                        HeroDetailLine("Phone changes", pendingChanges.toString())
                        HeroDetailLine("Last update", lastSync)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
    conflictCount: Int,
    missingCategories: List<String>,
    alerts: List<AlertModel>
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
            if (missingCategories.isNotEmpty()) {
                Text(
                    "Missing Categories",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                missingCategories.forEach { category ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = category,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (alerts.isNotEmpty()) {
                Text(
                    "Expiry Watch",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                alerts.take(4).forEach { alert ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = formatExpiryAlertDetail(alert),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (alerts.size > 4) {
                    Text(
                        "+${alerts.size - 4} more item(s) need attention",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
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
    accent: Color,
    maxWidth: Dp = 132.dp
) {
    Surface(
        modifier = Modifier.widthIn(max = maxWidth),
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
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatBagReadinessLabel(value: String): String {
    return when (value.trim()) {
        "Attention Needed" -> "Needs Attention"
        else -> value
    }
}

private fun resolveReadinessAccent(readinessPercent: Int): Color {
    return when {
        readinessPercent >= 90 -> Color(0xFF2ECC71)
        readinessPercent >= 51 -> Color(0xFFFF6B00)
        else -> Color(0xFFB3261E)
    }
}

@Composable
private fun resolveConnectionAccent(connection: PiConnectionSnapshot): Color {
    return when {
        connection.last_sync_error.isNotBlank() -> MaterialTheme.colorScheme.error
        connection.address_needs_attention || connection.is_offline -> MaterialTheme.colorScheme.error
        connection.is_online -> MaterialTheme.colorScheme.tertiary
        connection.is_paired -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
}

private fun formatTimestamp(time: Long): String {
    if (time == 0L) return "Never"
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(time))
}

private fun formatExpiryAlertDetail(alert: AlertModel): String {
    val status = if (alert.type == "expired") "Expired" else "Expiring Soon"
    val date = PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(alert.expiry_date_ms).ifBlank { "No date" }
    return "${alert.bag_name} - ${alert.item_name} - $status - $date"
}
