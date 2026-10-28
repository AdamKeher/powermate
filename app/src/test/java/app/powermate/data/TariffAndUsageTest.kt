package app.powermate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.util.Locale

class TariffAndUsageTest {
    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    /** The plan from the first real user: free 11–3, peak 4–7, off-peak otherwise. */
    private val freeWindowPlan = Tariff(
        supplyCentsPerDay = 110.0,
        otherTimes = Rate("Off-peak", 22.0),
        rates = listOf(
            Rate("Free", 0.0, listOf(TimeRange(at(11), at(15)))),
            Rate("Peak", 45.0, listOf(TimeRange(at(16), at(19)))),
        ),
    )

    private val tuesday = LocalDate.of(2026, 10, 27)
    private val saturday = LocalDate.of(2026, 10, 31)

    @Test
    fun `free window plan prices each time correctly`() {
        assertEquals("Off-peak", freeWindowPlan.rateAt(tuesday.atTime(10, 59)).name)
        assertEquals("Free", freeWindowPlan.rateAt(tuesday.atTime(11, 0)).name)
        assertEquals("Free", freeWindowPlan.rateAt(tuesday.atTime(14, 59)).name)
        assertEquals("Off-peak", freeWindowPlan.rateAt(tuesday.atTime(15, 30)).name)
        assertEquals("Peak", freeWindowPlan.rateAt(tuesday.atTime(18, 59)).name)
        assertEquals("Off-peak", freeWindowPlan.rateAt(tuesday.atTime(19, 0)).name)
        assertEquals(0.0, freeWindowPlan.centsPerKwhAt(saturday.atTime(12, 0)), 0.0)
    }

    @Test
    fun `a rate can apply at several times and on chosen days`() {
        val shoulder = Rate(
            "Shoulder", 30.0,
            listOf(
                TimeRange(at(7), at(14), TimeRange.WEEKDAYS),
                TimeRange(at(20), at(22), TimeRange.WEEKDAYS),
            ),
        )
        val tariff = Tariff(otherTimes = Rate("Off-peak", 18.0), rates = listOf(shoulder))
        assertEquals("Shoulder", tariff.rateAt(tuesday.atTime(8, 0)).name)
        assertEquals("Shoulder", tariff.rateAt(tuesday.atTime(21, 0)).name)
        assertEquals("Off-peak", tariff.rateAt(tuesday.atTime(17, 0)).name)
        assertEquals("Off-peak", tariff.rateAt(saturday.atTime(8, 0)).name)
    }

    @Test
    fun `overnight and all-day ranges`() {
        val overnight = TimeRange(at(22), at(7))
        assertTrue(overnight.covers(tuesday.atTime(23, 30)))
        assertTrue(overnight.covers(tuesday.atTime(6, 59)))
        assertFalse(overnight.covers(tuesday.atTime(7, 0)))
        val untilMidnight = TimeRange(at(19), 0)
        assertTrue(untilMidnight.covers(tuesday.atTime(23, 59)))
        assertFalse(untilMidnight.covers(tuesday.atTime(0, 30)))
        val weekendAllDay = TimeRange(0, 0, TimeRange.WEEKENDS)
        assertTrue(weekendAllDay.covers(saturday.atTime(0, 0)))
        assertFalse(weekendAllDay.covers(tuesday.atTime(12, 0)))
    }

