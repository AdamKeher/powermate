package app.powermate.plans

import app.powermate.data.Rate
import app.powermate.data.Tariff
import app.powermate.data.TimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.min

/**
 * An electricity plan from the AER's Consumer Data Right product reference data. Prices are stored
 * as published, in dollars excluding GST.
 */
data class RetailPlan(
    val planId: String,
    val brandName: String,
    val displayName: String,
    /** MARKET, STANDING or REGULATED. */
    val type: String,
    val pricingModel: String,
    val periods: List<PlanPeriod>,
    /** Guaranteed discounts, as fractions. Conditional ones (pay on time etc.) aren't applied. */
    val discountOffUsage: Double = 0.0,
    val discountOffBill: Double = 0.0,
    val hasConditionalDiscounts: Boolean = false,
    val hasDemandCharges: Boolean = false,
    val distributors: List<String> = emptyList(),
) {
    fun periodOn(date: LocalDate): PlanPeriod? {
        val day = MonthDay.from(date)
        return periods.firstOrNull { it.covers(day) } ?: periods.firstOrNull()
    }
}

/** A seasonal stretch of the plan, e.g. 01-07 to 30-06 for a year-round plan. */
data class PlanPeriod(
    val start: MonthDay,
    val end: MonthDay,
    val dailySupplyDollars: Double,
    /** One entry for a single-rate period; one per time-of-use band otherwise. */
    val rates: List<PlanRate>,
) {
    fun covers(day: MonthDay): Boolean =
        if (!start.isAfter(end)) !day.isBefore(start) && !day.isAfter(end) else !day.isBefore(start) || !day.isAfter(end)
}

data class PlanRate(
    val name: String,
    /** PEAK, OFF_PEAK, SHOULDER… or SINGLE for a flat rate. */
    val type: String,
    /** Stepped prices: each tier's size in kWh, the last unlimited. */
    val tiers: List<PlanTier>,
    /** How often stepped tiers reset. */
    val blockPeriod: BlockPeriod,
    /** When it applies; empty means always (single rate). */
    val times: List<PlanTime>,
)

data class PlanTier(val dollarsPerKwh: Double, val kwh: Double?)

enum class BlockPeriod { DAY, MONTH, QUARTER, YEAR }

/** Days plus a minute-of-day span, end exclusive; an end before the start wraps past midnight. */
data class PlanTime(val days: Set<DayOfWeek>, val startMinute: Int, val endMinute: Int) {
    fun covers(day: DayOfWeek, minute: Int): Boolean {
        if (day !in days) return false
        return if (startMinute < endMinute) minute in startMinute until endMinute else minute >= startMinute || minute < endMinute
    }
}

object PlanParser {
    /** Parses a plan detail response (`GET /energy/plans/{planId}`, x-v 3). */
    fun parse(json: JSONObject): RetailPlan? {
        val data = json.optJSONObject("data") ?: json
        val contract = data.optJSONObject("electricityContract") ?: return null
        val periodsJson = contract.optJSONArray("tariffPeriod") ?: return null
        var demand = false
        val periods = (0 until periodsJson.length()).mapNotNull { i ->
            val p = periodsJson.getJSONObject(i)
            if (p.has("demandCharges")) demand = true
            val rates = when (p.optString("rateBlockUType")) {
                "singleRate" -> p.optJSONObject("singleRate")?.let { listOf(parseRate(it, "SINGLE", emptyList())) }
                "timeOfUseRates" -> p.optJSONArray("timeOfUseRates")?.let { tou ->
                    (0 until tou.length()).map { j ->
                        val r = tou.getJSONObject(j)
                        parseRate(r, r.optString("type", "TIME_OF_USE"), parseTimes(r.optJSONArray("timeOfUse")))
                    }
                }
                "demandCharges" -> {
                    demand = true
                    null
                }
                else -> null
            } ?: return@mapNotNull null
            PlanPeriod(
                start = monthDay(p.optString("startDate"), MonthDay.of(1, 1)),
                end = monthDay(p.optString("endDate"), MonthDay.of(12, 31)),
                dailySupplyDollars = dailySupply(p),
                rates = rates,
            )
        }
        if (periods.isEmpty()) return null
        var offUsage = 0.0
        var offBill = 0.0
        var conditional = false
        contract.optJSONArray("discounts")?.let { discounts ->
            for (i in 0 until discounts.length()) {
                val d = discounts.getJSONObject(i)
                if (d.optString("type") != "GUARANTEED") {
                    conditional = true
                    continue
                }
                d.optJSONObject("percentOfUse")?.optString("rate")?.toDoubleOrNull()?.let { offUsage += it }
                d.optJSONObject("percentOfBill")?.optString("rate")?.toDoubleOrNull()?.let { offBill += it }
            }
        }
        val geography = data.optJSONObject("geography")
        return RetailPlan(
            planId = data.optString("planId"),
            brandName = data.optString("brandName"),
            displayName = data.optString("displayName"),
            type = data.optString("type"),
            pricingModel = contract.optString("pricingModel"),
            periods = periods,
            discountOffUsage = offUsage,
            discountOffBill = offBill,
            hasConditionalDiscounts = conditional,
            hasDemandCharges = demand,
            distributors = geography?.optJSONArray("distributors").strings(),
        )
    }

