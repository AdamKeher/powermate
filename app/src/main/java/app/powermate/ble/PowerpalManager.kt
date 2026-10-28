package app.powermate.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.powermate.ble.PowerpalProtocol.BufferRange
import app.powermate.ble.PowerpalUuids.BATTERY_LEVEL
import app.powermate.ble.PowerpalUuids.BATTERY_SERVICE
import app.powermate.ble.PowerpalUuids.SERVICE
import app.powermate.data.Reading
import app.powermate.data.ReadingDao
import app.powermate.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class LinkStatus {
    IDLE,
    CONNECTING,

    /** Out of range (or switched off): Android will reconnect by itself when it reappears. */
    WAITING,
    PAIRING,
    SYNCING,
    LIVE,

    /** Needs the user: Bluetooth off, permission missing, pairing code rejected and so on. */
    ERROR,
}

data class LinkState(
    val status: LinkStatus = LinkStatus.IDLE,
    val message: String? = null,
)

/** A history download running alongside live readings. */
data class Transfer(val kind: Kind, val progress: Float) {
    enum class Kind {
        /** New readings recorded while the app wasn't connected. */
        CATCH_UP,

        /** Readings from before PowerMate was paired, fetched only on request. */
        EARLIER,
    }
}

/** What pairing learned: the readings already on the device. */
data class PairingResult(val buffer: BufferRange)

/** One live-power report: the device's latest inter-pulse interval and when it arrived. */
data class LivePower(val intervalMs: Long, val receivedAtMillis: Long)

/** A failure worth showing to the user as is. */
class PowerpalException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns all conversations with the paired Powerpal. A single [Mutex] ensures only one connection
 * exists at a time, whether it's the foreground live session, a background sync or onboarding.
 *
 * Every session starts the same way: connect → write pairing code → set the clock → set the record
 * interval. A live session then subscribes to pulses straight away, so power shows within seconds,
 * and downloads history in the background: first any new readings since the last sync, then (if the
 * user asked) the readings the device held before it was paired, a slice at a time and newest first,
 * checking for new readings between slices.
 *
 * Connections are expected to come and go as the phone moves in and out of range. The device keeps
 * recording regardless, every slice of history is saved as it arrives, and a dropped live session
 * hands reconnection to Android's low-power background connect so it resumes as soon as possible.
 */
