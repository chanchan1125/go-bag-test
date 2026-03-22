package com.gobag.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gobag.feature.checkmode.CheckModeScreen
import com.gobag.feature.checkmode.CheckModeViewModel
import com.gobag.feature.inventory.InventoryScreen
import com.gobag.feature.inventory.InventoryViewModel
import com.gobag.feature.pairing.PairingScreen
import com.gobag.feature.pairing.PairingViewModel
import com.gobag.feature.sync.SyncScreen
import com.gobag.feature.sync.SyncViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val GoBagBlack = Color(0xFF0E0E0E)
private val GoBagSurface = Color(0xFF1A1A1A)
private val GoBagSurfaceHigh = Color(0xFF20201F)
private val GoBagSurfaceHighest = Color(0xFF262626)
private val GoBagPrimary = Color(0xFFFF9F4A)
private val GoBagSecondary = Color(0xFFFFA52A)
private val GoBagTertiary = Color(0xFFFFE393)
private val GoBagError = Color(0xFFFF7351)
private val GoBagText = Color(0xFFFFFFFF)
private val GoBagMuted = Color(0xFFADAAAA)
private val GoBagOnPrimary = Color(0xFF532A00)

@Composable
fun TacticalTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = GoBagPrimary,
        onPrimary = GoBagOnPrimary,
        background = GoBagBlack,
        onBackground = GoBagText,
        surface = GoBagSurface,
        onSurface = GoBagText,
        surfaceVariant = GoBagSurfaceHighest,
        onSurfaceVariant = GoBagMuted,
        error = GoBagError,
        onError = Color.Black,
        secondary = GoBagSecondary,
        onSecondary = Color.Black,
        tertiary = GoBagTertiary,
        onTertiary = Color.Black
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private data class StartupState(
    val phone_device_id: String,
    val selected_bag_id: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TacticalTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GoBagApp()
                }
            }
        }
    }
}

@Composable
private fun GoBagApp() {
    val context = LocalContext.current
    val container = remember { AppContainer(context) }
    val nav = rememberNavController()

    val startup_state = remember {
        runBlocking {
            container.device_state_store.initialize_phone_device_id_if_missing()
            val state = container.device_state_store.state.first()
            val bag_id = if (state.selected_bag_id.isNotBlank()) {
                state.selected_bag_id
            } else {
                container.item_repository.observe_bags().first().firstOrNull()?.bag_id.orEmpty()
            }
            StartupState(
                phone_device_id = state.phone_device_id,
                selected_bag_id = bag_id
            )
        }
    }

    LaunchedEffect(container) {
        combine(
            container.sync_repository.observe_device_state(),
            container.item_repository.observe_bags()
        ) { state, bags ->
            state to bags
        }.collect { (state, bags) ->
            // Keep one valid primary bag shared across every screen.
            val resolvedBagId = when {
                bags.isEmpty() -> ""
                state.selected_bag_id.isNotBlank() && bags.any { it.bag_id == state.selected_bag_id } -> state.selected_bag_id
                else -> bags.first().bag_id
            }
            if (resolvedBagId != state.selected_bag_id) {
                container.sync_repository.set_selected_bag_id(resolvedBagId)
            }
        }
    }

    val home_vm = remember { HomeViewModel(container.item_repository, container.sync_repository) }
    val inventory_vm = remember {
        InventoryViewModel(
            container.item_repository,
            container.sync_repository,
            startup_state.phone_device_id,
            startup_state.selected_bag_id
        )
    }
    val check_vm = remember {
        CheckModeViewModel(
            container.item_repository,
            container.sync_repository,
            startup_state.phone_device_id,
            startup_state.selected_bag_id
        )
    }
    val sync_vm = remember { SyncViewModel(container.item_repository, container.sync_repository) }
    val pairing_vm = remember { PairingViewModel(container.pairing_repository, container.sync_repository) }
    val settings_vm = remember { SettingsViewModel(container.item_repository, container.pairing_repository, container.sync_repository) }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                view_model = home_vm,
                on_inventory = { nav.navigate("inventory") },
                on_check_mode = { nav.navigate("check") },
                on_sync = { nav.navigate("sync") },
                on_pairing = { nav.navigate("pair") },
                on_settings = { nav.navigate("settings") }
            )
        }
        composable("inventory") {
            InventoryScreen(view_model = inventory_vm, on_back = { nav.popBackStack() })
        }
        composable("check") {
            CheckModeScreen(view_model = check_vm, on_back = { nav.popBackStack() })
        }
        composable("sync") {
            SyncScreen(view_model = sync_vm, on_back = { nav.popBackStack() })
        }
        composable("pair") {
            PairingScreen(view_model = pairing_vm, on_back = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(view_model = settings_vm, on_back = { nav.popBackStack() })
        }
    }
}
