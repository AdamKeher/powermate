package app.powermate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.powermate.ble.PowerpalProtocol
import app.powermate.ble.PowerpalProtocol.BufferRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class PairedDevice(
    val address: String,
    val name: String?,
    val pairingCode: Int,
) {
    /** Powerpal's cloud device ID, taken from the advertised name. */
    val serial: String? get() = PowerpalProtocol.serialFromName(name)
}

data class AppSettings(
    val device: PairedDevice? = null,
    val pulsesPerKwh: Double = DEFAULT_PULSES_PER_KWH,
    val tariff: Tariff = Tariff(),
    val backgroundSync: Boolean = true,
    val lastSyncMillis: Long = 0,
    val battery: Int? = null,
    val deviceInfo: Map<String, String> = emptyMap(),
    val ledSensitivity: String? = null,
    val apiKey: String? = null,
    val bufferFirst: Long = 0,
    val bufferLast: Long = 0,
    /**
     * Readings the Powerpal already held when it was paired aren't downloaded unless asked for.
     * [earlierEnd] is the newest of them still to fetch (0 when none), [earlierTop] where they began.
     */
    val earlierEnd: Long = 0,
    val earlierTop: Long = 0,
    val earlierRequested: Boolean = false,
    val earlierDismissed: Boolean = false,
    /** Australian postcode: picks the wholesale price region and the plans on offer. */
    val postcode: String? = null,
    /** Personal Amber Electric API token, for Amber customers' own prices. */
    val amberToken: String? = null,
    /** How many days of recent readings plan comparison treats as typical, before scaling to a year. */
    val comparisonDays: Int = 365,
    val alerts: AlertSettings = AlertSettings(),
    /** Keep a live connection with an ongoing notification, so usage alerts work in the background. */
    val backgroundLive: Boolean = false,
    /** Battery level already warned about, so the warning isn't repeated. */
    val batteryAlertedAt: Int? = null,
) {
    /** Earlier readings are still on the device and haven't been fetched. */
    val earlierAvailable: Boolean get() = earlierEnd > 0 && bufferFirst in 1..earlierEnd

    val earlierDays: Double get() = if (earlierAvailable) (earlierEnd - bufferFirst) / 86_400.0 else 0.0

    /** Share of the earlier readings fetched so far. */
    val earlierProgress: Float
        get() = if (earlierTop <= bufferFirst) 0f else ((earlierTop - earlierEnd).toFloat() / (earlierTop - bufferFirst)).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_PULSES_PER_KWH = 1000.0
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val deviceAddress = stringPreferencesKey("device_address")
        val deviceName = stringPreferencesKey("device_name")
        val pairingCode = intPreferencesKey("pairing_code")
        val pulsesPerKwh = doublePreferencesKey("pulses_per_kwh")
        val tariff = stringPreferencesKey("tariff")
        val backgroundSync = booleanPreferencesKey("background_sync")
        val lastSync = longPreferencesKey("last_sync")
        val syncCursor = longPreferencesKey("sync_cursor")
        val battery = intPreferencesKey("battery")
        val deviceInfo = stringPreferencesKey("device_info")
        val ledSensitivity = stringPreferencesKey("led_sensitivity")
        val apiKey = stringPreferencesKey("api_key")
        val bufferFirst = longPreferencesKey("buffer_first")
        val bufferLast = longPreferencesKey("buffer_last")
        val earlierEnd = longPreferencesKey("earlier_end")
        val earlierTop = longPreferencesKey("earlier_top")
        val earlierRequested = booleanPreferencesKey("earlier_requested")
        val earlierDismissed = booleanPreferencesKey("earlier_dismissed")
        val postcode = stringPreferencesKey("postcode")
        val amberToken = stringPreferencesKey("amber_token")
        val comparisonDays = intPreferencesKey("comparison_days")
        val alerts = stringPreferencesKey("alerts")
        val backgroundLive = booleanPreferencesKey("background_live")
        val batteryAlertedAt = intPreferencesKey("battery_alerted_at")
    }

    private val preferences: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = preferences.map { p ->
        val address = p[Keys.deviceAddress]
        val code = p[Keys.pairingCode]
        AppSettings(
            device = if (address != null && code != null) PairedDevice(address, p[Keys.deviceName], code) else null,
            pulsesPerKwh = p[Keys.pulsesPerKwh] ?: AppSettings.DEFAULT_PULSES_PER_KWH,
            tariff = Tariff.fromJson(p[Keys.tariff]),
            backgroundSync = p[Keys.backgroundSync] ?: true,
            lastSyncMillis = p[Keys.lastSync] ?: 0,
            battery = p[Keys.battery],
            deviceInfo = decodeMap(p[Keys.deviceInfo]),
            ledSensitivity = p[Keys.ledSensitivity],
            apiKey = p[Keys.apiKey],
            bufferFirst = p[Keys.bufferFirst] ?: 0,
            bufferLast = p[Keys.bufferLast] ?: 0,
            earlierEnd = p[Keys.earlierEnd] ?: 0,
            earlierTop = p[Keys.earlierTop] ?: 0,
            earlierRequested = p[Keys.earlierRequested] ?: false,
            earlierDismissed = p[Keys.earlierDismissed] ?: false,
            postcode = p[Keys.postcode],
            amberToken = p[Keys.amberToken],
            comparisonDays = p[Keys.comparisonDays] ?: 365,
            alerts = AlertSettings.fromJson(p[Keys.alerts]),
            backgroundLive = p[Keys.backgroundLive] ?: false,
            batteryAlertedAt = p[Keys.batteryAlertedAt],
        )
    }

    suspend fun setPostcode(postcode: String?) = context.dataStore.edit { p ->
        if (postcode.isNullOrBlank()) p.remove(Keys.postcode) else p[Keys.postcode] = postcode.trim()
    }

    suspend fun setComparisonDays(days: Int) = context.dataStore.edit { it[Keys.comparisonDays] = days }

    suspend fun setAmberToken(token: String?) = context.dataStore.edit { p ->
        if (token.isNullOrBlank()) p.remove(Keys.amberToken) else p[Keys.amberToken] = token.trim()
    }

    suspend fun setAlerts(alerts: AlertSettings) = context.dataStore.edit { it[Keys.alerts] = alerts.toJson() }

    suspend fun setBackgroundLive(enabled: Boolean) = context.dataStore.edit { it[Keys.backgroundLive] = enabled }

    suspend fun setBatteryAlertedAt(level: Int?) = context.dataStore.edit { p ->
        if (level == null) p.remove(Keys.batteryAlertedAt) else p[Keys.batteryAlertedAt] = level
    }

    suspend fun current(): AppSettings = settings.first()

    /**
     * Saves a newly paired Powerpal, forgetting everything learned from any previous one. Automatic
     * syncing starts after the newest reading in [buffer]; anything older is offered separately.
     */
    suspend fun savePairedDevice(device: PairedDevice, pulsesPerKwh: Double, buffer: BufferRange?) {
        context.dataStore.edit { p ->
            clearDeviceState(p)
            p[Keys.deviceAddress] = device.address
            device.name?.let { p[Keys.deviceName] = it }
            p[Keys.pairingCode] = device.pairingCode
            p[Keys.pulsesPerKwh] = pulsesPerKwh
            if (buffer != null && !buffer.isEmpty) {
                p[Keys.bufferFirst] = buffer.first
                p[Keys.bufferLast] = buffer.last
                p[Keys.syncCursor] = buffer.last
                p[Keys.earlierEnd] = buffer.last
                p[Keys.earlierTop] = buffer.last
            }
        }
    }

    suspend fun forgetDevice() {
        context.dataStore.edit { p ->
            clearDeviceState(p)
            p.remove(Keys.deviceAddress)
            p.remove(Keys.deviceName)
            p.remove(Keys.pairingCode)
        }
    }

    private fun clearDeviceState(p: androidx.datastore.preferences.core.MutablePreferences) {
        p.remove(Keys.syncCursor)
        p.remove(Keys.lastSync)
        p.remove(Keys.battery)
        p.remove(Keys.deviceInfo)
        p.remove(Keys.ledSensitivity)
        p.remove(Keys.apiKey)
        p.remove(Keys.bufferFirst)
        p.remove(Keys.bufferLast)
        p.remove(Keys.earlierEnd)
        p.remove(Keys.earlierTop)
        p.remove(Keys.earlierRequested)
        p.remove(Keys.earlierDismissed)
    }

    suspend fun setPulsesPerKwh(value: Double) = context.dataStore.edit { it[Keys.pulsesPerKwh] = value }

    suspend fun setTariff(tariff: Tariff) = context.dataStore.edit { it[Keys.tariff] = tariff.toJson() }

    suspend fun setBackgroundSync(enabled: Boolean) = context.dataStore.edit { it[Keys.backgroundSync] = enabled }

    /** Timestamp of the newest device record already stored; history requests resume after it. */
    suspend fun syncCursor(): Long = preferences.first()[Keys.syncCursor] ?: 0

    suspend fun setSyncCursor(timestamp: Long) = context.dataStore.edit { it[Keys.syncCursor] = timestamp }

    suspend fun markSynced(millis: Long) = context.dataStore.edit { it[Keys.lastSync] = millis }

    suspend fun setBattery(level: Int) = context.dataStore.edit { it[Keys.battery] = level }

    suspend fun setDeviceDetails(info: Map<String, String>, ledSensitivity: String?, apiKey: String?) =
        context.dataStore.edit { p ->
            p[Keys.deviceInfo] = JSONObject(info).toString()
            if (ledSensitivity != null) p[Keys.ledSensitivity] = ledSensitivity
            if (apiKey != null) p[Keys.apiKey] = apiKey
        }

    suspend fun setEarlierRequested(requested: Boolean) = context.dataStore.edit { p ->
        p[Keys.earlierRequested] = requested
        if (requested) p[Keys.earlierDismissed] = false
    }

    suspend fun dismissEarlier() = context.dataStore.edit { it[Keys.earlierDismissed] = true }

    /**
     * Offers everything still on the device again, newest first, down from the newest reading
     * already synced. Re-fetched minutes simply replace what's stored.
     */
    suspend fun reofferAllReadings() = context.dataStore.edit { p ->
        val end = p[Keys.syncCursor] ?: p[Keys.bufferLast] ?: 0
        p[Keys.earlierEnd] = end
        p[Keys.earlierTop] = end
        p[Keys.earlierDismissed] = false
    }

    /** After stored readings are deleted: sync new ones from now on, and offer the rest again. */
    suspend fun restartHistory() = context.dataStore.edit { p ->
        val last = p[Keys.bufferLast] ?: 0
        p[Keys.syncCursor] = last
        p[Keys.earlierEnd] = last
        p[Keys.earlierTop] = last
        p[Keys.earlierRequested] = false
        p[Keys.earlierDismissed] = false
    }

    /** Records that everything newer than [end] has been fetched; 0 means all done. */
    suspend fun setEarlierEnd(end: Long) = context.dataStore.edit { p ->
        p[Keys.earlierEnd] = end
        if (end == 0L) p[Keys.earlierRequested] = false
    }

    suspend fun setBufferRange(first: Long, last: Long) = context.dataStore.edit { p ->
        p[Keys.bufferFirst] = first
        p[Keys.bufferLast] = last
    }

    private fun decodeMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            root.keys().asSequence().associateWith { root.optString(it) }
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }
}
