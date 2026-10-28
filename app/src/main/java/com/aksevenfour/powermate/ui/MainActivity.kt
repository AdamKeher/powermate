package com.aksevenfour.powermate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.aksevenfour.powermate.BuildConfig
import com.aksevenfour.powermate.PowerMateApp
import com.aksevenfour.powermate.data.AppSettings
import com.aksevenfour.powermate.data.PairedDevice
import com.aksevenfour.powermate.ui.components.appContainer
import com.aksevenfour.powermate.ui.device.DeviceScreen
import com.aksevenfour.powermate.ui.device.DiagnosticsScreen
import com.aksevenfour.powermate.ui.live.LiveScreen
import com.aksevenfour.powermate.ui.onboarding.OnboardingFlow
import com.aksevenfour.powermate.ui.prices.PricesScreen
import com.aksevenfour.powermate.ui.plans.PlanComparisonScreen
import com.aksevenfour.powermate.ui.settings.AlertsScreen
import com.aksevenfour.powermate.ui.settings.SettingsScreen
import com.aksevenfour.powermate.ui.settings.TariffScreen
import com.aksevenfour.powermate.ui.theme.PowerMateTheme
import com.aksevenfour.powermate.ui.usage.UsageScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Debug builds can fake a connected Powerpal for screenshots and UI work:
        // adb shell am start -n com.aksevenfour.powermate/.ui.MainActivity --ez mock_live true
        if (BuildConfig.DEBUG && intent.getBooleanExtra("mock_live", false)) {
            (application as PowerMateApp).container.mockLive = true
        }
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
        if (container.settings.current().backgroundLive) com.aksevenfour.powermate.notify.LiveMonitorService.start(container.context)
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
