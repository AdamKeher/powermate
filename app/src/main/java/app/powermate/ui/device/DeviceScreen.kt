package app.powermate.ui.device

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powermate.data.AppSettings
import app.powermate.ui.Format
import app.powermate.ui.components.InfoRow
import app.powermate.ui.components.LinkStatusLabel
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.appContainer
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DeviceScreen(onOpenDiagnostics: () -> Unit) {
    val container = appContainer()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val link by container.powerpal.link.collectAsStateWithLifecycle()
    val readingCount by container.database.readings().observeCount().collectAsStateWithLifecycle(initialValue = 0)
    var showCode by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val device = settings.device ?: return

    ScreenScaffold("Device", actions = { LinkStatusLabel(link) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = device.name ?: "Powerpal") {
                InfoRow("Bluetooth address", device.address)
                device.serial?.let { InfoRow("Device ID", it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoRow(
                        "Pairing code",
                        if (showCode) String.format(Locale.ROOT, "%06d", device.pairingCode) else "••••••",
                        Modifier.weight(1f),
                    )
                    IconButton(onClick = { showCode = !showCode }) {
                        Icon(
                            if (showCode) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showCode) "Hide pairing code" else "Show pairing code",
                        )
                    }
                }
                InfoRow("Battery", settings.battery?.let { "$it%" } ?: "—")
                settings.deviceInfo.forEach { (label, value) -> InfoRow(label, value) }
                settings.ledSensitivity?.let { InfoRow("LED sensitivity (raw)", it) }
            }

            SectionCard(title = "Readings") {
                InfoRow(
                    "Held on device",
                    if (settings.bufferFirst > 0 && settings.bufferLast > 0) {
                        val days = (settings.bufferLast - settings.bufferFirst) / 86_400.0
                        "${Format.dateTime(settings.bufferFirst)} → ${Format.dateTime(settings.bufferLast)} " +
                            String.format(Locale.getDefault(), "(%.1f days)", days)
                    } else {
                        "—"
                    },
                )
                InfoRow("Stored on phone", String.format(Locale.getDefault(), "%,d", readingCount))
                InfoRow("Last synced", Format.ago(settings.lastSyncMillis))
                if (settings.earlierAvailable) {
                    InfoRow(
                        "From before pairing",
                        String.format(Locale.getDefault(), "%.1f days on device", settings.earlierDays) +
                            if (settings.earlierRequested) ", downloading" else "",
                    )
                    OutlinedButton(
                        onClick = { container.scope.launch { container.powerpal.setEarlierHistoryRequested(!settings.earlierRequested) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (settings.earlierRequested) "Stop downloading earlier readings" else "Download earlier readings") }
                } else if (settings.bufferFirst > 0) {
                    OutlinedButton(
                        onClick = {
                            container.scope.launch {
                                container.settings.reofferAllReadings()
                                container.powerpal.setEarlierHistoryRequested(true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download everything on the Powerpal again") }
                }
                Button(onClick = { container.powerpal.requestSync() }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            }

            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth diagnostics") }
            TextButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Forget this Powerpal", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this Powerpal?") },
            text = {
                Text(
                    "PowerMate will disconnect and stop syncing. Your stored readings are kept. " +
                        "Make sure you have a copy of the pairing code: " +
                        String.format(Locale.ROOT, "%06d", device.pairingCode) + ".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    container.stopLive()
                    // Outlives this screen, which disappears as soon as the device is forgotten.
                    container.scope.launch {
                        container.settings.forgetDevice()
                        container.applyBackgroundSync()
                    }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val lines by container.log.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    ScreenScaffold(
        "Bluetooth diagnostics",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PowerMate Bluetooth log")
                    putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                }
                context.startActivity(Intent.createChooser(send, "Share log"))
            }) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
            IconButton(onClick = container.log::clear) { Icon(Icons.Filled.Delete, contentDescription = "Clear log") }
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                "Nothing logged yet. Bluetooth activity appears here while PowerMate talks to your Powerpal.",
                Modifier.padding(padding).padding(16.dp),
            )
        } else {
            SelectionContainer(Modifier.padding(padding)) {
                LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    items(lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
