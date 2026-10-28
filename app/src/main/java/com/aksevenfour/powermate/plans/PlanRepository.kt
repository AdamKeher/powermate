package com.aksevenfour.powermate.plans

import android.content.Context
import com.aksevenfour.powermate.ble.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the residential electricity plans on offer at a postcode, from the AER's Consumer Data
 * Right product reference data (cdr.energymadeeasy.gov.au). Results are cached for a week.
 */
class PlanRepository(private val context: Context, private val log: DiagnosticLog) {

    data class Progress(val stage: String, val done: Int, val total: Int)

    suspend fun plansFor(postcode: String, forceRefresh: Boolean, onProgress: (Progress) -> Unit): List<RetailPlan> =
        withContext(Dispatchers.IO) {
            val cache = File(context.cacheDir, "plans-$postcode.json")
            if (!forceRefresh && cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) {
                val cached = JSONArray(cache.readText())
                return@withContext (0 until cached.length()).mapNotNull { PlanParser.parse(cached.getJSONObject(it)) }
            }

            val gate = Semaphore(PARALLEL_REQUESTS)
            val retailersDone = AtomicInteger()
            onProgress(Progress("Checking retailers", 0, RETAILERS.size))
            val candidates = coroutineScope {
                RETAILERS.map { slug ->
                    async {
                        gate.withPermit {
                            val found = runCatching { plansAtPostcode(slug, postcode) }
                                .onFailure { log.add("Plans from $slug unavailable: ${it.message}") }
                                .getOrDefault(emptyList())
                            onProgress(Progress("Checking retailers", retailersDone.incrementAndGet(), RETAILERS.size))
                            found
                        }
                    }
                }.awaitAll().flatten()
            }
            log.add("${candidates.size} plans offered at $postcode")

            val detailsDone = AtomicInteger()
            onProgress(Progress("Downloading plan details", 0, candidates.size))
            val details = coroutineScope {
                candidates.map { (slug, planId) ->
                    async {
                        gate.withPermit {
                            val detail = runCatching { JSONObject(get("$BASE/$slug/cds-au/v1/energy/plans/$planId", version = 3)) }
                                .onFailure { log.add("Plan $planId unavailable: ${it.message}") }
                                .getOrNull()
                            onProgress(Progress("Downloading plan details", detailsDone.incrementAndGet(), candidates.size))
                            detail
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            cache.writeText(JSONArray(details).toString())
            details.mapNotNull { PlanParser.parse(it) }
        }

    /** (retailer, planId) for current residential electricity plans that list [postcode]. */
    private fun plansAtPostcode(slug: String, postcode: String): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        var page = 1
        while (true) {
            val body = JSONObject(
                get("$BASE/$slug/cds-au/v1/energy/plans?fuelType=ELECTRICITY&effective=CURRENT&page-size=1000&page=$page", version = 1),
            )
            val plans = body.getJSONObject("data").getJSONArray("plans")
            for (i in 0 until plans.length()) {
                val plan = plans.getJSONObject(i)
                if (plan.optString("customerType", "RESIDENTIAL") != "RESIDENTIAL") continue
                val geography = plan.optJSONObject("geography") ?: continue
                val included = geography.optJSONArray("includedPostcodes") ?: continue
                if ((0 until included.length()).none { included.getString(it) == postcode }) continue
                val excluded = geography.optJSONArray("excludedPostcodes")
                if (excluded != null && (0 until excluded.length()).any { excluded.getString(it) == postcode }) continue
                found += slug to plan.getString("planId")
            }
            val totalPages = body.optJSONObject("meta")?.optInt("totalPages", 1) ?: 1
            if (page >= totalPages) break
            page++
        }
        return found
    }

    private fun get(url: String, version: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("x-v", version.toString())
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://cdr.energymadeeasy.gov.au"
        private const val CACHE_MS = 7 * 24 * 3_600_000L
        private const val PARALLEL_REQUESTS = 6

        /** Victoria: 3000–3999 and 8000–8999. */
        fun isVictorian(postcode: String): Boolean {
            val n = postcode.trim().takeIf { it.length == 4 }?.toIntOrNull() ?: return false
            return n in 3000..3999 || n in 8000..8999
        }

        /**
         * Retailer identifiers from the AER's "Consumer Data Right – Energy Retailer Base URIs and
         * CDR Brands" list (January 2026). Retailers that don't sell in a postcode simply return
         * no plans for it.
         */
        val RETAILERS = listOf(
            "1st-energy", "actewagl", "active-utilities", "agl", "alinta", "altogether", "amber", "ampol", "arc-energy",
            "arcstream", "aseno", "aurora", "besy", "blue-nrg", "bright-spark", "brighte", "circular", "cleanco", "cleanpeak",
            "coles", "commander", "covau", "cpe-mascot", "diamond", "discover", "dodo", "ea-connect", "electricity-in-a-box",
            "energy-locals", "energy-locals-urban", "energyaustralia", "engie", "erc-energy", "ergon", "erm-power", "evergy",
            "flipped", "flow-power", "flow-systems", "future-x", "gee-energy", "globird", "glow-power", "humenergy", "igeno",
            "io-energy", "kogan", "locality-planning", "localvolts", "lumo", "macarthur", "macquarie", "metered-energy",
            "microgrid", "mojo", "momentum", "nectr", "next-business", "on-by-energyaustralia", "origin", "ovo-energy",
            "pacific-blue", "people-energy", "perpetual", "powerdirect", "powerhub", "powershop", "powow", "qenergy", "radian",
            "real-utilities", "reamped", "red-energy", "sanctuary", "savant", "seene", "shell-energy", "silver-asset",
            "simply-energy", "smart-energy", "smartestenergy", "solstice", "stanwell", "sumo-gas", "sumo-power", "tango",
            "tas-gas", "telstra-energy", "tesla", "veolia", "winconnect", "yes-energy", "zen-energy",
        )
    }
}
