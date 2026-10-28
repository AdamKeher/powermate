package app.powermate.prices

import app.powermate.ble.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Electricity prices for Victoria. Without an account it uses AEMO's public market data for the
 * VIC1 region (the wholesale spot price); with an Amber Electric API token it uses the customer's
 * own all-in Amber prices. Renewables come from Amber's public per-state feed either way.
 */
class PriceRepository(private val log: DiagnosticLog) {
    private val _outlook = MutableStateFlow<PriceOutlook?>(null)
    val outlook: StateFlow<PriceOutlook?> = _outlook.asStateFlow()
    private val mutex = Mutex()

    /** Fetches fresh prices unless the last fetch is younger than [maxAge]. */
    suspend fun refresh(amberToken: String?, maxAge: Duration = Duration.ofMinutes(4)): PriceOutlook = mutex.withLock {
        _outlook.value?.takeIf { Duration.between(it.fetchedAt, Instant.now()) < maxAge }?.let { return it }
        val fresh = withContext(Dispatchers.IO) {
            val renewables = runCatching { fetchRenewables() }.onFailure { log.add("Renewables unavailable: ${it.message}") }
                .getOrDefault(emptyMap())
            val intervals = if (!amberToken.isNullOrBlank()) fetchAmber(amberToken) else fetchSpot(renewables)
            PriceOutlook(if (!amberToken.isNullOrBlank()) PriceSource.AMBER else PriceSource.SPOT, intervals, Instant.now())
        }
        _outlook.value = fresh
        fresh
    }

    /**
     * AEMO's dashboard feed: the last day of 5-minute actual prices plus 30-minute pre-dispatch
     * forecasts. Times are NEM time (UTC+10, no daylight saving) and mark the end of each interval;
     * prices are $/MWh (÷10 for c/kWh). Actuals are averaged into half hours to match the forecasts.
     */
    private fun fetchSpot(renewables: Map<Instant, Double>): List<PriceInterval> {
        val body = JSONObject(post(AEMO_URL, """{"timeScale":["30MIN"]}""")).getJSONArray("5MIN")
        val actualHalfHours = sortedMapOf<Instant, MutableList<Double>>()
        val forecasts = mutableListOf<PriceInterval>()
        for (i in 0 until body.length()) {
            val row = body.getJSONObject(i)
            if (row.optString("REGIONID") != REGION) continue
            val end = LocalDateTime.parse(row.getString("SETTLEMENTDATE")).toInstant(NEM_TIME)
            val cents = row.getDouble("RRP") / 10
            if (row.optString("PERIODTYPE") == "FORECAST") {
                val start = end.minus(HALF_HOUR)
                forecasts += PriceInterval(start, end, cents, renewables[start], isForecast = true)
            } else {
                // A 5-minute interval ending at :05..:30 belongs to the half hour ending at :30.
                val halfHourEnd = ceilToHalfHour(end)
                actualHalfHours.getOrPut(halfHourEnd) { mutableListOf() } += cents
            }
        }
        val actuals = actualHalfHours.map { (end, prices) ->
            val start = end.minus(HALF_HOUR)
            PriceInterval(start, end, prices.average(), renewables[start], isForecast = false)
        }
        val lastActual = actuals.lastOrNull()?.end ?: Instant.EPOCH
        return (actuals + forecasts.filter { !it.start.isBefore(lastActual) }).sortedBy { it.start }
    }

    /** Amber's public renewables share for Victoria, by half-hour start. */
    private fun fetchRenewables(): Map<Instant, Double> {
        val rows = JSONArray(get("$AMBER_BASE/state/vic/renewables/current?next=48&previous=48&resolution=30", token = null))
        return (0 until rows.length()).associate { i ->
            val row = rows.getJSONObject(i)
            minuteOf(Instant.parse(row.getString("startTime"))) to row.getDouble("renewables")
        }
    }

    /** An Amber customer's own prices for their (first active) site, general usage channel. */
    private fun fetchAmber(token: String): List<PriceInterval> {
        val sites = JSONArray(get("$AMBER_BASE/sites", token))
        val site = (0 until sites.length()).map { sites.getJSONObject(it) }
            .firstOrNull { it.optString("status") == "active" } ?: sites.optJSONObject(0)
            ?: throw IOException("No Amber site found for this token")
        val rows = JSONArray(get("$AMBER_BASE/sites/${site.getString("id")}/prices/current?next=48&previous=48&resolution=30", token))
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("channelType") == "general" }
            .map { row ->
                PriceInterval(
                    start = minuteOf(Instant.parse(row.getString("startTime"))),
                    end = minuteOf(Instant.parse(row.getString("endTime"))),
                    centsPerKwh = row.getDouble("perKwh"),
                    renewables = row.optDouble("renewables").takeIf { !it.isNaN() },
                    isForecast = row.optString("type") == "ForecastInterval",
                    spike = row.optString("spikeStatus").let { it == "spike" || it == "potential" },
                )
            }
            .sortedBy { it.start }
    }

    private fun get(url: String, token: String?): String = request(url, token, body = null)

    private fun post(url: String, body: String): String = request(url, token = null, body = body)

    private fun request(url: String, token: String?, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(if (code == 401 || code == 403) "Amber rejected the API token" else "Price service returned HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val REGION = "VIC1"
        const val AEMO_URL = "https://visualisations.aemo.com.au/aemo/apps/api/report/5MIN"
        const val AMBER_BASE = "https://api.amber.com.au/v1"
        val NEM_TIME: ZoneOffset = ZoneOffset.ofHours(10)
        val HALF_HOUR: Duration = Duration.ofMinutes(30)

        /** Amber stamps interval starts a second past the boundary. */
        fun minuteOf(instant: Instant): Instant = Instant.ofEpochSecond(instant.epochSecond - Math.floorMod(instant.epochSecond, 60L))

        fun ceilToHalfHour(instant: Instant): Instant {
            val s = instant.epochSecond
            val rem = Math.floorMod(s, 1800L)
            return Instant.ofEpochSecond(if (rem == 0L) s else s - rem + 1800)
        }
    }
}
