package com.aksevenfour.powermate.plans

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

class RetailPlansTest {
    private fun fixture(): RetailPlan {
        val text = javaClass.getResource("/cdr/ovo_free4.json")!!.readText()
        return PlanParser.parse(JSONObject(text))!!
    }

    @Test
    fun `parses a real AER plan record`() {
        val plan = fixture()
        assertEquals("OVO932456MRE10@EME", plan.planId)
        assertEquals("The Free 4 Plan", plan.displayName)
        assertEquals("TIME_OF_USE_CONT_LOAD", plan.pricingModel)
        val period = plan.periods.single()
        assertEquals(MonthDay.of(7, 1), period.start)
        assertEquals(MonthDay.of(6, 30), period.end)
        assertEquals(3.0619, period.dailySupplyDollars, 1e-9)
        assertEquals(listOf("Peak", "Off Peak", "Shoulder"), period.rates.map { it.name })
        // "15:00"–"21:59" is published inclusive: it runs to 22:00.
        val peak = period.rates[0]
        assertTrue(peak.times.any { it.startMinute == 15 * 60 && it.endMinute == 22 * 60 })
        assertFalse(plan.hasDemandCharges)
    }

    @Test
    fun `free window and peak are picked by time`() {
        val period = fixture().periods.single()
        fun rateAt(h: Int, m: Int = 0) = period.rates[PlanPricing.rateIndexAt(period, DayOfWeek.TUESDAY, h * 60 + m)].name
        assertEquals("Off Peak", rateAt(3))
        assertEquals("Peak", rateAt(8))
        assertEquals("Off Peak", rateAt(10, 30))
        assertEquals("Shoulder", rateAt(12))
        assertEquals("Shoulder", rateAt(14, 59))
        assertEquals("Peak", rateAt(15))
        assertEquals("Peak", rateAt(21, 59))
        assertEquals("Off Peak", rateAt(22))
    }

    @Test
    fun `estimate prices usage per band, adds supply and GST, and scales to a year`() {
        val plan = fixture()
        val day = LocalDate.of(2026, 10, 27)
        val usage = listOf(
            UsageSample(day.atTime(12, 0), 10.0), // free shoulder
            UsageSample(day.atTime(18, 0), 2.0), // peak 0.3828
            UsageSample(day.atTime(23, 0), 1.0), // off-peak 0.2194
        )
        val estimate = PlanPricing.estimate(plan, usage)
        val dayCost = (2 * 0.3828 + 0.2194 + 3.0619) * 1.1
        assertEquals(dayCost * 365, estimate.yearlyDollars, 1e-6)
        assertEquals(3.0619 * 1.1 * 365, estimate.supplyDollars, 1e-6)
    }

    @Test
    fun `stepped tiers fill per block period`() {
        val plan = RetailPlan(
            planId = "X", brandName = "B", displayName = "Stepped", type = "MARKET", pricingModel = "SINGLE_RATE",
            periods = listOf(
                PlanPeriod(
                    MonthDay.of(1, 1), MonthDay.of(12, 31), 1.0,
                    listOf(PlanRate("Usage", "SINGLE", listOf(PlanTier(0.20, 10.0), PlanTier(0.30, null)), BlockPeriod.DAY, emptyList())),
                ),
            ),
            discountOffUsage = 0.10,
        )
        val day = LocalDate.of(2026, 3, 2)
        // 8 kWh then 5 kWh on one day: 10 at 0.20, 3 at 0.30. A fresh day starts at tier one again.
        val usage = listOf(
            UsageSample(day.atTime(1, 0), 8.0),
            UsageSample(day.atTime(2, 0), 5.0),
            UsageSample(day.plusDays(1).atTime(1, 0), 4.0),
        )
        val estimate = PlanPricing.estimate(plan, usage)
        val usageCost = (10 * 0.20 + 3 * 0.30 + 4 * 0.20) * 0.9
        assertEquals((usageCost + 2 * 1.0) * 1.1 * 365 / 2, estimate.yearlyDollars, 1e-6)
    }

    @Test
    fun `a plan converts to a PowerMate tariff`() {
        val tariff = PlanPricing.toTariff(fixture(), LocalDate.of(2026, 10, 28))
        assertEquals(3.0619 * 110, tariff.supplyCentsPerDay, 1e-9)
        // The band covering most of the week becomes "all other times".
        assertEquals("Off Peak", tariff.otherTimes.name)
        assertEquals(21.94 * 1.1, tariff.otherTimes.centsPerKwh, 1e-9)
        assertEquals(setOf("Peak", "Shoulder"), tariff.rates.map { it.name }.toSet())
        assertEquals(0.0, tariff.centsPerKwhAt(LocalDate.of(2026, 10, 28).atTime(13, 0)), 0.0)
        assertEquals(38.28 * 1.1, tariff.centsPerKwhAt(LocalDate.of(2026, 10, 28).atTime(17, 0)), 1e-9)
    }

    @Test
    fun `only Victorian postcodes are accepted`() {
        assertTrue(PlanRepository.isVictorian("3000"))
        assertTrue(PlanRepository.isVictorian("3999"))
        assertTrue(PlanRepository.isVictorian("8001"))
        assertFalse(PlanRepository.isVictorian("2000"))
        assertFalse(PlanRepository.isVictorian("300"))
        assertFalse(PlanRepository.isVictorian("abcd"))
    }
}
