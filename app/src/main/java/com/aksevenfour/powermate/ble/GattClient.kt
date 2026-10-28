package com.aksevenfour.powermate.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class GattException(message: String) : Exception(message)

class GattStatusException(val operation: String, val status: Int) :
    GattException("$operation failed (GATT status $status)")

class GattDisconnectedException(val status: Int) : GattException("Connection lost (status $status)")

/**
 * Suspending wrapper around [BluetoothGatt]. Android allows a single outstanding GATT operation, so
 * every operation is serialised through [opMutex] and resumed from the matching callback.
 *
 * A client is single use: once disconnected, make a new one with [connect]. Losing the link (out of
 * range, or Bluetooth switched off) fails any operation in progress with [GattDisconnectedException].
 * Callers must hold BLUETOOTH_CONNECT; [PowerpalManager] checks before creating one.
 */
@SuppressLint("MissingPermission")
class GattClient private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    private class OpResult(val status: Int, val value: ByteArray? = null)

    private val opMutex = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<OpResult>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null

    private val _disconnected = CompletableDeferred<Int>()

    /** Completes with the GATT status once the link drops or [close] is called. */
    val disconnected: Deferred<Int> get() = _disconnected

    val isConnected: Boolean get() = gatt != null && !_disconnected.isCompleted

    private val notifications = ConcurrentHashMap<UUID, Channel<ByteArray>>()

    /** Android doesn't always report a dropped link when Bluetooth is switched off, so watch for it. */
    private val adapterOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                log("Bluetooth switched off")
                markDisconnected(STATUS_BLUETOOTH_OFF)
            }
        }
    }
    @Volatile private var receiverRegistered = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.complete(Unit)
            } else {
                log("Link down (state $newState, status $status)")
                markDisconnected(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending?.complete(OpResult(status))
        }

        @Suppress("OVERRIDE_DEPRECATION") // Only called on Android 12 and older.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) pending?.complete(OpResult(status, characteristic.value))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pending?.complete(OpResult(status, value))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(OpResult(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(OpResult(status))
        }

        @Suppress("OVERRIDE_DEPRECATION") // Only called on Android 12 and older.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) deliver(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(characteristic.uuid, value)
        }
    }

    private fun deliver(uuid: UUID, value: ByteArray?) {
        if (value != null) notifications[uuid]?.trySend(value.copyOf())
    }

    private fun markDisconnected(status: Int) {
        val error = GattDisconnectedException(status)
        connectWaiter?.completeExceptionally(error)
        pending?.completeExceptionally(error)
        _disconnected.complete(status)
        // Closing (not cancelling) lets consumers drain anything already received.
        notifications.values.forEach { it.close() }
    }

    /**
     * Connects. With [autoConnect], Android watches for the device in the background (low power, no
     * timeout) and connects whenever it comes into range; [timeoutMs] null means wait indefinitely.
     */
    private suspend fun open(autoConnect: Boolean, timeoutMs: Long?) {
        ContextCompat.registerReceiver(
            context,
            adapterOff,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        try {
            gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw GattException("Android refused to start a connection")
            if (timeoutMs == null) waiter.await() else withTimeout(timeoutMs) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("Powerpal not found (is it in range?)")
        } finally {
            connectWaiter = null
        }
    }

    private suspend fun execute(
        operation: String,
        timeoutMs: Long = OP_TIMEOUT_MS,
        start: (BluetoothGatt) -> Boolean,
    ): OpResult = opMutex.withLock {
        val gatt = gatt
        if (gatt == null || _disconnected.isCompleted) throw GattDisconnectedException(-1)
        val result = CompletableDeferred<OpResult>()
        pending = result
        try {
            if (!start(gatt)) throw GattException("$operation could not be started")
            val outcome = try {
                withTimeout(timeoutMs) { result.await() }
            } catch (e: TimeoutCancellationException) {
                throw GattException("$operation timed out")
            }
            if (outcome.status != BluetoothGatt.GATT_SUCCESS) throw GattStatusException(operation, outcome.status)
            outcome
        } finally {
            pending = null
        }
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: throw GattException("Characteristic ${PowerpalUuids.nameOf(characteristic)} not found")

    fun has(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    suspend fun discoverServices() {
        execute("Service discovery", timeoutMs = 30_000) { it.discoverServices() }
    }

    /** No callback exists for this, so it's fire and forget. */
    fun requestConnectionPriority(priority: Int) {
        gatt?.requestConnectionPriority(priority)
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val target = characteristic(service, characteristic)
        return execute("Read ${PowerpalUuids.nameOf(characteristic)}") { it.readCharacteristic(target) }.value
            ?: ByteArray(0)
    }

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val target = characteristic(service, characteristic)
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        execute("Write ${PowerpalUuids.nameOf(characteristic)}") { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(target, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.writeType = writeType
                @Suppress("DEPRECATION")
                target.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(target)
            }
        }
    }

    /** Subscribes to a characteristic. The returned channel closes when the link drops. */
    suspend fun subscribe(service: UUID, characteristic: UUID): ReceiveChannel<ByteArray> {
        val target = characteristic(service, characteristic)
        val channel = notifications.getOrPut(characteristic) { Channel(Channel.UNLIMITED) }
        val gatt = gatt ?: throw GattDisconnectedException(-1)
        if (!gatt.setCharacteristicNotification(target, true)) {
            throw GattException("Could not enable notifications for ${PowerpalUuids.nameOf(characteristic)}")
        }
        val cccd = target.getDescriptor(PowerpalUuids.CCCD)
            ?: throw GattException("${PowerpalUuids.nameOf(characteristic)} has no notification descriptor")
        val indicateOnly = target.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            target.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val enable = if (indicateOnly) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        execute("Subscribe to ${PowerpalUuids.nameOf(characteristic)}") { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        return channel
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        markDisconnected(-1)
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { context.unregisterReceiver(adapterOff) }
        }
    }

    companion object {
        private const val OP_TIMEOUT_MS = 10_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 15_000L
        private const val DIRECT_ATTEMPTS = 2

        /** Reported when the link dropped because Bluetooth was switched off. */
        const val STATUS_BLUETOOTH_OFF = -2

        /**
         * Connects to [device]. Direct attempts come first since they're quickest when it's in range,
         * retrying the transient failures (usually status 133) Android BLE is known for. If those fail
         * and [waitForDevice] is set, [onWaiting] is called and Android is left to connect in the
         * background whenever the device reappears, for as long as the caller stays active.
         */
        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            log: (String) -> Unit,
            waitForDevice: Boolean,
            onWaiting: () -> Unit = {},
        ): GattClient {
            var lastError: GattException? = null
            for (attempt in 1..DIRECT_ATTEMPTS) {
                val client = GattClient(context, device, log)
                try {
                    client.open(autoConnect = false, timeoutMs = DIRECT_CONNECT_TIMEOUT_MS)
                    return client
                } catch (e: GattException) {
                    client.close()
                    lastError = e
                    log("Connect attempt $attempt failed: ${e.message}")
                    if (attempt < DIRECT_ATTEMPTS) delay(1_000L)
                }
            }
            if (!waitForDevice) throw lastError ?: GattException("Could not connect")

            log("Waiting for the Powerpal to come back into range")
            onWaiting()
            val client = GattClient(context, device, log)
            try {
                client.open(autoConnect = true, timeoutMs = null)
                log("Powerpal back in range")
                return client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }
    }
}
