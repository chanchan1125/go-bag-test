package com.gobag.feature.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.gobag.core.model.AlertModel
import com.gobag.core.model.Conflict
import com.gobag.domain.logic.PiConnectionSnapshot
import com.gobag.domain.logic.PreparednessRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SyncPanel = Color(0xFF1B1B1C)
private val SyncPanelHigh = Color(0xFF232325)
private val SyncPanelHighest = Color(0xFF2D2D2F)
private val SyncOutline = Color(0xFF5A4136)
private val SyncPrimary = Color(0xFFFF6B00)
private val SyncPrimarySoft = Color(0xFFFFB693)
private val SyncSecondary = Color(0xFF8CCDFF)
private val SyncSuccess = Color(0xFF78DC77)
private val SyncCritical = Color(0xFF93000A)
private val SyncCriticalSoft = Color(0xFFFFDAD6)

@Composable
fun SyncScreen(
    view_model: SyncViewModel,
    on_back: () -> Unit
) {
    val state by view_model.ui_state.collectAsState()
    val connection = state.connection
    val snackbarHost = remember { SnackbarHostState() }
    val connectionAccent = remember(connection, state.conflicts.size) {
        resolveConnectionAccent(connection = connection, conflictCount = state.conflicts.size)
    }
    val autoSyncLocked = state.conflicts.isNotEmpty()

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
            SyncTopBar(
                bagName = state.selected_bag_name,
                connectionLabel = connection.primary_label,
                connectionAccent = connectionAccent,
                onBack = on_back
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                SyncHeroCard(
                    bagName = state.selected_bag_name,
                    connectionLabel = connection.connection_label,
                    detail = connection.detail,
                    lastSync = formatSyncTime(state.last_sync_at),
                    pending = state.pending_phone_changes,
                    running = state.running,
                    connectionAccent = connectionAccent
                )
            }

            item {
                MetricsRow(
                    pending = state.pending_phone_changes,
                    conflicts = state.conflicts.size,
                    alerts = state.alerts.size,
                    connectionAccent = connectionAccent
                )
            }

            item {
                SyncActionsCard(
                    running = state.running,
                    autoSyncEnabled = state.auto_sync_enabled,
                    autoSyncLocked = autoSyncLocked,
                    connection = connection,
                    onSyncNow = view_model::sync_now,
                    onSetAutoSync = view_model::set_auto_sync
                )
            }

            if (connection.last_sync_error.isNotBlank() || connection.last_connection_error.isNotBlank()) {
                item {
                    ErrorNoticeCard(
                        message = connection.last_sync_error.ifBlank { connection.last_connection_error }
                    )
                }
            }

            item {
                SectionLabel("Expiry alerts")
            }

            item {
                ExpiryAlertsCard(
                    bagName = state.selected_bag_name,
                    alerts = state.alerts
                )
            }

            item {
                SectionLabel("Review items")
            }

            if (state.conflicts.isEmpty()) {
                item {
                    NoConflictsCard()
                }
            } else {
                items(state.conflicts, key = { it.item_id }) { conflict ->
                    ConflictReviewCard(
                        conflict = conflict,
                        onKeepPhone = { view_model.keep_phone(conflict) },
                        onKeepPi = { view_model.keep_pi(conflict) },
                        onKeepDeleted = { view_model.keep_deleted(conflict) },
                        onRestore = { view_model.restore(conflict) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncTopBar(
    bagName: String,
    connectionLabel: String,
    connectionAccent: Color,
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
                        "UPDATE BAG",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = SyncPrimarySoft
                    )
                    Text(
                        if (bagName.isBlank()) "No bag selected" else bagName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusPill(
                text = connectionLabel,
                accent = connectionAccent
            )
        }
    }
}

@Composable
private fun SyncHeroCard(
    bagName: String,
    connectionLabel: String,
    detail: String,
    lastSync: String,
    pending: Int,
    running: Boolean,
    connectionAccent: Color
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            connectionAccent.copy(alpha = 0.28f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionLabel("Bag status")
                Text(
                    "UPDATE BAG",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (bagName.isBlank()) "No bag selected yet." else bagName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ConnectionBanner(
                    connectionLabel = connectionLabel,
                    detail = detail,
                    lastSync = lastSync,
                    accent = connectionAccent
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        label = "Phone changes",
                        value = pending.toString(),
                        accent = SyncPrimary
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        label = "Update",
                        value = if (running) "Updating" else "Ready",
                        accent = if (running) SyncPrimary else SyncSuccess
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(
    connectionLabel: String,
    detail: String,
    lastSync: String,
    accent: Color
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    connectionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Last update: $lastSync",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeroMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricsRow(
    pending: Int,
    conflicts: Int,
    alerts: Int,
    connectionAccent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MetricCard(
            modifier = Modifier.weight(1f),
            value = pending.toString(),
            label = "Phone changes",
            accent = SyncPrimary
        )
        MetricCard(
            modifier = Modifier.weight(1f),
            value = if (conflicts == 0) "Clear" else conflicts.toString(),
            label = if (conflicts == 0) "Review" else "Review",
            accent = if (conflicts == 0) SyncSuccess else SyncCriticalSoft
        )
        MetricCard(
            modifier = Modifier.weight(1f),
            value = alerts.toString(),
            label = "Alerts",
            accent = connectionAccent
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SyncActionsCard(
    running: Boolean,
    autoSyncEnabled: Boolean,
    autoSyncLocked: Boolean,
    connection: PiConnectionSnapshot,
    onSyncNow: () -> Unit,
    onSetAutoSync: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SyncPanelHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = SyncPrimary
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Updates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Update any time. Automatic updates turn on when review items are clear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onSyncNow,
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncPrimary,
                    contentColor = Color(0xFF351000)
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (running) "Updating..." else "Update now",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SyncPanel),
                border = BorderStroke(1.dp, SyncOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SyncPanelHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (autoSyncLocked) Icons.Default.Warning else Icons.Default.Settings,
                                contentDescription = null,
                                tint = if (autoSyncLocked) SyncCriticalSoft else SyncPrimarySoft
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (autoSyncEnabled) "Auto update on" else "Auto update off",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                buildAutoSyncMessage(
                                    autoSyncLocked = autoSyncLocked,
                                    connection = connection
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = onSetAutoSync,
                        enabled = !autoSyncLocked,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SyncPrimary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = SyncPanelHighest,
                            disabledCheckedTrackColor = SyncOutline,
                            disabledUncheckedTrackColor = SyncOutline.copy(alpha = 0.55f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorNoticeCard(message: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SyncCritical),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = SyncCriticalSoft
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = SyncCriticalSoft
            )
        }
    }
}

@Composable
private fun ExpiryAlertsCard(
    bagName: String,
    alerts: List<AlertModel>
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh),
        border = BorderStroke(1.dp, SyncCritical.copy(alpha = 0.36f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = SyncPrimarySoft
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Bag alerts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (alerts.isEmpty()) {
                            "No expired or near-expiry items in $bagName."
                        } else {
                            "Items that need attention in this bag."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (alerts.isEmpty()) {
                StatusMessageCard(
                    icon = Icons.Default.CheckCircle,
                    iconTint = SyncSuccess,
                    title = "No active alerts",
                    body = "This bag has no expired or near-expiry items right now."
                )
            } else {
                alerts.take(5).forEach { alert ->
                    AlertRow(alert = alert)
                }
                if (alerts.size > 5) {
                    Text(
                        "+${alerts.size - 5} more item(s) flagged",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: AlertModel) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanel),
        modifier = Modifier.fillMaxWidth()
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                formatAlertDetail(alert),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoConflictsCard() {
    StatusMessageCard(
        icon = Icons.Default.CheckCircle,
        iconTint = SyncSuccess,
        title = "Nothing to review",
        body = "You can update your bag whenever you are ready."
    )
}

@Composable
private fun ConflictReviewCard(
    conflict: Conflict,
    onKeepPhone: () -> Unit,
    onKeepPi: () -> Unit,
    onKeepDeleted: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh),
        border = BorderStroke(1.dp, SyncOutline),
        modifier = Modifier.fillMaxWidth()
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SyncCriticalSoft)
                )
                Text(
                    conflict.server_version.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "Needs review: ${conflict.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Choose which version to keep.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onKeepPhone,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SyncPrimary,
                        contentColor = Color(0xFF351000)
                    )
                ) {
                    Text("Keep phone")
                }
                OutlinedButton(
                    onClick = onKeepPi,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SyncOutline)
                ) {
                    Text("Keep bag")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onKeepDeleted,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SyncCritical.copy(alpha = 0.5f))
                ) {
                    Text(
                        "Keep deleted",
                        color = SyncCriticalSoft
                    )
                }
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SyncOutline)
                ) {
                    Text("Restore")
                }
            }
        }
    }
}

@Composable
private fun StatusMessageCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SyncPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    accent: Color
) {
    Surface(
        modifier = Modifier.widthIn(max = 132.dp),
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun buildAutoSyncMessage(
    autoSyncLocked: Boolean,
    connection: PiConnectionSnapshot
): String {
    return when {
        autoSyncLocked -> "Automatic updates stay off until every review item is cleared."
        connection.is_online -> "Your bag is ready for automatic updates."
        connection.is_paired -> "Automatic updates will resume when your bag is back online."
        else -> "Connect your bag before turning on automatic updates."
    }
}

private fun resolveConnectionAccent(
    connection: PiConnectionSnapshot,
    conflictCount: Int
): Color {
    return when {
        connection.last_sync_error.isNotBlank() || connection.last_connection_error.isNotBlank() -> SyncCriticalSoft
        conflictCount > 0 -> SyncPrimarySoft
        connection.is_online -> SyncSecondary
        connection.is_offline || connection.address_needs_attention -> SyncCriticalSoft
        connection.is_paired -> SyncPrimarySoft
        else -> SyncSuccess
    }
}

private fun formatSyncTime(value: Long): String {
    if (value == 0L) return "Never"
    return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(value))
}

private fun formatAlertDetail(alert: AlertModel): String {
    val status = if (alert.type == "expired") "Expired" else "Expiring soon"
    val date = PreparednessRules.format_epoch_ms_to_yyyy_mm_dd(alert.expiry_date_ms).ifBlank { "No date" }
    return "${alert.bag_name} - $status - $date"
}
