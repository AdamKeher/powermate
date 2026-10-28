package app.powermate.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.util.Locale

/**
 * When a rate applies: a time of day on certain days, optionally only in certain months.
 * A range whose end is before its start runs overnight; one whose start equals its end is all day.
 * Days are judged by the calendar day of each moment, so the early hours of an overnight
 * Friday range fall on Saturday.
 */
data class TimeRange(
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek> = EVERY_DAY,
    val months: Set<Month> = ALL_YEAR,
) {
    val isAllDay: Boolean get() = startMinute == endMinute

    fun covers(time: LocalDateTime): Boolean {
        if (time.dayOfWeek !in days || time.month !in months) return false
        val minute = time.hour * 60 + time.minute
        return segments().any { minute >= it.first && minute < it.second }
    }

    /** The minute-of-day spans `[from, to)` this range covers on a day it applies to. */
    fun segments(): List<Pair<Int, Int>> = when {
        isAllDay -> listOf(0 to MINUTES_PER_DAY)
        startMinute < endMinute -> listOf(startMinute to endMinute)
        else -> listOf(startMinute to MINUTES_PER_DAY, 0 to endMinute).filter { it.first < it.second }
    }

    fun overlaps(other: TimeRange): Boolean {
        if (days.none { it in other.days } || months.none { it in other.months }) return false
        return segments().any { a -> other.segments().any { b -> a.first < b.second && b.first < a.second } }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        val WEEKDAYS: Set<DayOfWeek> = EVERY_DAY - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY
        val WEEKENDS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val ALL_YEAR: Set<Month> = Month.entries.toSet()
    }
}

/** A named price, such as "Peak, 45 c/kWh", and the times it applies. */
data class Rate(
    val name: String,
    val centsPerKwh: Double,
    val ranges: List<TimeRange> = emptyList(),
)

/**
 * Electricity pricing in cents (or pence). At any moment the first of [rates] with a covering
 * time range applies; when none does, [otherTimes] applies. With no [rates] it's a flat-rate plan.
 */
