package com.gobag.feature.checkmode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gobag.core.model.Item
import com.gobag.domain.logic.PreparednessRules

private val ReviewPanel = Color(0xFF1B1B1C)
private val ReviewPanelHigh = Color(0xFF232325)
private val ReviewPanelHighest = Color(0xFF2D2D2F)
private val ReviewOutline = Color(0xFF5A4136)
private val ReviewPrimary = Color(0xFFFF6B00)
private val ReviewPrimarySoft = Color(0xFFFFB693)
private val ReviewSuccess = Color(0xFF78DC77)
private val ReviewSuccessDeep = Color(0xFF0C3411)
private val ReviewCritical = Color(0xFF93000A)
private val ReviewCriticalText = Color(0xFFFFDAD6)

@Composable
fun CheckModeScreen(
    view_model: CheckModeViewModel,
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

    val packedCount = state.items.count { it.packed_status }
    val totalCount = state.items.size
    val unpackedItems = state.items.filterNot { it.packed_status }
    val progress = if (totalCount == 0) 0f else packedCount.toFloat() / totalCount.toFloat()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            ReviewTopBar(
                packedCount = packedCount,
                totalCount = totalCount,
                onBack = on_back
            )
        },
        bottomBar = {
            CompleteReviewBar(onBack = on_back)
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                ReviewHeroCard(
                    packedCount = packedCount,
                    totalCount = totalCount,
                    progress = progress
                )
            }

            item {
                if (unpackedItems.isNotEmpty()) {
                    ReviewQueueSection(unpackedItems = unpackedItems)
                } else {
                    AllClearCard(totalCount = totalCount)
                }
            }

            item {
                SectionLabel("Mandatory Inventory")
            }

            if (state.items.isEmpty()) {
                item {
                    EmptyReviewCard()
                }
            } else {
                items(state.items) { item ->
                    ReviewItemCard(
                        item = item,
                        onToggle = { view_model.toggle(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewTopBar(
    packedCount: Int,
    totalCount: Int,
    onBack: () -> Unit
) {
    val percent = if (totalCount == 0) 0 else ((packedCount.toFloat() / totalCount.toFloat()) * 100f).toInt()
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
                        color = ReviewPrimarySoft
                    )
                    Text(
                        "DEPARTURE REVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "$percent% READY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ReviewPrimarySoft
            )
        }
    }
}

@Composable
private fun ReviewHeroCard(
    packedCount: Int,
    totalCount: Int,
    progress: Float
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ReviewPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ReviewSuccess.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        radius = 520f
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionLabel("Active Protocol")
                Text(
                    "DEPARTURE REVIEW",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "$packedCount/$totalCount",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = ReviewSuccess
                        )
                        Text(
                            if (totalCount == 0) "NO ITEMS READY FOR REVIEW" else "CRITICAL ITEMS CHECKED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ReviewSuccess
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ReviewPanelHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = ReviewSuccess,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = ReviewSuccess,
                    trackColor = ReviewPanelHighest
                )
                Text(
                    if (totalCount == 0) {
                        "Add inventory items and sync them before running a departure review."
                    } else {
                        "${totalCount - packedCount} item(s) still need confirmation before this checklist is complete."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReviewQueueSection(unpackedItems: List<Item>) {
    val queueItems = unpackedItems.take(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Review Queue (${unpackedItems.size})")
        queueItems.forEach { item ->
            ReviewQueueCard(item = item)
        }
    }
}

@Composable
private fun ReviewQueueCard(item: Item) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ReviewCritical),
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
                tint = ReviewCriticalText
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = ReviewCriticalText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${PreparednessRules.normalize_category(item.category)} | ${item.quantity} ${item.unit} | Pending review",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReviewCriticalText.copy(alpha = 0.86f)
                )
            }
        }
    }
}

@Composable
private fun AllClearCard(totalCount: Int) {
    val message = when {
        totalCount == 0 -> "No items are available to review yet."
        else -> "Every visible item is currently marked packed on this phone."
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ReviewPanelHigh),
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
                    .background(ReviewSuccess),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = ReviewSuccessDeep
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (totalCount == 0) "Nothing to review" else "All items verified",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyReviewCard() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ReviewPanelHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Nothing to check",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Add inventory items and sync with the Raspberry Pi before using this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: Item,
    onToggle: () -> Unit
) {
    val packed = item.packed_status
    val statusColor = if (packed) ReviewSuccess else ReviewPrimarySoft
    val statusText = if (packed) "VERIFIED" else "PENDING REVIEW"
    val toggleBackground = if (packed) ReviewSuccess else Color.Transparent
    val toggleBorder = if (packed) ReviewSuccess else ReviewOutline
    val toggleContent = if (packed) ReviewSuccessDeep else ReviewPrimarySoft

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ReviewPanelHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(category = PreparednessRules.normalize_category(item.category), packed = packed)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Text(
                    "${PreparednessRules.normalize_category(item.category)} | ${item.quantity} ${item.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = toggleBackground,
                border = BorderStroke(2.dp, toggleBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (packed) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Mark unpacked",
                            tint = toggleContent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: String, packed: Boolean) {
    val background = if (packed) ReviewSuccess.copy(alpha = 0.18f) else ReviewPanelHighest
    val content = if (packed) ReviewSuccess else ReviewPrimarySoft
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            categoryToken(category),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = content
        )
    }
}

@Composable
private fun CompleteReviewBar(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 16.dp
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ReviewPrimary,
                contentColor = Color(0xFF351000)
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Complete Review",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null
                )
            }
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

private fun categoryToken(category: String): String = when (category) {
    "Water & Food" -> "WF"
    "Medical & Health" -> "MH"
    "Light & Communication" -> "LC"
    "Tools & Protection" -> "TP"
    "Hygiene" -> "HY"
    else -> "OT"
}