    private fun dailySupply(period: JSONObject): Double {
        period.optString("dailySupplyCharge").toDoubleOrNull()?.let { return it }
        // Banded supply charges: take the first band.
        val bands = period.optJSONArray("dailySupplyCharges") ?: return 0.0
        return bands.optJSONObject(0)?.optString("amount")?.toDoubleOrNull() ?: 0.0
    }

    private fun parseRate(json: JSONObject, type: String, times: List<PlanTime>): PlanRate {
        val rates = json.optJSONArray("rates") ?: JSONArray()
        val tiers = (0 until rates.length()).mapNotNull { i ->
            val r = rates.getJSONObject(i)
            r.optString("unitPrice").toDoubleOrNull()?.let { PlanTier(it, r.optString("volume").toDoubleOrNull()) }
        }.ifEmpty { listOf(PlanTier(0.0, null)) }
        return PlanRate(
            name = json.optString("displayName").ifBlank { type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } },
            type = type,
            tiers = tiers,
            blockPeriod = when (json.optString("period")) {
                "P1D" -> BlockPeriod.DAY
                "P1M" -> BlockPeriod.MONTH
                "P3M" -> BlockPeriod.QUARTER
                else -> BlockPeriod.YEAR
            },
            times = times,
        )
    }

    private fun parseTimes(array: JSONArray?): List<PlanTime> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val t = array.getJSONObject(i)
            val days = t.optJSONArray("days").strings().mapNotNull(::dayOf).toSet().ifEmpty { DayOfWeek.entries.toSet() }
            val start = minuteOf(t.optString("startTime")) ?: return@mapNotNull null
            // End times are published inclusive ("09:59" means up to 10:00).
            val endInclusive = minuteOf(t.optString("endTime")) ?: return@mapNotNull null
            PlanTime(days, start, (endInclusive + 1) % MINUTES_PER_DAY)
        }
    }

    private fun dayOf(code: String): DayOfWeek? = when (code) {
        "MON" -> DayOfWeek.MONDAY
        "TUE" -> DayOfWeek.TUESDAY
        "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY
        "FRI" -> DayOfWeek.FRIDAY
        "SAT" -> DayOfWeek.SATURDAY
        "SUN" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun minuteOf(text: String): Int? {
        val parts = text.trim().split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return (h * 60 + m).takeIf { it in 0..MINUTES_PER_DAY }
    }

    private fun monthDay(text: String, fallback: MonthDay): MonthDay {
        val parts = text.split('-')
        val month = parts.getOrNull(0)?.toIntOrNull() ?: return fallback
        val day = parts.getOrNull(1)?.toIntOrNull() ?: return fallback
        return runCatching { MonthDay.of(month, day) }.getOrDefault(fallback)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private const val MINUTES_PER_DAY = 1440
}

/** A plan's estimated yearly cost for this household, including GST. */
data class PlanEstimate(
    val plan: RetailPlan,
    val yearlyDollars: Double,
    val usageDollars: Double,
    val supplyDollars: Double,
)

/** Readings to price: energy per quarter hour of local time. */
data class UsageSample(val start: LocalDateTime, val kwh: Double)

object PlanPricing {
    const val GST = 1.1

    /**
     * Prices [usage] under [plan] and scales the result to a year. Stepped tiers fill per block
     * period; guaranteed discounts apply; demand charges and conditional discounts don't.
     */
    fun estimate(plan: RetailPlan, usage: List<UsageSample>): PlanEstimate {
        val days = usage.map { it.start.toLocalDate() }.toSortedSet()
        if (days.isEmpty()) return PlanEstimate(plan, 0.0, 0.0, 0.0)
        val blockUse = HashMap<Triple<Int, Int, Long>, Double>()
        var usageCost = 0.0
        for (sample in usage) {
            if (sample.kwh <= 0) continue
            val date = sample.start.toLocalDate()
            val period = plan.periodOn(date) ?: continue
            val periodIndex = plan.periods.indexOf(period)
            val minute = sample.start.hour * 60 + sample.start.minute
            val rateIndex = rateIndexAt(period, sample.start.dayOfWeek, minute)
            val rate = period.rates[rateIndex]
            val key = Triple(periodIndex, rateIndex, blockKey(rate.blockPeriod, date))
            var used = blockUse[key] ?: 0.0
            var remaining = sample.kwh
            var tierFloor = 0.0
            for ((t, tier) in rate.tiers.withIndex()) {
                val ceiling = if (t == rate.tiers.lastIndex || tier.kwh == null) Double.MAX_VALUE else tierFloor + tier.kwh
                val room = (ceiling - maxOf(used, tierFloor)).coerceAtLeast(0.0)
                val take = min(room, remaining)
                usageCost += take * tier.dollarsPerKwh
                remaining -= take
                used += take
                tierFloor = ceiling
                if (remaining <= 0) break
            }
            blockUse[key] = used
        }
        usageCost *= 1 - plan.discountOffUsage
        val supplyCost = days.sumOf { plan.periodOn(it)?.dailySupplyDollars ?: 0.0 }
        val scale = 365.0 / days.size
        val total = (usageCost + supplyCost) * (1 - plan.discountOffBill) * GST * scale
        return PlanEstimate(plan, total, usageCost * GST * scale, supplyCost * GST * scale)
    }

    /** The same usage at the household's own [tariff], for comparison. */
    fun estimateTariff(tariff: Tariff, usage: List<UsageSample>): Double {
        val days = usage.map { it.start.toLocalDate() }.toSet()
        if (days.isEmpty()) return 0.0
        val cents = usage.sumOf { it.kwh * tariff.centsPerKwhAt(it.start) } + days.size * tariff.supplyCentsPerDay
        return cents / 100 * 365.0 / days.size
    }

    fun rateIndexAt(period: PlanPeriod, day: DayOfWeek, minute: Int): Int {
        if (period.rates.size == 1) return 0
        val index = period.rates.indexOfFirst { rate -> rate.times.any { it.covers(day, minute) } }
        if (index >= 0) return index
        // Times not covered by any band: fall back to off-peak, else the first band.
        return period.rates.indexOfFirst { it.type == "OFF_PEAK" }.takeIf { it >= 0 } ?: 0
    }

    private fun blockKey(period: BlockPeriod, date: LocalDate): Long = when (period) {
        BlockPeriod.DAY -> date.toEpochDay()
        BlockPeriod.MONTH -> date.year * 12L + date.monthValue
        BlockPeriod.QUARTER -> date.year * 4L + (date.monthValue - 1) / 3
        BlockPeriod.YEAR -> date.year.toLong()
    }

    /**
     * Turns [plan] into a PowerMate [Tariff] using the season in force on [today]. The band that
     * covers the most of the week becomes "all other times"; the rest keep their exact times.
     */
    fun toTariff(plan: RetailPlan, today: LocalDate): Tariff {
        val period = plan.periodOn(today) ?: plan.periods.first()
        val supply = period.dailySupplyDollars * 100 * GST
        fun cents(rate: PlanRate) = rate.tiers.first().dollarsPerKwh * 100 * GST
        if (period.rates.size == 1) {
            return Tariff(supplyCentsPerDay = supply, otherTimes = Rate(period.rates[0].name.ifBlank { "Standard" }, cents(period.rates[0])))
        }
        val coverage = period.rates.map { rate ->
            DayOfWeek.entries.sumOf { day -> (0 until 1440 step 15).count { m -> rate.times.any { it.covers(day, m) } } }
        }
        // Most of the week wins; ties go to an off-peak band, then the cheaper one.
        val mainIndex = coverage.indices.maxWithOrNull(
            compareBy<Int>({ coverage[it] }, { if (period.rates[it].type == "OFF_PEAK") 1 else 0 }, { -cents(period.rates[it]) }),
        ) ?: 0
        val others = period.rates.filterIndexed { i, _ -> i != mainIndex }.map { rate ->
            Rate(
                name = rate.name,
                centsPerKwh = cents(rate),
                ranges = rate.times.map { TimeRange(it.startMinute, it.endMinute, it.days) },
            )
        }
        return Tariff(supplyCentsPerDay = supply, otherTimes = Rate(period.rates[mainIndex].name, cents(period.rates[mainIndex])), rates = others)
    }
}