@SuppressLint("MissingPermission")
class PowerpalManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val readings: ReadingDao,
    private val log: DiagnosticLog,
) {
    private enum class Mode { PAIR, SYNC, LIVE }

    private val sessionMutex = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    private val _link = MutableStateFlow(LinkState())
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _live = MutableStateFlow<LivePower?>(null)
    val live: StateFlow<LivePower?> = _live.asStateFlow()

    private val _liveHistory = MutableStateFlow<List<LivePower>>(emptyList())

    /** Device details rarely change, so flaky links don't re-read them on every reconnect. */
    @Volatile private var detailsReadAt = 0L

    /** Live reports from the last [LIVE_HISTORY_MS], oldest first. */
    val liveHistory: StateFlow<List<LivePower>> = _liveHistory.asStateFlow()

    /** Asks a running live session to check for new records now, or to retry immediately after an error. */
    fun requestSync() {
        syncRequests.trySend(Unit)
    }

    /** Starts or stops downloading the readings the Powerpal held before it was paired. */
    suspend fun setEarlierHistoryRequested(requested: Boolean) {
        settings.setEarlierRequested(requested)
        if (!requested && _transfer.value?.kind == Transfer.Kind.EARLIER) _transfer.value = null
        log.add(if (requested) "Earlier readings requested" else "Earlier readings download stopped")
        requestSync()
    }

    /**
     * Connects with a candidate pairing code and confirms the Powerpal accepts it. Returns the
     * readings the device already holds, so they can be offered rather than auto-downloaded.
     */
    suspend fun pair(address: String, pairingCode: Int): PairingResult = withContext(Dispatchers.Default) {
        sessionMutex.withLock {
            try {
                checkNotNull(runSession(address, pairingCode, Mode.PAIR))
            } finally {
                _link.value = LinkState()
            }
        }
    }

    /**
     * Connects once, downloads any new history (and earlier history, if requested) and disconnects.
     * Used by the background worker. Returns false if it could not reach the device.
     */
    suspend fun syncOnce(): Boolean = withContext(Dispatchers.Default) {
        val device = settings.current().device ?: return@withContext true
        // A live session already keeps history up to date.
        if (!sessionMutex.tryLock()) return@withContext true
        try {
            runSession(device.address, device.pairingCode, Mode.SYNC)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add("Background sync failed: ${e.message}")
            false
        } finally {
            _link.value = LinkState()
            _transfer.value = null
            sessionMutex.unlock()
        }
    }

    /** Keeps a live connection open, reconnecting with backoff, until the calling coroutine is cancelled. */
    suspend fun runLive(): Unit = withContext(Dispatchers.Default) {
        // Reconnect straight away when Bluetooth is switched back on.
        val bluetoothOn = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) requestSync()
            }
        }
        ContextCompat.registerReceiver(
            context,
            bluetoothOn,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        var backoffMs = INITIAL_BACKOFF_MS
        try {
            while (true) {
                val device = settings.current().device ?: return@withContext
                val started = SystemClock.elapsedRealtime()
                try {
                    sessionMutex.withLock { runSession(device.address, device.pairingCode, Mode.LIVE) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.add("Live session ended: ${e.message}")
                    // Dropped links are routine; only problems the user can fix are errors.
                    val bluetoothOff = e is GattDisconnectedException && e.status == GattClient.STATUS_BLUETOOTH_OFF
                    _link.value = if (e is GattException && !bluetoothOff) {
                        LinkState(LinkStatus.WAITING, "Reconnecting…")
                    } else {
                        LinkState(LinkStatus.ERROR, describe(e))
                    }
                }
                _transfer.value = null
                if (SystemClock.elapsedRealtime() - started > STABLE_SESSION_MS) backoffMs = INITIAL_BACKOFF_MS
                // A sync request (Retry, or Bluetooth coming back on) cuts the wait short.
                withTimeoutOrNull(backoffMs) { syncRequests.receive() }
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        } finally {
            runCatching { context.unregisterReceiver(bluetoothOn) }
            _link.value = LinkState()
            _transfer.value = null
        }
    }

    private suspend fun runSession(address: String, pairingCode: Int, mode: Mode): PairingResult? = coroutineScope {
        val adapter = context.bluetoothAdapter() ?: throw PowerpalException("This phone has no Bluetooth")
        if (!BluetoothPermissions.canConnect(context)) throw PowerpalException("Bluetooth permission hasn't been granted")
        if (!adapter.isEnabled) throw PowerpalException("Bluetooth is turned off")
        val device = adapter.getRemoteDevice(address)

        _link.value = LinkState(LinkStatus.CONNECTING)
        log.add("Connecting to $address for ${mode.name.lowercase()}")
        val client = GattClient.connect(
            context,
            device,
            log::add,
            waitForDevice = mode == Mode.LIVE,
            onWaiting = { _link.value = LinkState(LinkStatus.WAITING) },
        )
        try {
            // No MTU request: every Powerpal payload fits the default 20 bytes, and an exchange the
            // device mishandles would stall the whole session.
            client.discoverServices()
            if (!client.has(SERVICE, PowerpalUuids.PAIRING_CODE)) {
                throw PowerpalException("That device doesn't look like a Powerpal")
            }

            _link.value = LinkState(LinkStatus.PAIRING)
            authenticate(client, device, pairingCode)
            // The device records every minute, so a stale newest record hints at a wrong clock.
            // Only logged: on the one device seen so far the clock was right (verified against
            // Powerpal's cloud), so there's no evidence yet for how to correct it.
            val beforeClock = readBufferRange(client)
            val lag = epochSeconds() - beforeClock.last
            if (!beforeClock.isEmpty && kotlin.math.abs(lag) > PowerpalProtocol.CLOCK_WARNING_SECONDS) {
                log.add("Newest stored reading is ${describeAge(lag)} from the phone's clock")
            }
            setClock(client)
            setRecordInterval(client)

            if (mode == Mode.PAIR) {
                readDeviceDetails(client)
                log.add("Pairing confirmed; device holds ${describe(beforeClock)}")
                return@coroutineScope PairingResult(beforeClock)
            }

            val tracker = StreamTracker()
            val records = client.subscribe(SERVICE, PowerpalUuids.MEASUREMENT)
            launch { collectRecords(records, tracker) }

            if (mode == Mode.LIVE) {
                if (client.has(SERVICE, PowerpalUuids.PULSE)) {
                    val pulses = client.subscribe(SERVICE, PowerpalUuids.PULSE)
                    launch { for (packet in pulses) onPulse(packet) }
                } else {
                    log.add("No pulse characteristic (older firmware?); live power unavailable")
                }
                _link.value = LinkState(LinkStatus.LIVE)
            } else {
                _link.value = LinkState(LinkStatus.SYNCING)
            }
            if (detailsReadAt == 0L || SystemClock.elapsedRealtime() - detailsReadAt > DETAILS_INTERVAL_MS) {
                readDeviceDetails(client)
            }

            var lastBatteryRead = SystemClock.elapsedRealtime()
            while (true) {
                val range = readBufferRange(client)
                settings.setBufferRange(range.first, range.last)
                catchUp(client, tracker, range)
                settings.markSynced(System.currentTimeMillis())

                // One day of earlier history per pass, so new readings never wait behind a long download.
                if (fetchEarlierDay(client, tracker, range)) continue
                if (mode == Mode.SYNC) return@coroutineScope null

                if (SystemClock.elapsedRealtime() - lastBatteryRead > BATTERY_INTERVAL_MS) {
                    readBattery(client)
                    lastBatteryRead = SystemClock.elapsedRealtime()
                }
                withTimeoutOrNull(POLL_INTERVAL_MS) {
                    select {
                        syncRequests.onReceive {}
                        client.disconnected.onAwait {}
                    }
                }
                if (!client.isConnected) throw GattDisconnectedException(client.disconnected.await())
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            // Closes the notification channels, which ends the collectors launched above.
            client.close()
        }
    }

    private suspend fun authenticate(client: GattClient, device: BluetoothDevice, pairingCode: Int) {
        val payload = PowerpalProtocol.encodePairingCode(pairingCode)
        try {
            client.write(SERVICE, PowerpalUuids.PAIRING_CODE, payload)
        } catch (e: GattStatusException) {
            if (e.status !in SECURITY_STATUSES) {
                throw PowerpalException("The Powerpal rejected the pairing code (status ${e.status})", e)
            }
            log.add("Pairing code write needs an encrypted link; bonding with the phone")
            if (!device.awaitBond(context)) throw PowerpalException("Bluetooth pairing with the Powerpal failed", e)
            client.write(SERVICE, PowerpalUuids.PAIRING_CODE, payload)
        }
        log.add("Pairing code accepted")
    }

    /** The Powerpal forgets the time on power loss and stamps records with garbage until it is told. */
    private suspend fun setClock(client: GattClient) {
        val previous = optional("read clock") { PowerpalProtocol.decodeU32(client.read(SERVICE, PowerpalUuids.TIME)) }
        val now = epochSeconds()
        client.write(SERVICE, PowerpalUuids.TIME, PowerpalProtocol.encodeU32(now))
        log.add("Clock set to ${Instant.ofEpochSecond(now)}" + (previous?.let { " (device said ${it - now}s offset)" } ?: ""))
    }

    /** Writing the interval, even unchanged, is what makes some firmware start sending records. */
    private suspend fun setRecordInterval(client: GattClient) {
        val previous = optional("read batch size") {
            PowerpalProtocol.decodeU32(client.read(SERVICE, PowerpalUuids.READING_BATCH_SIZE))
        }
        client.write(
            SERVICE,
            PowerpalUuids.READING_BATCH_SIZE,
            PowerpalProtocol.encodeU32(PowerpalProtocol.RECORD_MINUTES.toLong()),
        )
        log.add("Record interval was ${previous ?: "?"} min, now ${PowerpalProtocol.RECORD_MINUTES} min")
    }

    private suspend fun readDeviceDetails(client: GattClient) {
        val info = LinkedHashMap<String, String>()
        for ((label, uuid) in PowerpalUuids.DEVICE_INFO_FIELDS) {
            if (!client.has(PowerpalUuids.DEVICE_INFO_SERVICE, uuid)) continue
            optional("read $label") { client.read(PowerpalUuids.DEVICE_INFO_SERVICE, uuid) }
                ?.let { bytes -> bytes.toString(Charsets.UTF_8).trim { it.isWhitespace() || it == '\u0000' } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { info[label] = it }
        }
        val led = if (client.has(SERVICE, PowerpalUuids.LED_SENSITIVITY)) {
            optional("read LED sensitivity") { client.read(SERVICE, PowerpalUuids.LED_SENSITIVITY) }
                ?.let { PowerpalProtocol.toHex(it) }
        } else {
            null
        }
        val apiKey = if (client.has(SERVICE, PowerpalUuids.API_KEY)) {
            optional("read API key") { client.read(SERVICE, PowerpalUuids.API_KEY) }
                ?.takeIf { it.size == 16 }
                ?.let { PowerpalProtocol.formatApiKey(it) }
        } else {
            null
        }
        log.add("Device info $info, LED sensitivity ${led ?: "?"}")
        settings.setDeviceDetails(info, led, apiKey)
        readBattery(client)
        detailsReadAt = SystemClock.elapsedRealtime()
    }

    private suspend fun readBattery(client: GattClient) {
        if (!client.has(BATTERY_SERVICE, BATTERY_LEVEL)) return
        val bytes = optional("read battery") { client.read(BATTERY_SERVICE, BATTERY_LEVEL) } ?: return
        if (bytes.isNotEmpty()) {
            val level = bytes[0].toInt() and 0xFF
            log.add("Battery $level%")
            settings.setBattery(level)
        }
    }

    private suspend fun readBufferRange(client: GattClient): BufferRange {
        val bytes = client.read(SERVICE, PowerpalUuids.FIRST_REC)
        return PowerpalProtocol.parseBufferRange(bytes)
            ?: throw PowerpalException("Unexpected reply about stored readings (${PowerpalProtocol.toHex(bytes)})")
    }

    /** Downloads every record newer than the sync cursor. */
    private suspend fun catchUp(client: GattClient, tracker: StreamTracker, range: BufferRange) {
        if (range.isEmpty) return
        var cursor = settings.syncCursor()
        if (cursor > range.last + CLOCK_SLACK_SECONDS) {
            log.add("Sync position is ahead of the device clock; re-reading its buffer")
            cursor = 0
        }
        val origin = if (cursor == 0L) range.first else max(cursor + PowerpalProtocol.RECORD_SECONDS, range.first)
        if (origin > range.last) return

        val span = (range.last - origin).coerceAtLeast(1)
        val showProgress = span > PROGRESS_THRESHOLD_SECONDS
        log.add("Catching up on readings ${Instant.ofEpochSecond(origin)} → ${Instant.ofEpochSecond(range.last)}")
        val received = download(client, tracker, origin, range.last) { last ->
            settings.setSyncCursor(last)
            if (showProgress) _transfer.value = Transfer(Transfer.Kind.CATCH_UP, (last - origin).toFloat() / span)
        }
        if (showProgress) _transfer.value = null
        log.add("Caught up: $received readings")
    }

    /**
     * Fetches the newest not-yet-downloaded day of readings from before pairing, if the user asked
     * for them. Returns true if it fetched something, so the caller can come straight back for more.
     */
    private suspend fun fetchEarlierDay(client: GattClient, tracker: StreamTracker, range: BufferRange): Boolean {
        val current = settings.current()
        if (!current.earlierRequested || range.isEmpty) return false
        val end = min(current.earlierEnd, range.last)
        if (end < range.first) {
            settings.setEarlierEnd(0)
            _transfer.value = null
            log.add("Earlier readings: all downloaded")
            return false
        }
        val start = max(range.first, end - EARLIER_SLICE_SECONDS + PowerpalProtocol.RECORD_SECONDS)
        val top = current.earlierTop.coerceAtLeast(end)
        _transfer.value = Transfer(Transfer.Kind.EARLIER, ((top - end).toFloat() / (top - range.first).coerceAtLeast(1)))
        val received = download(client, tracker, start, end) {}
        log.add("Earlier readings ${Instant.ofEpochSecond(start)} → ${Instant.ofEpochSecond(end)}: $received")
        settings.setEarlierEnd(if (start <= range.first) 0 else start - PowerpalProtocol.RECORD_SECONDS)
        if (start <= range.first) {
            _transfer.value = null
            log.add("Earlier readings: all downloaded")
        }
        return true
    }

    /**
     * Downloads records in `[from, to]`, a slice at a time. The device streams each slice over the
     * measurement characteristic, where [collectRecords] stores it. A slice is done once its last
     * record arrives or the stream goes quiet, since the buffer can have gaps; a stream that stops
     * early is resumed after the newest record received. [afterSlice] gets the last record covered.
     *
     * The device doesn't keep a timestamp per record: it labels them `start + n × 60 s` from the
     * requested start. So every request must start exactly one record after the last, or the same
     * minute comes back again under a new label.
     */
    private suspend fun download(
        client: GattClient,
        tracker: StreamTracker,
        from: Long,
        to: Long,
        afterSlice: suspend (Long) -> Unit,
    ): Int {
        var start = from
        var received = 0
        while (start <= to) {
            val end = min(to, start + SLICE_SECONDS - PowerpalProtocol.RECORD_SECONDS)
            tracker.begin()
            client.write(SERVICE, PowerpalUuids.MEASUREMENT_ACCESS, PowerpalProtocol.encodeTimeRange(start, end))
            awaitSlice(client, tracker, end)
            received += tracker.received
            val last = if (tracker.maxTimestamp >= start) tracker.maxTimestamp else end
            afterSlice(last)
            start = last + PowerpalProtocol.RECORD_SECONDS
        }
        return received
    }

    /** Waits for a slice to finish streaming. If the link drops, returns at once so partial progress is kept. */
    private suspend fun awaitSlice(client: GattClient, tracker: StreamTracker, end: Long) {
        while (true) {
            delay(STREAM_CHECK_MS)
            if (tracker.maxTimestamp >= end || !client.isConnected) return
            val quietLimit = if (tracker.received == 0) FIRST_RECORD_TIMEOUT_MS else STREAM_IDLE_TIMEOUT_MS
            if (SystemClock.elapsedRealtime() - tracker.lastActivity > quietLimit) return
        }
    }

    /** Stores records as they arrive, batching whatever has queued up into one transaction. */
    private suspend fun collectRecords(channel: ReceiveChannel<ByteArray>, tracker: StreamTracker) {
        for (first in channel) {
            val packets = mutableListOf(first)
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                packets += next
            }
            val records = packets.flatMap { packet ->
                if (tracker.shouldLogPacket()) log.add("Record ${PowerpalProtocol.toHex(packet)}")
                PowerpalProtocol.parseMeasurements(packet)
            }
            val latestPlausible = epochSeconds() + CLOCK_SLACK_SECONDS
            val valid = records.filter { it.timestamp in PowerpalProtocol.MIN_VALID_TIMESTAMP..latestPlausible }
            if (valid.size < records.size) log.add("Ignored ${records.size - valid.size} record(s) with an impossible timestamp")
            if (valid.isNotEmpty()) {
                readings.storeDeviceReadings(
                    valid.map {
                        Reading(PowerpalProtocol.minuteOf(it.timestamp), it.pulses, PowerpalProtocol.RECORD_SECONDS, Reading.SOURCE_DEVICE)
                    },
                )
            }
            tracker.onRecords(records)
        }
    }

    private fun onPulse(packet: ByteArray) {
        if (packet.size < 4) {
            log.add("Short pulse packet ${PowerpalProtocol.toHex(packet)}")
            return
        }
        val intervalMs = PowerpalProtocol.decodeU32(packet)
        if (intervalMs == 0L) return
        val now = System.currentTimeMillis()
        val report = LivePower(intervalMs, now)
        _live.value = report
        _liveHistory.update { history -> history.dropWhile { it.receivedAtMillis < now - LIVE_HISTORY_MS } + report }
    }

    /**
     * Debug builds only: pretends a Powerpal is connected and reports a plausible household load
     * (a base load, a kettle ten minutes ago, an oven now), so the live screens can be worked on and
     * photographed without the device. The last [LIVE_HISTORY_MS] is filled in for the trend.
     */
    suspend fun runMockLive(pulsesPerKwh: Double) {
        val random = Random(7)
        fun load(minutesAgo: Double): Double {
            val base = 450 + 80 * sin(minutesAgo)
            val kettle = if (minutesAgo in 9.0..12.0) 2_200.0 else 0.0
            val oven = if (minutesAgo < 5.0) 1_700.0 else 0.0
            return base + kettle + oven + random.nextDouble(-60.0, 60.0)
        }
        fun interval(watts: Double) = (3.6e9 / (pulsesPerKwh * watts)).toLong().coerceAtLeast(1)
        try {
            _link.value = LinkState(LinkStatus.LIVE)
            val start = System.currentTimeMillis()
            val history = mutableListOf<LivePower>()
            var t = start - LIVE_HISTORY_MS
            while (t < start) {
                val gap = interval(load((start - t) / 60_000.0))
                history += LivePower(gap, t)
                t += gap
            }
            _liveHistory.value = history
            while (true) {
                val report = LivePower(interval(load(0.0)), System.currentTimeMillis())
                _live.value = report
                _liveHistory.update { h -> h.dropWhile { it.receivedAtMillis < report.receivedAtMillis - LIVE_HISTORY_MS } + report }
                delay(report.intervalMs)
            }
        } finally {
            _live.value = null
            _link.value = LinkState()
        }
    }

    /** Runs an optional read, treating a refusal by the device as "not available". */
    private suspend fun <T> optional(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: GattStatusException) {
        log.add("Couldn't $what: ${e.message}")
        null
    }

    private fun describeAge(seconds: Long): String {
        val abs = kotlin.math.abs(seconds)
        val amount = when {
            abs >= 86_400 -> String.format(java.util.Locale.ROOT, "%.1f days", abs / 86_400.0)
            abs >= 3_600 -> String.format(java.util.Locale.ROOT, "%.1f hours", abs / 3_600.0)
            else -> "${abs / 60} minutes"
        }
        return "$amount ${if (seconds > 0) "behind" else "ahead of"}"
    }

    private fun describe(range: BufferRange): String =
        if (range.isEmpty) "no readings" else "readings from ${Instant.ofEpochSecond(range.first)} to ${Instant.ofEpochSecond(range.last)}"

    /** Tracks progress of the slice currently being streamed. */
    private class StreamTracker {
        @Volatile var maxTimestamp = 0L
            private set
        @Volatile var received = 0
            private set
        @Volatile var lastActivity = 0L
            private set
        private val packetsToLog = AtomicInteger(0)

        fun begin() {
            maxTimestamp = 0
            received = 0
            lastActivity = SystemClock.elapsedRealtime()
            packetsToLog.set(RAW_PACKETS_LOGGED_PER_SLICE)
        }

        fun onRecords(records: List<PowerpalProtocol.Measurement>) {
            if (records.isNotEmpty()) maxTimestamp = max(maxTimestamp, records.maxOf { it.timestamp })
            received += records.size
            lastActivity = SystemClock.elapsedRealtime()
        }

        fun shouldLogPacket(): Boolean = packetsToLog.getAndDecrement() > 0
    }

    companion object {
        const val LIVE_HISTORY_MS = 15 * 60 * 1000L

        private const val POLL_INTERVAL_MS = 60_000L
        private const val BATTERY_INTERVAL_MS = 30 * 60 * 1000L
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val STABLE_SESSION_MS = 60_000L

        /** One day of one-minute records per request keeps each stream short and resumable. */
        private const val SLICE_SECONDS = 86_400L

        /**
         * Earlier history is fetched newest first, so a slice cut short by a dropout is fetched again
         * in full. Smaller slices waste less when the link is flaky.
         */
        private const val EARLIER_SLICE_SECONDS = 6 * 3_600L
        private const val DETAILS_INTERVAL_MS = 6 * 3_600_000L
        private const val PROGRESS_THRESHOLD_SECONDS = 30 * 60L
        private const val CLOCK_SLACK_SECONDS = 86_400L
        private const val STREAM_CHECK_MS = 200L
        private const val FIRST_RECORD_TIMEOUT_MS = 8_000L
        private const val STREAM_IDLE_TIMEOUT_MS = 4_000L
        private const val RAW_PACKETS_LOGGED_PER_SLICE = 3

        /** Insufficient authentication / authorization / encryption, and the stack's generic auth failure. */
        private val SECURITY_STATUSES = setOf(5, 8, 15, 137)

        private fun epochSeconds(): Long = System.currentTimeMillis() / 1000

        fun describe(e: Throwable): String = when (e) {
            is PowerpalException -> e.message ?: "Something went wrong"
            is GattDisconnectedException ->
                if (e.status == GattClient.STATUS_BLUETOOTH_OFF) "Bluetooth is turned off" else "Lost connection to the Powerpal"
            is GattStatusException -> "The Powerpal refused a request (status ${e.status})"
            is GattException -> e.message ?: "Bluetooth error"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
