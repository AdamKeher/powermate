package com.aksevenfour.powermate.data

import android.content.Context
import android.net.Uri
import com.aksevenfour.powermate.ble.DiagnosticLog
import com.aksevenfour.powermate.ble.PowerpalProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** The outcome of asking Powerpal's cloud to resend a pairing code to the device's registered owner. */
sealed interface PairingCodeRecovery {
    /** The cloud has sent the code to the owner's registered email and mobile. */
    data object Sent : PairingCodeRecovery

    /** The device isn't registered, so there's no owner to send the code to. */
    data object NotRegistered : PairingCodeRecovery

    data class Failed(val message: String) : PairingCodeRecovery
}

/** Moving readings in and out of the app: CSV export, and an import from Powerpal's cloud. */
class DataTools(
    private val context: Context,
    private val readings: ReadingDao,
    private val log: DiagnosticLog,
) {
    /** Writes every stored reading to [uri] and returns how many were written. */
    suspend fun exportCsv(uri: Uri, pulsesPerKwh: Double): Int = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        var count = 0
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file")
        stream.bufferedWriter().use { out ->
            out.appendLine("timestamp_utc,local_time,duration_seconds,pulses,kwh,source")
            var after = Long.MIN_VALUE
            while (true) {
                val page = readings.page(after, PAGE_SIZE)
                if (page.isEmpty()) break
                for (r in page) {
                    val instant = Instant.ofEpochSecond(r.timestamp)
                    out.append(instant.toString()).append(',')
                        .append(LocalDateTime.ofInstant(instant, zone).toString()).append(',')
                        .append(r.durationSec.toString()).append(',')
                        .append(r.pulses.toString()).append(',')
                        .append(String.format(Locale.ROOT, "%.4f", PowerpalProtocol.kwh(r.pulses.toLong(), pulsesPerKwh))).append(',')
                        .append(if (r.source == Reading.SOURCE_CLOUD) "cloud" else "device")
                        .append('\n')
                    count++
                }
                after = page.last().timestamp
            }
        }
        count
    }

    /**
     * Copies minute-by-minute history from Powerpal's cloud (readings.powerpal.net), for as long as
     * it stays online. Minutes the device already supplied are kept. Everything else the cloud has
     * replaces what's stored for the same stretch, including coarser totals from earlier imports,
     * so running it again is safe. Returns the number of minutes imported.
     */
    suspend fun importFromCloud(
        deviceId: String,
        apiKey: String,
        onProgress: (Float) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(deviceId.trim(), "UTF-8")
        val device = JSONObject(get("$CLOUD_BASE/device/$id", apiKey.trim()))
        // Whole hours, so totals stored by earlier hourly imports are replaced in one piece.
        val first = device.getLong("first_reading_timestamp") / 3_600 * 3_600
        val last = device.getLong("last_reading_timestamp")
        log.add("Cloud holds readings ${Instant.ofEpochSecond(first)} → ${Instant.ofEpochSecond(last)}")

        var imported = 0
        var chunkStart = first
        while (chunkStart <= last) {
            onProgress((chunkStart - first).toFloat() / (last - first + 1))
            // The API's end is inclusive.
            val chunkEnd = min(last, chunkStart + CLOUD_CHUNK_SECONDS - 1)
            val rows = JSONArray(
                get("$CLOUD_BASE/meter_reading/$id?start=$chunkStart&end=$chunkEnd&sample=1", apiKey.trim()),
            )
            val batch = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                Reading(row.getLong("timestamp") / 60 * 60, row.getInt("pulses"), 60, Reading.SOURCE_CLOUD)
            }
            imported += readings.storeCloudReadings(chunkStart, chunkEnd, batch)
            chunkStart = chunkEnd + 1
        }
        log.add("Imported $imported minutes from the cloud")
        imported
    }

    /**
     * Asks Powerpal's cloud to send a device's pairing code to the owner who registered it, the way
     * the official app's "I can't find my pairing code" link does. The code only ever goes to the
     * registered email and mobile, never back to us, so this can't be used to read someone else's code.
     * [serial] is the 8-char hex from the Bluetooth name (see [PowerpalProtocol.serialFromName]).
     */
    suspend fun recoverPairingCode(serial: String): PairingCodeRecovery = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(serial.trim(), "UTF-8")
        val connection = URL("$DEVICES_BASE/energy_bridges/$id/pairing_code_recovery").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            // The server requires a Content-Length even with no body (411 otherwise); send an empty one.
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            val code = connection.responseCode
            log.add("Pairing code recovery for $serial returned HTTP $code")
            when {
                code in 200..299 -> PairingCodeRecovery.Sent
                code == 404 -> PairingCodeRecovery.NotRegistered
                else -> PairingCodeRecovery.Failed("Powerpal's cloud returned HTTP $code")
            }
        } catch (e: IOException) {
            PairingCodeRecovery.Failed(e.message ?: "Couldn't reach Powerpal's cloud")
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Authorization", apiKey)
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(
                    when (code) {
                        401, 403 -> "Powerpal's cloud rejected the API key"
                        404 -> "Powerpal's cloud doesn't know that device ID"
                        else -> "Powerpal's cloud returned HTTP $code"
                    },
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PAGE_SIZE = 5_000
        const val CLOUD_BASE = "https://readings.powerpal.net/api/v1"
        const val DEVICES_BASE = "https://devices.powerpal.net/api/v1"
        /** 30 days of minute rows (43,200) per request, under the API's 50,000-row limit. */
        const val CLOUD_CHUNK_SECONDS = 30 * 86_400L
    }
}