data class Tariff(
    val supplyCentsPerDay: Double = 100.0,
    val otherTimes: Rate = Rate("Standard", 30.0),
    val rates: List<Rate> = emptyList(),
) {
    val isTimeOfUse: Boolean get() = rates.isNotEmpty()

    /** Every rate, time-of-use ones first and [otherTimes] last; indexes match [rateIndexAt]. */
    val allRates: List<Rate> get() = rates + otherTimes

    val isSeasonal: Boolean get() = rates.any { rate -> rate.ranges.any { it.months != TimeRange.ALL_YEAR } }

    /** Index into [allRates] of the rate in force at [time]. */
    fun rateIndexAt(time: LocalDateTime): Int {
        val index = rates.indexOfFirst { rate -> rate.ranges.any { it.covers(time) } }
        return if (index >= 0) index else rates.size
    }

    fun rateAt(time: LocalDateTime): Rate = allRates[rateIndexAt(time)]

    fun centsPerKwhAt(time: LocalDateTime): Double = rateAt(time).centsPerKwh

    /**
     * When the rate next changes after [from], looking up to a week ahead. Rates are constant
     * between range boundaries and midnights, so only those moments need checking.
     */
    fun nextChange(from: LocalDateTime): LocalDateTime? {
        if (!isTimeOfUse) return null
        val current = rateIndexAt(from)
        val boundaries = (rates.flatMap { rate -> rate.ranges.flatMap { listOf(it.startMinute, it.endMinute) } } + 0)
            .distinct().sorted()
        for (dayOffset in 0L..8L) {
            val date = from.toLocalDate().plusDays(dayOffset)
            for (minute in boundaries) {
                val candidate = date.atTime(LocalTime.of(minute / 60, minute % 60))
                if (candidate.isAfter(from) && rateIndexAt(candidate) != current) return candidate
            }
        }
        return null
    }

    /** Pairs of rates, by [allRates] index, whose time ranges overlap; the first of each wins. */
    fun overlappingRates(): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in rates.indices) {
            for (j in i + 1 until rates.size) {
                if (rates[i].ranges.any { a -> rates[j].ranges.any { b -> a.overlaps(b) } }) pairs += i to j
            }
        }
        return pairs
    }

    /** Usage cost over [date] of a steady load of [watts], excluding the supply charge. */
    fun dailyUsageCents(watts: Double, date: LocalDate): Double {
        val kwhPerMinute = watts / 1000.0 / 60.0
        var cents = 0.0
        var time = date.atStartOfDay()
        repeat(TimeRange.MINUTES_PER_DAY) {
            cents += kwhPerMinute * centsPerKwhAt(time)
            time = time.plusMinutes(1)
        }
        return cents
    }

    fun toJson(): String = JSONObject()
        .put("version", 2)
        .put("supply", supplyCentsPerDay)
        .put("other", rateJson(otherTimes))
        .put("rates", JSONArray(rates.map(::rateJson)))
        .toString()

    companion object {
        fun fromJson(json: String?): Tariff {
            if (json.isNullOrBlank()) return Tariff()
            return try {
                val root = JSONObject(json)
                if (root.has("windows")) migrateV1(root) else readV2(root)
            } catch (e: JSONException) {
                Tariff()
            }
        }

        private fun rateJson(rate: Rate): JSONObject = JSONObject()
            .put("name", rate.name)
            .put("rate", rate.centsPerKwh)
            .put(
                "ranges",
                JSONArray(
                    rate.ranges.map { range ->
                        JSONObject()
                            .put("start", range.startMinute)
                            .put("end", range.endMinute)
                            .put("days", JSONArray(range.days.map { it.value }.sorted()))
                            .put("months", JSONArray(range.months.map { it.value }.sorted()))
                    },
                ),
            )

        private fun readV2(root: JSONObject): Tariff {
            val rates = root.optJSONArray("rates") ?: JSONArray()
            return Tariff(
                supplyCentsPerDay = root.optDouble("supply", 100.0),
                otherTimes = root.optJSONObject("other")?.let(::readRate) ?: Rate("Standard", 30.0),
                rates = (0 until rates.length()).map { readRate(rates.getJSONObject(it)) },
            )
        }

        private fun readRate(json: JSONObject): Rate {
            val ranges = json.optJSONArray("ranges") ?: JSONArray()
            return Rate(
                name = json.optString("name"),
                centsPerKwh = json.optDouble("rate", 0.0),
                ranges = (0 until ranges.length()).map { i ->
                    val range = ranges.getJSONObject(i)
                    TimeRange(
                        startMinute = range.optInt("start"),
                        endMinute = range.optInt("end"),
                        days = range.optJSONArray("days").ints().filter { it in 1..7 }.map(DayOfWeek::of).toSet().ifEmpty { TimeRange.EVERY_DAY },
                        months = range.optJSONArray("months").ints().filter { it in 1..12 }.map(Month::of).toSet().ifEmpty { TimeRange.ALL_YEAR },
                    )
                },
            )
        }

        /** The first release stored a base rate plus single-range windows with a day preset. */
        private fun migrateV1(root: JSONObject): Tariff {
            val windows = root.getJSONArray("windows")
            val rates = (0 until windows.length()).map { i ->
                val w = windows.getJSONObject(i)
                val days = when (w.optString("days")) {
                    "WEEKDAYS" -> TimeRange.WEEKDAYS
                    "WEEKENDS" -> TimeRange.WEEKENDS
                    else -> TimeRange.EVERY_DAY
                }
                Rate(w.optString("name"), w.optDouble("rate", 0.0), listOf(TimeRange(w.optInt("start"), w.optInt("end"), days)))
            }
            return Tariff(
                supplyCentsPerDay = root.optDouble("supply", 100.0),
                otherTimes = Rate(if (rates.isEmpty()) "Standard" else "Off-peak", root.optDouble("base", 30.0)),
                rates = rates,
            )
        }

        private fun JSONArray?.ints(): List<Int> =
            if (this == null) emptyList() else (0 until length()).map { getInt(it) }
    }
}

fun formatMinuteOfDay(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