    @Test
    fun `seasonal ranges only apply in their months`() {
        val summerPeak = Rate("Peak", 50.0, listOf(TimeRange(at(14), at(20), months = setOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY))))
        val tariff = Tariff(otherTimes = Rate("Standard", 25.0), rates = listOf(summerPeak))
        assertEquals("Peak", tariff.rateAt(LocalDateTime.of(2027, 1, 15, 15, 0)).name)
        assertEquals("Standard", tariff.rateAt(LocalDateTime.of(2026, 7, 15, 15, 0)).name)
        assertTrue(tariff.isSeasonal)
        assertFalse(freeWindowPlan.isSeasonal)
    }

    @Test
    fun `next change finds the following boundary`() {
        assertEquals(tuesday.atTime(11, 0), freeWindowPlan.nextChange(tuesday.atTime(9, 30)))
        assertEquals(tuesday.atTime(15, 0), freeWindowPlan.nextChange(tuesday.atTime(11, 0)))
        assertEquals(tuesday.atTime(19, 0), freeWindowPlan.nextChange(tuesday.atTime(16, 5)))
        assertEquals(tuesday.plusDays(1).atTime(11, 0), freeWindowPlan.nextChange(tuesday.atTime(19, 0)))
        assertNull(Tariff().nextChange(tuesday.atTime(12, 0)))
    }

    @Test
    fun `overlapping rates are reported, first listed wins`() {
        assertTrue(freeWindowPlan.overlappingRates().isEmpty())
        val clash = freeWindowPlan.copy(
            rates = freeWindowPlan.rates + Rate("Shoulder", 30.0, listOf(TimeRange(at(14), at(17), TimeRange.WEEKDAYS))),
        )
        assertEquals(listOf(0 to 2, 1 to 2), clash.overlappingRates())
        assertEquals("Free", clash.rateAt(tuesday.atTime(14, 30)).name)
        // Different days never overlap.
        val split = Tariff(
            rates = listOf(
                Rate("A", 1.0, listOf(TimeRange(0, 0, TimeRange.WEEKDAYS))),
                Rate("B", 2.0, listOf(TimeRange(0, 0, TimeRange.WEEKENDS))),
            ),
        )
        assertTrue(split.overlappingRates().isEmpty())
    }

    @Test
    fun `daily cost of a steady load follows the rates`() {
        // 1 kW for a day: 4 h free, 3 h peak, 17 h off-peak.
        assertEquals(3 * 45.0 + 17 * 22.0, freeWindowPlan.dailyUsageCents(1000.0, tuesday), 1e-6)
    }

    @Test
    fun `tariff survives json`() {
        val seasonal = freeWindowPlan.copy(
            rates = freeWindowPlan.rates + Rate("Winter peak", 40.0, listOf(TimeRange(at(17), at(21), setOf(DayOfWeek.MONDAY), setOf(Month.JULY)))),
        )
        assertEquals(seasonal, Tariff.fromJson(seasonal.toJson()))
        assertEquals(Tariff(), Tariff.fromJson("not json"))
        assertEquals(Tariff(), Tariff.fromJson(null))
    }

    @Test
    fun `first release tariffs are migrated`() {
        val v1 = """{"base":21.5,"supply":95,"windows":[{"name":"Peak","rate":48,"start":900,"end":1260,"days":"WEEKDAYS"}]}"""
        val migrated = Tariff.fromJson(v1)
        assertEquals(95.0, migrated.supplyCentsPerDay, 0.0)
        assertEquals(Rate("Off-peak", 21.5), migrated.otherTimes)
        assertEquals(listOf(Rate("Peak", 48.0, listOf(TimeRange(900, 1260, TimeRange.WEEKDAYS)))), migrated.rates)
    }

    private val zone: ZoneId = ZoneId.of("Australia/Adelaide")

    private fun bucketAt(time: LocalDateTime, pulses: Long) = PulseBucket(time.atZone(zone).toEpochSecond(), pulses)

    @Test
    fun `day summary buckets by local hour and splits usage by rate`() {
        val buckets = listOf(
            bucketAt(tuesday.atTime(10, 45), 1000), // 1 kWh off-peak
            bucketAt(tuesday.atTime(12, 0), 3000), // 3 kWh free
            bucketAt(tuesday.atTime(17, 15), 2000), // 2 kWh peak
        )
        val summary = UsageCalculator.summarize(
            buckets, Period.DAY, tuesday, zone, pulsesPerKwh = 1000.0, tariff = freeWindowPlan,
            now = tuesday.plusDays(3).atStartOfDay(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        assertEquals(6.0, summary.totalKwh, 1e-9)
        assertEquals(1.0, summary.bars[10].kwh, 1e-9)
        assertEquals(3.0, summary.bars[12].kwh, 1e-9)
        assertEquals(22.0 + 0.0 + 90.0, summary.usageCents, 1e-9)
        // A day with readings is charged the whole daily fee, however few hours they cover, and its
        // three hours with readings share it so the hour rows add up to the day.
        assertEquals(110.0, summary.supplyCents, 1e-9)
        assertEquals(summary.totalCents, summary.bars.sumOf { it.totalCents }, 1e-9)
        assertEquals(listOf("Free", "Peak", "Off-peak"), summary.byRate.map { it.name })
        assertEquals(listOf(3.0, 2.0, 1.0), summary.byRate.map { it.kwh })
        assertEquals(listOf(0, 1, 2), summary.byRate.map { it.rateIndex })
        // Each bar is split by rate too: the 17:00 bar is all Peak, the 12:00 bar all Free.
        assertEquals(listOf(0.0, 2.0, 0.0), summary.bars[17].rateKwh)
        assertEquals(listOf(0.0, 90.0, 0.0), summary.bars[17].rateCents)
        assertEquals(listOf(3.0, 0.0, 0.0), summary.bars[12].rateKwh)
        assertFalse(summary.isCurrent)
        assertNull(summary.projectedCents)
    }

    @Test
    fun `day summary feeds the energy clock and the table`() {
        val buckets = listOf(
            bucketAt(tuesday.atTime(11, 15), 2500), // quarter 45
            bucketAt(tuesday.atTime(11, 30), 1500), // quarter 46
            bucketAt(tuesday.atTime(23, 45), 100), // quarter 95
        )
        val summary = UsageCalculator.summarize(
            buckets, Period.DAY, tuesday, zone, 1000.0, freeWindowPlan,
            now = tuesday.plusDays(1).atStartOfDay(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        assertEquals(96, summary.quarterKwh.size)
        assertEquals(2.5, summary.quarterKwh[45], 1e-9)
        assertEquals(1.5, summary.quarterKwh[46], 1e-9)
        assertEquals(0.1, summary.quarterKwh[95], 1e-9)
        assertEquals(listOf(45, 46, 95), summary.quarterHasData.withIndex().filter { it.value }.map { it.index })
        // The two hours with readings share the daily fee; hours without carry none.
        assertEquals(110.0 / 2, summary.bars[11].supplyCents, 1e-9)
        assertEquals(0.0, summary.bars[12].supplyCents, 0.0)
        assertEquals(0.0 + 110.0 / 2, summary.bars[11].totalCents, 1e-9) // free window
    }

    @Test
    fun `line graph series has the right resolution per period`() {
        val buckets = listOf(bucketAt(tuesday.atTime(12, 30), 500), bucketAt(tuesday.atTime(12, 45), 250))
        fun summary(period: Period, start: LocalDate) = UsageCalculator.summarize(
            buckets, period, start, zone, 1000.0, freeWindowPlan,
            now = tuesday.plusDays(10).atStartOfDay(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        val day = summary(Period.DAY, tuesday)
        assertEquals(96, day.series.size)
        assertEquals(0.5, day.series[50].kwh, 1e-9)
        assertEquals(0, day.series[50].rateIndex) // Free
        val week = summary(Period.WEEK, LocalDate.of(2026, 10, 26))
        assertEquals(168, week.series.size)
        assertEquals(0.75, week.series[24 + 12].kwh, 1e-9) // Tuesday 12:00 hour
        val month = summary(Period.MONTH, LocalDate.of(2026, 10, 1))
        assertEquals(31, month.series.size)
        assertEquals(0.75, month.series[26].kwh, 1e-9)
        assertEquals(24.0, month.seriesStepHours, 0.0)
    }

    @Test
    fun `month rows carry a day's fee for each day with readings`() {
        val start = LocalDate.of(2026, 10, 1)
        val buckets = listOf(bucketAt(start.atTime(9, 0), 1000), bucketAt(start.plusDays(2).atTime(9, 0), 1000))
        val summary = UsageCalculator.summarize(
            buckets, Period.MONTH, start, zone, 1000.0, freeWindowPlan,
            now = start.plusMonths(1).atStartOfDay(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        assertEquals(110.0, summary.bars[0].supplyCents, 1e-9)
        assertEquals(0.0, summary.bars[1].supplyCents, 0.0)
        assertTrue(summary.quarterKwh.isEmpty())
        val year = UsageCalculator.summarize(
            buckets, Period.YEAR, LocalDate.of(2026, 1, 1), zone, 1000.0, freeWindowPlan,
            now = start.plusMonths(3).atStartOfDay(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        assertEquals(2 * 110.0, year.bars[9].supplyCents, 1e-9)
    }

    @Test
    fun `buckets outside the period are ignored`() {
        val summary = UsageCalculator.summarize(
            listOf(bucketAt(tuesday.minusDays(1).atTime(23, 45), 1000)), Period.DAY, tuesday, zone, 1000.0, freeWindowPlan,
            now = tuesday.atTime(12, 0).atZone(zone), firstReading = null, locale = Locale.ENGLISH,
        )
        assertEquals(0.0, summary.totalKwh, 0.0)
        assertFalse(summary.hasData)
        assertTrue(summary.byRate.isEmpty())
    }

    @Test
    fun `month has a bar per day and projects the current month`() {
        val start = LocalDate.of(2026, 2, 1)
        val flat = Tariff(supplyCentsPerDay = 100.0, otherTimes = Rate("Standard", 20.0))
        val buckets = (0 until 10).map { bucketAt(start.plusDays(it.toLong()).atTime(12, 0), 5000) }
        val now = start.plusDays(10).atStartOfDay(zone)
        val summary = UsageCalculator.summarize(
            buckets, Period.MONTH, start, zone, 1000.0, flat, now,
            firstReading = start.atStartOfDay(zone).toEpochSecond(), locale = Locale.ENGLISH,
        )
        assertEquals(28, summary.bars.size)
        assertEquals(50.0, summary.totalKwh, 1e-9)
        assertEquals(10, summary.daysWithData)
        assertTrue(summary.isCurrent)
        // 100c usage + 100c supply per elapsed day, over 28 days.
        assertEquals(28 * 200.0, summary.projectedCents!!, 1e-6)
    }

    @Test
    fun `always on is the quietest quarter hour`() {
        val buckets = (0 until 96).map { PulseBucket(it * 900L, if (it == 40) 25 else 200) }
        // 25 pulses in 15 minutes at 1000 imp/kWh = 0.025 kWh per quarter hour = 100 W.
        assertEquals(100.0, UsageCalculator.alwaysOnWatts(buckets, 1000.0)!!, 1e-9)
        assertNull(UsageCalculator.alwaysOnWatts(buckets.take(3), 1000.0))
    }

    @Test
    fun `power slots average each five minutes and spread long readings`() {
        fun reading(time: LocalDateTime, pulses: Int, seconds: Int) =
            Reading(time.atZone(zone).toEpochSecond(), pulses, seconds, Reading.SOURCE_DEVICE)
        val readings = listOf(
            reading(tuesday.atTime(11, 2), 100, 60), // 0.1 kWh in 11:00-11:05
            reading(tuesday.atTime(11, 3), 50, 60), // and 0.05 kWh more
            reading(tuesday.atTime(12, 0), 1200, 3600), // an hourly cloud total, 0.1 kWh per slot
            reading(tuesday.minusDays(1).atTime(23, 59), 999, 60), // the day before: ignored
        )
        val (kw, hasData) = UsageCalculator.powerSlots(readings, tuesday, zone, pulsesPerKwh = 1000.0)
        assertEquals(UsageCalculator.POWER_SLOTS_PER_DAY, kw.size)
        // 0.15 kWh over five minutes is 1.8 kW.
        assertEquals(1.8, kw[11 * 12], 1e-9)
        assertTrue(hasData[11 * 12])
        assertFalse(hasData[11 * 12 + 1])
        for (slot in 12 * 12 until 13 * 12) assertEquals(1.2, kw[slot], 1e-9)
        assertFalse(hasData[13 * 12])
        assertFalse(hasData[0])
        assertEquals(0.15 + 1.2, kw.sum() / 12, 1e-9)
    }

    @Test
    fun `daily totals cover each day of the month, null where there are no readings`() {
        val month = tuesday.withDayOfMonth(1)
        val buckets = listOf(
            bucketAt(month.atTime(0, 0), 500),
            bucketAt(month.atTime(23, 45), 1500), // still the 1st in local time
            bucketAt(month.plusDays(2).atTime(12, 0), 0), // a reading of nothing is still a reading
            bucketAt(month.minusDays(1).atTime(23, 45), 999), // the month before: ignored
        )
        val days = UsageCalculator.dailyKwh(buckets, month, month.lengthOfMonth(), zone, pulsesPerKwh = 1000.0)
        assertEquals(month.lengthOfMonth(), days.size)
        assertEquals(2.0, days[0]!!, 1e-9)
        assertNull(days[1])
        assertEquals(0.0, days[2]!!, 1e-9)
    }
}
