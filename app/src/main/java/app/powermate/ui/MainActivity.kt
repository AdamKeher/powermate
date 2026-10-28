package app.powermate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.powermate.data.AppSettings
import app.powermate.data.PairedDevice
import app.powermate.ui.components.appContainer
import app.powermate.ui.device.DeviceScreen
import app.powermate.ui.device.DiagnosticsScreen
import app.powermate.ui.live.LiveScreen
import app.powermate.ui.onboarding.OnboardingFlow
import app.powermate.ui.prices.PricesScreen
import app.powermate.ui.plans.PlanComparisonScreen
import app.powermate.ui.settings.AlertsScreen
import app.powermate.ui.settings.SettingsScreen
import app.powermate.ui.settings.TariffScreen
import app.powermate.ui.theme.PowerMateTheme
import app.powermate.ui.usage.UsageScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PowerMateTheme { PowerMateRoot() }
        }
    }
}

@Composable
private fun PowerMateRoot() {
    val container = appContainer()
    val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val device = settings?.device
    when {
        settings == null -> Box(Modifier.fillMaxSize())
        device == null -> OnboardingFlow()
        else -> MainScaffold(device)
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIVE("live", "Live", Icons.Filled.Bolt),
    USAGE("usage", "Usage", Icons.Filled.BarChart),
    PRICES("prices", "Prices", Icons.AutoMirrored.Filled.TrendingUp),
    DEVICE("device", "Device", Icons.Filled.Sensors),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold(device: PairedDevice) {
    val container = appContainer()

    // Stay connected for live power while the app is on screen, and briefly after.
    LifecycleStartEffect(device) {
        container.onForeground()
        onStopOrDispose { container.onBackground() }
    }
    LaunchedEffect(device.address) {
        container.applyBackgroundSync()
        if (container.settings.current().backgroundLive) app.powermate.notify.LiveMonitorService.start(container.context)
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.LIVE.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Tab.LIVE.route) { LiveScreen() }
            composable(Tab.USAGE.route) { UsageScreen() }
            composable(Tab.PRICES.route) { PricesScreen() }
            composable(Tab.DEVICE.route) { DeviceScreen(onOpenDiagnostics = { nav.navigate("diagnostics") }) }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(
                    onEditTariff = { nav.navigate("tariff") },
                    onComparePlans = { nav.navigate("plans") },
                    onAlerts = { nav.navigate("alerts") },
                )
            }
            composable("plans") { PlanComparisonScreen(onBack = { nav.popBackStack() }) }
            composable("alerts") { AlertsScreen(onBack = { nav.popBackStack() }) }
            composable("tariff") { TariffScreen(onBack = { nav.popBackStack() }) }
            composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
