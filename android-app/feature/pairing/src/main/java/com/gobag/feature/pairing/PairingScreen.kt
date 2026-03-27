package com.gobag.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    view_model: PairingViewModel,
    on_back: () -> Unit
) {
    val state by view_model.ui_state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val isPaired = state.paired_bag_count > 0
    val hasSavedEndpoint = state.endpoint.isNotBlank()
    val statusAccent = when {
        isPaired -> MaterialTheme.colorScheme.tertiary
        state.endpoint_status.equals("Reachable", ignoreCase = true) -> MaterialTheme.colorScheme.primary
        state.endpoint_status.equals("Failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        view_model.on_qr_payload(text)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launcher.launch(buildPairQrScanOptions())
        }
    }

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
                            "TACTICAL LINK",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "CONNECT PI",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                actions = {
                    StatusPill(
                        label = when {
                            isPaired -> "PAIRED"
                            hasSavedEndpoint -> "ADDRESS SAVED"
                            else -> "READY TO PAIR"
                        },
                        accent = statusAccent
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
                PairingHeroCard(
                    endpoint = state.endpoint.ifBlank { "Raspberry Pi Hub" },
                    pairingDetail = state.pairing_detail,
                    statusLabel = state.status,
                    authStatus = state.auth_status,
                    pairedBagCount = state.paired_bag_count,
                    statusAccent = statusAccent
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MiniStatusCard(
                        modifier = Modifier.weight(1f),
                        title = "Endpoint",
                        value = state.endpoint_status,
                        detail = if (hasSavedEndpoint) "Local Pi address is stored on this phone." else "Save or scan a Pi endpoint first.",
                        accent = statusAccent
                    )
                    MiniStatusCard(
                        modifier = Modifier.weight(1f),
                        title = "Auth",
                        value = state.auth_status,
                        detail = if (isPaired) "A bag is authenticated and ready for sync." else "Use a QR scan or manual Pair Code to authenticate.",
                        accent = if (isPaired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                }
            }

            item {
                ControlCard(
                    title = "Saved Raspberry Pi Address",
                    subtitle = "Save and test the Raspberry Pi address first. Then pair with either the bag QR code or the 6-digit Pair Code shown on the Pi."
                ) {
                    OutlinedTextField(
                        value = state.manual_endpoint,
                        onValueChange = view_model::on_manual_endpoint_changed,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://192.168.1.20:8080") },
                        singleLine = true
                    )
                    Text(
                        state.endpoint_detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Pairing status: ${state.status} | Auth: ${state.auth_status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.error.isNotBlank()) {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { view_model.test_connection(state.manual_endpoint) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp),
                        enabled = !state.running,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            if (state.running) "Testing..." else "Test Address",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launcher.launch(buildPairQrScanOptions())
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp),
                        enabled = !state.running,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            if (state.running) "Waiting..." else "Scan QR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                ControlCard(
                    title = "Manual Pair Code",
                    subtitle = "If the Raspberry Pi already shows a 6-digit Pair Code, enter it here to pair without scanning."
                ) {
                    OutlinedTextField(
                        value = state.manual_pair_code,
                        onValueChange = view_model::on_pair_code_changed,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter 6-digit Pair Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Text(
                        "Use the same saved Raspberry Pi address above. The phone will validate the Pair Code with the Pi and then download the bag inventory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = view_model::pair_with_code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        enabled = !state.running,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            if (state.running) "Pairing With Code..." else "Pair With Code",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                ControlCard(
                    title = "Pairing Methods",
                    subtitle = "Both pairing methods reach the same secure Pi backend route and end in the same saved bag state on the phone."
                ) {
                    GuidanceLine(
                        title = "Scan Bag QR",
                        body = "Best when the Pi dashboard is nearby. The QR already contains the Pi address and Pair Code."
                    )
                    GuidanceLine(
                        title = "Enter Pair Code",
                        body = "Best when the camera is unreliable. First save the Pi address above, then enter the 6-digit code manually."
                    )
                }
            }

            if (isPaired) {
                item {
                    OutlinedButton(
                        onClick = view_model::unpair,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Text(
                            "Remove Selected Bag",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                ControlCard(
                    title = "Pairing Flow",
                    subtitle = "Keep the Pi powered and nearby. The phone can now complete the same pairing flow from either a QR scan or the visible Pair Code."
                ) {
                    GuidanceLine(
                        title = "1. Save the address",
                        body = "Test a local Pi endpoint so the phone knows where the hub lives."
                    )
                    GuidanceLine(
                        title = "2. Use QR or Pair Code",
                        body = "Scan the Pi QR code or type the 6-digit Pair Code shown on the Raspberry Pi."
                    )
                    GuidanceLine(
                        title = "3. Start editing offline",
                        body = "Once paired, the bag becomes selectable everywhere in the phone app."
                    )
                }
            }
        }
    }
}

private fun buildPairQrScanOptions(): ScanOptions =
    ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Scan Go-Bag QR")
        setBeepEnabled(false)
    }

@Composable
private fun PairingHeroCard(
    endpoint: String,
    pairingDetail: String,
    statusLabel: String,
    authStatus: String,
    pairedBagCount: Int,
    statusAccent: Color
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
                            statusAccent.copy(alpha = 0.18f),
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
                        Text(
                            "Raspberry Pi Hub",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(label = statusLabel.uppercase(), accent = statusAccent)
                }

                Text(
                    pairingDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        label = "Paired Bags",
                        value = pairedBagCount.toString(),
                        accent = MaterialTheme.colorScheme.primary
                    )
                    HeroMetric(
                        modifier = Modifier.weight(1f),
                        label = "Auth",
                        value = authStatus,
                        accent = statusAccent
                    )
                }
            }
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
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
private fun MiniStatusCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    detail: String,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
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
private fun ControlCard(
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
private fun GuidanceLine(
    title: String,
    body: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun StatusPill(
    label: String,
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
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}
