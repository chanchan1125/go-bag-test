package com.gobag.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gobag.app.ui.theme.TacticalTheme
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

private data class StartupState(
    val phone_device_id: String,
    val selected_bag_id: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoBagApp()
        }
    }
}

@Composable
private fun GoBagApp() {
    val context = LocalContext.current
    val container = remember { AppContainer(context) }
    val nav = rememberNavController()
    val darkThemeEnabled by container.device_state_store.dark_theme_enabled.collectAsState(initial = true)

    val startup_state = remember {
        runBlocking {
            container.device_state_store.initialize_phone_device_id_if_missing()
            val state = container.device_state_store.state.first()
            val pairedBagIds = state.paired_bags.map { it.bag_id }
            val bag_id = when {
                state.selected_bag_id.isNotBlank() && state.selected_bag_id in pairedBagIds -> state.selected_bag_id
                pairedBagIds.isNotEmpty() -> pairedBagIds.first()
                else -> ""
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
            val pairedBagIds = state.paired_bags.map { it.bag_id }.toSet()
            val pairedBags = bags.filter { it.bag_id in pairedBagIds }
            val resolvedBagId = when {
                pairedBags.isEmpty() -> ""
                state.selected_bag_id.isNotBlank() && pairedBags.any { it.bag_id == state.selected_bag_id } -> state.selected_bag_id
                else -> pairedBags.first().bag_id
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
    val settings_vm = remember {
        SettingsViewModel(
            container.item_repository,
            container.pairing_repository,
            container.sync_repository,
            container.device_state_store
        )
    }

    TacticalTheme(darkTheme = darkThemeEnabled) {
        Surface(color = MaterialTheme.colorScheme.background) {
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
    }
}
