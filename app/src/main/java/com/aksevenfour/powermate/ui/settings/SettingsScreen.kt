package com.aksevenfour.powermate.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import com.aksevenfour.powermate.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aksevenfour.powermate.AppContainer
import com.aksevenfour.powermate.BuildConfig
import com.aksevenfour.powermate.data.AppSettings
import com.aksevenfour.powermate.ui.Format
import com.aksevenfour.powermate.ui.components.InfoRow
import com.aksevenfour.powermate.ui.components.RateSwatch
import com.aksevenfour.powermate.ui.components.rateColor
import com.aksevenfour.powermate.ui.components.PowerMateLogo
import com.aksevenfour.powermate.ui.components.ScreenScaffold
import com.aksevenfour.powermate.ui.components.SectionCard
import com.aksevenfour.powermate.ui.components.TariffText
import com.aksevenfour.powermate.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

private const val MINUTES_PER_DAY = 1_440

/** Long-running data jobs live here so they survive switching tabs. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun setPulsesPerKwh(value: Double) = viewModelScope.launch { container.settings.setPulsesPerKwh(value) }

    fun setAmberToken(token: String?) = viewModelScope.launch {
        container.settings.setAmberToken(token)
        // Prices change source with the token, so drop whatever was fetched before.
        runCatching { container.prices.refresh(token, maxAge = java.time.Duration.ZERO) }
            .onSuccess { _messages.send(if (token.isNullOrBlank()) "Showing wholesale spot prices" else "Showing your Amber prices") }
            .onFailure { _messages.send(it.message ?: "Couldn't load Amber prices") }
    }

    fun setBackgroundSync(enabled: Boolean) = viewModelScope.launch {
        container.settings.setBackgroundSync(enabled)
        container.applyBackgroundSync()
    }

    fun exportCsv(uri: Uri) = runJob("Exporting…") {
        val count = container.dataTools.exportCsv(uri, settings.value.pulsesPerKwh)
        "Exported ${"%,d".format(count)} readings"
    }

    fun importFromCloud(deviceId: String, apiKey: String) = runJob("Importing from Powerpal's cloud…") {
        val count = container.dataTools.importFromCloud(deviceId, apiKey) { done ->
            _busy.value = "Importing from Powerpal's cloud… ${(done * 100).roundToInt()}%"
        }
        when {
            count == 0 -> "Powerpal's cloud had nothing to import"
            count < MINUTES_PER_DAY -> "Imported ${"%,d".format(count)} minutes of history"
            else -> "Imported ${"%,d".format((count + MINUTES_PER_DAY / 2) / MINUTES_PER_DAY)} days of minute-by-minute history"
        }
    }

    fun deleteAllReadings() = runJob("Deleting…") {
        container.database.readings().deleteAll()
        container.settings.restartHistory()
        "All readings deleted. Readings still on the Powerpal can be downloaded again from the Live screen."
    }

    private fun runJob(label: String, job: suspend () -> String) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            val message = try {
                job()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
            }
            _messages.send(message)
        }
    }
}

@Composable
fun SettingsScreen(onEditTariff: () -> Unit, onComparePlans: () -> Unit, onAlerts: () -> Unit) {
    val container = appContainer()
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(viewModel::exportCsv)
    }

    ScreenScaffold("Settings", snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            SectionCard(title = "Meter") {
                PulsesPerKwhField(settings.pulsesPerKwh, viewModel::setPulsesPerKwh)
            }

            SectionCard(title = "Electricity plan") {
                val tariff = settings.tariff
                tariff.allRates.forEachIndexed { index, rate ->
                    val times = when {
                        index < tariff.rates.size -> rate.ranges.map(TariffText::range)
                        tariff.isTimeOfUse -> listOf("All other times")
                        else -> listOf("All the time")
                    }
                    Row {
                        RateSwatch(rateColor(tariff, index), Modifier.padding(top = 4.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(rate.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(Format.rate(rate.centsPerKwh), style = MaterialTheme.typography.bodyMedium)
                            }
                            times.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp)) {
                    Text("Daily connection fee", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Format.supply(tariff.supplyCentsPerDay), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onEditTariff, modifier = Modifier.fillMaxWidth()) { Text("Edit plan") }
                Button(onClick = onComparePlans, modifier = Modifier.fillMaxWidth()) { Text("Compare plans for my usage") }
            }

            SectionCard(title = "Notifications") {
                Text(
                    "Rate reminders, usage alerts, summaries and price spikes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onAlerts, modifier = Modifier.fillMaxWidth()) { Text("Choose notifications") }
            }

            SectionCard(title = "Amber Electric") {
                AmberTokenField(settings.amberToken, viewModel::setAmberToken)
            }

            SectionCard(title = "Sync") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background sync")
                        Text(
                            "Download new readings every couple of hours while the app is closed, " +
                                "so none are lost if the Powerpal's memory fills up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.backgroundSync, onCheckedChange = viewModel::setBackgroundSync)
                }
            }

            SectionCard(title = "Your data") {
                Button(
                    onClick = { exportLauncher.launch("powermate-${LocalDate.now()}.csv") },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export readings (CSV)") }
                OutlinedButton(
                    onClick = { showImport = true },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import history from Powerpal's cloud") }
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all readings", color = MaterialTheme.colorScheme.error) }
            }

            SectionCard(title = "About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PowerMateLogo(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("PowerMate ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Free, independent software that keeps Powerpal monitors working after the official app was " +
                        "abandoned. Not made by or affiliated with Powerpal. Bluetooth protocol thanks to the " +
                        "powerpal-esphome project by Gary Pendergast and WeekendWarrior1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MadeBy()
            }
        }
    }

    if (showImport) {
        CloudImportDialog(
            initialDeviceId = settings.device?.serial.orEmpty(),
            initialApiKey = settings.apiKey.orEmpty(),
            onDismiss = { showImport = false },
            onImport = { id, key ->
                showImport = false
                viewModel.importFromCloud(id, key)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all readings?") },
            text = { Text("This removes every reading stored on this phone. It can't be undone; export a CSV first if you want a copy.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAllReadings()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private const val HOMEPAGE = "https://aksevenfour.com/powermate"

/** The developer's badge and the app's homepage, which opens in the browser. */
@Composable
private fun MadeBy() {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { runCatching { uriHandler.openUri(HOMEPAGE) } }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.logo_aksevenfour),
            contentDescription = "aksevenfour",
            modifier = Modifier.height(40.dp).aspectRatio(190.47f / 100f),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Made by aksevenfour", style = MaterialTheme.typography.bodyMedium)
            Text(HOMEPAGE.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PulsesPerKwhField(current: Double, onSave: (Double) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(Format.number(current)) }
    val parsed = text.toDoubleOrNull()?.takeIf { it > 0 }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
        label = { Text("Pulses per kWh") },
        supportingText = { Text("Printed on your meter as imp/kWh. Changing it recalculates all history.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = parsed == null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (parsed != null && parsed != current) {
        Button(onClick = { onSave(parsed) }) { Text("Save") }
    }
}

@Composable
private fun CloudImportDialog(
    initialDeviceId: String,
    initialApiKey: String,
    onDismiss: () -> Unit,
    onImport: (String, String) -> Unit,
) {
    var deviceId by rememberSaveable { mutableStateOf(initialDeviceId) }
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from Powerpal's cloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copies your minute-by-minute history from Powerpal's servers, if they're still running. " +
                        "Running it again refreshes it, keeping readings that came straight from your Powerpal. " +
                        "Both values are read from your Powerpal automatically when available.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(deviceId, { deviceId = it.trim() }, label = { Text("Device ID") }, singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it.trim() }, label = { Text("API key") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(deviceId, apiKey) }, enabled = deviceId.isNotBlank() && apiKey.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AmberTokenField(current: String?, onSave: (String?) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current.orEmpty()) }
    Text(
        "Amber customers: add your API token (Amber app or website → Settings → Developers) to see your own " +
            "all-in prices on the Prices tab instead of the wholesale spot price.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.trim() },
        label = { Text("Amber API token") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (text != current.orEmpty()) Button(onClick = { onSave(text.ifBlank { null }) }) { Text("Save") }
        if (!current.isNullOrBlank()) TextButton(onClick = { onSave(null) }) { Text("Remove") }
    }
}
