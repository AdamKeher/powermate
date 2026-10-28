package app.powermate.data

import app.powermate.ble.PowerpalProtocol
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class Period(val label: String) {
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year");

    fun startOf(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (this) {
        DAY -> date
        WEEK -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        MONTH -> date.withDayOfMonth(1)
        YEAR -> date.withDayOfYear(1)
    }

    fun shift(start: LocalDate, by: Long): LocalDate = when (this) {
        DAY -> start.plusDays(by)
        WEEK -> start.plusWeeks(by)
        MONTH -> start.plusMonths(by)
        YEAR -> start.plusYears(by)
    }
}

data class UsageBar(
    val label: String,
    val detail: String,
    val kwh: Double,
    val costCents: Double,
    val hasData: Boolean,
    /** This bar's share of the daily connection fee: 1/24 of a day per hour, or a day per day with readings. */
    val supplyCents: Double = 0.0,
    /** The bar split by rate, indexed like [Tariff.allRates]. */
    val rateKwh: List<Double> = emptyList(),
    val rateCents: List<Double> = emptyList(),
) {
    val totalCents: Double get() = costCents + supplyCents
}

/**
 * One point of the line graph: energy over a fixed step (15 minutes for a day, an hour for a week,
 * a day for a month or year). [rateIndex] is the rate at the step's start, when that's meaningful.
 */
data class SeriesPoint(
    val start: LocalDateTime,
    val kwh: Double,
    val hasData: Boolean,
    val rateIndex: Int?,
)

/** Energy and cost that fell under one rate; [rateIndex] indexes [Tariff.allRates]. */
data class RateUsage(
    val rateIndex: Int,
    val name: String,
    val centsPerKwh: Double,
    val kwh: Double,
    val cents: Double,
)

data class UsageSummary(
    val period: Period,
    val start: LocalDate,
    val title: String,
    val bars: List<UsageBar>,
    val totalKwh: Double,
    val usageCents: Double,
    /** Supply charges for the days in the period that have any readings. */
    val supplyCents: Double,
    val daysWithData: Int,
    /** For the current week, month or year: the period's total cost at the rate so far. */
    val projectedCents: Double?,
    val isCurrent: Boolean,
    /** Usage split by rate, in [Tariff.allRates] order, omitting rates with no usage. */
    val byRate: List<RateUsage> = emptyList(),
    /** Day view only: kWh per quarter hour of local time (96 entries), and which have readings. */
    val quarterKwh: List<Double> = emptyList(),
    val quarterHasData: List<Boolean> = emptyList(),
    /** Day view only: average kW per five minutes of local time (288 entries), and which have readings. */
    val powerKw: List<Double> = emptyList(),
    val powerHasData: List<Boolean> = emptyList(),
    /** Day view only: kWh for each day of the day's month, null for days without readings. */
    val monthDays: List<Double?> = emptyList(),
    /** Finer-grained points for the line graph. */
    val series: List<SeriesPoint> = emptyList(),
) {
    /** Hours per [series] point: a power curve for a day or week, daily energy for longer periods. */
    val seriesStepHours: Double
        get() = when (period) {
            Period.DAY -> 0.25
            Period.WEEK -> 1.0
            Period.MONTH, Period.YEAR -> 24.0
        }

    val totalCents: Double get() = usageCents + supplyCents
    val hasData: Boolean get() = daysWithData > 0
}

/** Pure aggregation of stored pulses into charts and costs, kept separate from storage for testing. */
object UsageCalculator {
    fun summarize(
        buckets: List<PulseBucket>,
        period: Period,
        start: LocalDate,
        zone: ZoneId,
        pulsesPerKwh: Double,
        tariff: Tariff,
        now: ZonedDateTime,
        firstReading: Long?,
        locale: Locale = Locale.getDefault(),
    ): UsageSummary {
        val end = period.shift(start, 1)
        val barCount = when (period) {
            Period.DAY -> 24
            Period.WEEK -> 7
            Period.MONTH -> start.lengthOfMonth()
            Period.YEAR -> 12
        }
        val kwh = DoubleArray(barCount)
        val cents = DoubleArray(barCount)
        val hasData = BooleanArray(barCount)
        val daysWithData = HashSet<LocalDate>()
        val barDays = Array(barCount) { HashSet<LocalDate>() }
        val rates = tariff.allRates
        val rateKwh = DoubleArray(rates.size)
        val rateCents = DoubleArray(rates.size)
        val barRateKwh = Array(barCount) { DoubleArray(rates.size) }
        val barRateCents = Array(barCount) { DoubleArray(rates.size) }
        val quarters = if (period == Period.DAY) DoubleArray(QUARTERS_PER_DAY) else null
        val quarterHasData = BooleanArray(QUARTERS_PER_DAY)
        val seriesCount = when (period) {
            Period.DAY -> QUARTERS_PER_DAY
            Period.WEEK -> 7 * 24
            Period.MONTH, Period.YEAR -> ChronoUnit.DAYS.between(start, end).toInt()
        }
        val seriesKwh = DoubleArray(seriesCount)
        val seriesHasData = BooleanArray(seriesCount)

        for (bucket in buckets) {
            val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(bucket.bucket), zone)
            val day = time.toLocalDate()
            if (day.isBefore(start) || !day.isBefore(end)) continue
            val index = when (period) {
                Period.DAY -> time.hour
                Period.WEEK -> ChronoUnit.DAYS.between(start, day).toInt()
                Period.MONTH -> day.dayOfMonth - 1
                Period.YEAR -> day.monthValue - 1
            }
            val energy = PowerpalProtocol.kwh(bucket.pulses, pulsesPerKwh)
            val rateIndex = tariff.rateIndexAt(time)
            val cost = energy * rates[rateIndex].centsPerKwh
            kwh[index] += energy
            cents[index] += cost
            rateKwh[rateIndex] += energy
            rateCents[rateIndex] += cost
            barRateKwh[index][rateIndex] += energy
            barRateCents[index][rateIndex] += cost
            hasData[index] = true
            daysWithData += day
            barDays[index] += day
            if (quarters != null) {
                val quarter = time.hour * 4 + time.minute / 15
                quarters[quarter] += energy
                quarterHasData[quarter] = true
            }
            val point = when (period) {
                Period.DAY -> time.hour * 4 + time.minute / 15
                Period.WEEK -> ChronoUnit.DAYS.between(start, day).toInt() * 24 + time.hour
                Period.MONTH, Period.YEAR -> ChronoUnit.DAYS.between(start, day).toInt()
            }
            if (point in 0 until seriesCount) {
                seriesKwh[point] += energy
                seriesHasData[point] = true
            }
        }

        val hoursWithData = if (period == Period.DAY) hasData.count { it } else 0
        val bars = List(barCount) { i ->
            UsageBar(
                label = barLabel(period, start, i, locale),
                detail = barDetail(period, start, i, locale),
                kwh = kwh[i],
                costCents = cents[i],
                hasData = hasData[i],
                supplyCents = when {
                    !hasData[i] -> 0.0
                    // The day's fee is shared by its hours with readings, so the hour rows add up to the day.
                    period == Period.DAY -> tariff.supplyCentsPerDay / hoursWithData
                    else -> barDays[i].size * tariff.supplyCentsPerDay
                },
                rateKwh = barRateKwh[i].toList(),
                rateCents = barRateCents[i].toList(),
            )
        }
        val usageCents = cents.sum()
        // The connection fee is a whole day's charge for every day with readings, however many hours
        // they cover. Days with none aren't charged, so nothing is billed before history starts.
        val supplyCents = daysWithData.size * tariff.supplyCentsPerDay
        val today = now.toLocalDate()
        val isCurrent = !today.isBefore(start) && today.isBefore(end)

        var projected: Double? = null
        if (isCurrent && period != Period.DAY) {
            val periodStart = start.atStartOfDay(zone).toEpochSecond()
            val from = maxOf(periodStart, firstReading ?: periodStart)
            val elapsedDays = (now.toEpochSecond() - from) / 86_400.0
            if (elapsedDays >= 1.0) {
                val periodDays = ChronoUnit.DAYS.between(start, end).toDouble()
                projected = (usageCents / elapsedDays + tariff.supplyCentsPerDay) * periodDays
            }
        }

        return UsageSummary(
            period = period,
            start = start,
            title = title(period, start, end, locale),
            bars = bars,
            totalKwh = kwh.sum(),
            usageCents = usageCents,
            supplyCents = supplyCents,
            daysWithData = daysWithData.size,
            projectedCents = projected,
            isCurrent = isCurrent,
            byRate = rates.indices
                .filter { rateKwh[it] > 0 }
                .map { RateUsage(it, rates[it].name, rates[it].centsPerKwh, rateKwh[it], rateCents[it]) },
            quarterKwh = quarters?.toList() ?: emptyList(),
            quarterHasData = if (quarters != null) quarterHasData.toList() else emptyList(),
            series = List(seriesCount) { i ->
                val pointStart = when (period) {
                    Period.DAY -> start.atTime(i / 4, (i % 4) * 15)
                    Period.WEEK -> start.plusDays((i / 24).toLong()).atTime(i % 24, 0)
                    Period.MONTH, Period.YEAR -> start.plusDays(i.toLong()).atStartOfDay()
                }
                SeriesPoint(
                    start = pointStart,
                    kwh = seriesKwh[i],
                    hasData = seriesHasData[i],
                    rateIndex = if (period == Period.DAY || period == Period.WEEK) tariff.rateIndexAt(pointStart) else null,
                )
            },
        )
    }

    const val QUARTERS_PER_DAY = 96

    /** kWh for each of [days] days from [firstDay] in local time, null for days without readings. */
    fun dailyKwh(buckets: List<PulseBucket>, firstDay: LocalDate, days: Int, zone: ZoneId, pulsesPerKwh: Double): List<Double?> {
        val totals = arrayOfNulls<Double>(days)
        for (bucket in buckets) {
            val day = ChronoUnit.DAYS.between(firstDay, LocalDateTime.ofInstant(Instant.ofEpochSecond(bucket.bucket), zone).toLocalDate())
            if (day !in 0 until days) continue
            totals[day.toInt()] = (totals[day.toInt()] ?: 0.0) + PowerpalProtocol.kwh(bucket.pulses, pulsesPerKwh)
        }
        return totals.toList()
    }
    const val POWER_SLOT_MINUTES = 5
    const val POWER_SLOTS_PER_DAY = 1440 / POWER_SLOT_MINUTES

    /**
     * Average power in kW for each five minutes of [day] in local time, and which slots have
     * readings. A reading longer than a slot (an older cloud import's hour or quarter) is spread
     * evenly over the time it covers instead of landing in its first slot.
     */
    fun powerSlots(
        readings: List<Reading>,
        day: LocalDate,
        zone: ZoneId,
        pulsesPerKwh: Double,
    ): Pair<List<Double>, List<Boolean>> {
        val slotSeconds = POWER_SLOT_MINUTES * 60L
        val kwh = DoubleArray(POWER_SLOTS_PER_DAY)
        val hasData = BooleanArray(POWER_SLOTS_PER_DAY)
        for (reading in readings) {
            val duration = reading.durationSec.coerceAtLeast(1).toLong()
            val energy = PowerpalProtocol.kwh(reading.pulses.toLong(), pulsesPerKwh)
            val end = reading.timestamp + duration
            var t = reading.timestamp
            while (t < end) {
                // Five-minute boundaries line up with local time in every Australian zone.
                val next = minOf(end, (Math.floorDiv(t, slotSeconds) + 1) * slotSeconds)
                val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(t), zone)
                if (time.toLocalDate() == day) {
                    val slot = (time.hour * 60 + time.minute) / POWER_SLOT_MINUTES
                    kwh[slot] += energy * (next - t) / duration
                    hasData[slot] = true
                }
                t = next
            }
        }
        val hoursPerSlot = POWER_SLOT_MINUTES / 60.0
        return kwh.map { it / hoursPerSlot } to hasData.toList()
    }

    /** Lowest quarter hour in [buckets], as watts: a fair estimate of the always-on load. */
    fun alwaysOnWatts(buckets: List<PulseBucket>, pulsesPerKwh: Double): Double? {
        if (buckets.size < MIN_BUCKETS_FOR_BASELINE) return null
        return PowerpalProtocol.kwh(buckets.minOf { it.pulses }, pulsesPerKwh) * 4_000.0
    }

    private const val MIN_BUCKETS_FOR_BASELINE = 16

    private fun hourLabel(hour: Int, locale: Locale): String =
        LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern("ha", locale)).lowercase(locale)

    private fun barLabel(period: Period, start: LocalDate, i: Int, locale: Locale): String = when (period) {
        Period.DAY -> hourLabel(i, locale)
        Period.WEEK -> start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        Period.MONTH -> (i + 1).toString()
        Period.YEAR -> Month.of(i + 1).getDisplayName(TextStyle.SHORT, locale)
    }

    private fun barDetail(period: Period, start: LocalDate, i: Int, locale: Locale): String = when (period) {
        Period.DAY -> "${hourLabel(i, locale)} – ${hourLabel((i + 1) % 24, locale)}"
        Period.WEEK, Period.MONTH ->
            start.plusDays(i.toLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        Period.YEAR -> start.withMonth(i + 1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    }

    private fun title(period: Period, start: LocalDate, end: LocalDate, locale: Locale): String {
        val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return when (period) {
            Period.DAY -> start.format(DateTimeFormatter.ofPattern("EEE", locale)) + " " + start.format(medium)
            Period.WEEK -> start.format(medium) + " – " + end.minusDays(1).format(medium)
            Period.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
            Period.YEAR -> start.year.toString()
        }
    }
}

class UsageRepository(private val readings: ReadingDao) {
    /** Emits whenever stored readings change. */
    fun changes(): Flow<Long?> = readings.observeLatestTimestamp()

    fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    suspend fun summary(
        period: Period,
        anchor: LocalDate,
        settings: AppSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): UsageSummary {
        val start = period.startOf(anchor, firstDayOfWeek())
        val end = period.shift(start, 1)
        val from = start.atStartOfDay(zone).toEpochSecond()
        val to = end.atStartOfDay(zone).toEpochSecond()
        val buckets = readings.quarterHours(from, to)
        val summary = UsageCalculator.summarize(
            buckets = buckets,
            period = period,
            start = start,
            zone = zone,
            pulsesPerKwh = settings.pulsesPerKwh,
            tariff = settings.tariff,
            now = ZonedDateTime.now(zone),
            firstReading = readings.earliestTimestamp(),
        )
        if (period != Period.DAY) return summary
        val (powerKw, powerHasData) = UsageCalculator.powerSlots(readings.between(from, to), start, zone, settings.pulsesPerKwh)
        val month = start.withDayOfMonth(1)
        val monthBuckets = readings.quarterHours(month.atStartOfDay(zone).toEpochSecond(), month.plusMonths(1).atStartOfDay(zone).toEpochSecond())
        val monthDays = UsageCalculator.dailyKwh(monthBuckets, month, month.lengthOfMonth(), zone, settings.pulsesPerKwh)
        return summary.copy(powerKw = powerKw, powerHasData = powerHasData, monthDays = monthDays)
    }

    /** Always-on load over the last day, ignoring the current, incomplete quarter hour. */
    suspend fun alwaysOnWatts(pulsesPerKwh: Double): Double? {
        val end = System.currentTimeMillis() / 1000 / 900 * 900
        return UsageCalculator.alwaysOnWatts(readings.quarterHours(end - 86_400, end), pulsesPerKwh)
    }
}
