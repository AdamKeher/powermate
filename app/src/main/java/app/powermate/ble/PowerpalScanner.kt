package app.powermate.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val bonded: Boolean,
)

/** Finds Powerpals nearby. Callers must hold [BluetoothPermissions.required]. */
@SuppressLint("MissingPermission")
class PowerpalScanner(private val context: Context) {

    /** Powerpals already bonded to this phone, typically by the original app. They may not be advertising. */
    fun bondedPowerpals(): List<FoundDevice> =
        context.bluetoothAdapter()?.bondedDevices.orEmpty()
            .filter { isPowerpalName(it.name) }
            .map { FoundDevice(it.address, it.name, rssi = null, bonded = true) }

    /** Emits the growing list of Powerpals seen, until the collector stops. */
    fun scan(): Flow<List<FoundDevice>> = callbackFlow {
        val scanner = context.bluetoothAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is turned off"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, FoundDevice>()
        bondedPowerpals().forEach { found[it.address] = it }
        trySend(found.values.toList())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                val advertisesService = result.scanRecord?.serviceUuids?.any { it.uuid == PowerpalUuids.SERVICE } == true
                if (!advertisesService && !isPowerpalName(name)) return
                val address = result.device.address
                found[address] = FoundDevice(
                    address = address,
                    name = name ?: found[address]?.name,
                    rssi = result.rssi,
                    bonded = result.device.bondState == BluetoothDevice.BOND_BONDED,
                )
                trySend(found.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE })
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed (error $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        fun isPowerpalName(name: String?): Boolean = name?.trim()?.startsWith("powerpal", ignoreCase = true) == true
    }
}
