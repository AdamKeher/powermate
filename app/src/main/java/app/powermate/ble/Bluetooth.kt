package app.powermate.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object BluetoothPermissions {
    /** Runtime permissions needed to scan for and talk to a Powerpal on this Android version. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun canScan(context: Context): Boolean = required.all { granted(context, it) }

    /** Connecting to a known address needs no location permission, only BLUETOOTH_CONNECT on 12+. */
    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

fun Context.isBluetoothOn(): Boolean = bluetoothAdapter()?.isEnabled == true

/**
 * Bonds with [this] device, letting Android show its pairing prompt, and waits for the outcome.
 * The Powerpal doesn't normally need an OS-level bond, but some phones insist before allowing writes.
 */
@SuppressLint("MissingPermission")
suspend fun BluetoothDevice.awaitBond(context: Context, timeoutMs: Long = 60_000): Boolean {
    if (bondState == BluetoothDevice.BOND_BONDED) return true
    val outcome = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (changed?.address != address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> outcome.complete(true)
                BluetoothDevice.BOND_NONE -> outcome.complete(false)
            }
        }
    }
    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        ContextCompat.RECEIVER_EXPORTED,
    )
    try {
        if (bondState != BluetoothDevice.BOND_BONDING && !createBond()) return false
        return withTimeoutOrNull(timeoutMs) { outcome.await() } ?: false
    } finally {
        context.unregisterReceiver(receiver)
    }
}
