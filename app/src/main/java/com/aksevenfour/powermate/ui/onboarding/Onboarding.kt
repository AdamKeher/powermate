package com.aksevenfour.powermate.ui.onboarding

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aksevenfour.powermate.ble.BluetoothPermissions
import com.aksevenfour.powermate.ble.FoundDevice
import com.aksevenfour.powermate.ble.LinkStatus
import com.aksevenfour.powermate.ble.PowerpalManager
import com.aksevenfour.powermate.ble.PowerpalProtocol
import com.aksevenfour.powermate.ble.isBluetoothOn
import com.aksevenfour.powermate.data.PairedDevice
import com.aksevenfour.powermate.ui.components.PowerMateLogo
import com.aksevenfour.powermate.ui.components.ScreenScaffold
import com.aksevenfour.powermate.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Welcome → find the Powerpal → enter its pairing code and meter rate. */
@Composable
fun OnboardingFlow() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "welcome") {
        composable("welcome") { WelcomeScreen(onContinue = { nav.navigate("scan") }) }
        composable("scan") {
            ScanScreen(
                onBack = { nav.popBackStack() },
                onChoose = { device ->
                    nav.navigate("pair/${Uri.encode(device.address)}?name=${Uri.encode(device.name.orEmpty())}")
                },
            )
        }
        composable(
            "pair/{address}?name={name}",
            arguments = listOf(
                navArgument("address") { type = NavType.StringType },
                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            PairScreen(
                address = entry.arguments?.getString("address").orEmpty(),
                name = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    ScreenScaffold("Welcome") { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PowerMateLogo(Modifier.size(72.dp))
            Text("PowerMate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "A free, independent app for Powerpal energy monitors. It talks to your Powerpal directly over " +
                    "Bluetooth and keeps all your readings on this phone. No account or cloud needed.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Before you start", style = MaterialTheme.typography.titleMedium)
                    Text("• Have your Powerpal's 6-digit pairing code ready. It's on the card that came with it, or in the old app.")
                    Text("• Close or uninstall the original Powerpal app. A Powerpal talks to only one app at a time.")
                    Text("• Know your meter's rate, printed on the meter as imp/kWh (often 1000).")
                }
            }
            Text(
                "PowerMate is not made by or affiliated with Powerpal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Find my Powerpal") }
        }
    }
}

@Composable
private fun ScanScreen(onBack: () -> Unit, onChoose: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(BluetoothPermissions.canScan(context)) }
    var bluetoothOn by remember { mutableStateOf(context.isBluetoothOn()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = BluetoothPermissions.canScan(context)
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothOn = context.isBluetoothOn()
    }
    LifecycleResumeEffect(Unit) {
        permitted = BluetoothPermissions.canScan(context)
        bluetoothOn = context.isBluetoothOn()
        onPauseOrDispose {}
    }

    ScreenScaffold("Find your Powerpal", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !permitted -> {
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "PowerMate needs permission to find and connect to nearby Bluetooth devices."
                        } else {
                            "Android needs location permission before apps can scan for Bluetooth devices. " +
                                "PowerMate doesn't use your location."
                        },
                    )
                    Button(onClick = { permissionLauncher.launch(BluetoothPermissions.required) }) { Text("Allow") }
                }
                !bluetoothOn -> {
                    Text("Bluetooth is turned off.")
                    Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("Turn on Bluetooth")
                    }
                }
                else -> DeviceList(onChoose)
            }
        }
    }
}

@Composable
private fun DeviceList(onChoose: (FoundDevice) -> Unit) {
    val container = appContainer()
    var devices by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        scanning = true
        error = null
        try {
            withTimeoutOrNull(SCAN_DURATION_MS) { container.scanner.scan().collect { devices = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        scanning = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Looking for Powerpals nearby…", style = MaterialTheme.typography.bodyMedium)
        }
        devices.forEach { device ->
            Card(Modifier.fillMaxWidth().clickable { onChoose(device) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (device.bonded) Icons.Filled.Link else Icons.Filled.Bluetooth, contentDescription = null)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(device.name ?: "Powerpal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(device.address)
                                device.rssi?.let { append(" · signal $it dBm") }
                                if (device.bonded) append(" · paired with this phone")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!scanning) {
            if (devices.isEmpty()) {
                Text(
                    "No Powerpal found. Make sure you're within a few metres of it and that no other app " +
                        "(like the original Powerpal app) is connected to it." +
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " Location must also be switched on." else "",
                )
            }
            OutlinedButton(onClick = { round++ }) { Text("Scan again") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairScreen(address: String, name: String?, onBack: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val link by container.powerpal.link.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var pulsesText by rememberSaveable { mutableStateOf("1000") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pairingCode = PowerpalProtocol.parsePairingCode(code)
    val pulsesPerKwh = pulsesText.toDoubleOrNull()?.takeIf { it > 0 }

    ScreenScaffold(name ?: "Powerpal", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") },
                supportingText = { Text("6 digits, from your Powerpal's card. Keep a copy: losing it means a factory reset.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pulsesText,
                onValueChange = { pulsesText = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                label = { Text("Meter pulses per kWh") },
                supportingText = { Text("Printed on your meter, e.g. \"1000 imp/kWh\". You can change this later.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !busy,
                isError = pulsesPerKwh == null,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COMMON_PULSE_RATES.forEach { rate ->
                    FilterChip(
                        selected = pulsesText == rate,
                        onClick = { pulsesText = rate },
                        label = { Text(rate) },
                        enabled = !busy,
                    )
                }
            }
            Text(
                "If Android asks for a PIN to pair, enter the same 6-digit code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && pairingCode != null && pulsesPerKwh != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (pairingCode == null || pulsesPerKwh == null) return@Button
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val result = container.powerpal.pair(address, pairingCode)
                            container.settings.savePairedDevice(PairedDevice(address, name, pairingCode), pulsesPerKwh, result.buffer)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = PowerpalManager.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        when (link.status) {
                            LinkStatus.PAIRING -> "  Sending pairing code…"
                            else -> "  Connecting…"
                        },
                    )
                } else {
                    Text("Connect")
                }
            }
        }
    }
}

private const val SCAN_DURATION_MS = 20_000L
private val COMMON_PULSE_RATES = listOf("800", "1000", "1600", "2000", "3200")
