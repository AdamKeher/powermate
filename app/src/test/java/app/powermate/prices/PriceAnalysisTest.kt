package app.powermate.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PriceAnalysisTest {
    private val t0 = Instant.parse("2026-10-28T00:00:00Z")

    private fun day(vararg cents: Double, renewables: List<Double>? = null) = cents.mapIndexed { i, c ->
        PriceInterval(t0.plusSeconds(1800L * i), t0.plusSeconds(1800L * (i + 1)), c, renewables?.get(i))
    }

    @Test
    fun `levels compare with the average`() {
        assertEquals(PriceLevel.GREAT, PriceAnalysis.level(10.0, 25.0))
        assertEquals(PriceLevel.GOOD, PriceAnalysis.level(24.0, 25.0))
        assertEquals(PriceLevel.ELEVATED, PriceAnalysis.level(30.0, 25.0))
        assertEquals(PriceLevel.HIGH, PriceAnalysis.level(40.0, 25.0))
    }

    @Test
    fun `finds cheap and expensive stretches of an hour or more`() {
        // Average 20: 5,5 is a great hour; 40,40,40 a high hour and a half; a lone 5 is too short.
        val intervals = day(20.0, 5.0, 5.0, 20.0, 5.0, 20.0, 40.0, 40.0, 40.0, 20.0, 20.0, 5.0, 20.0, 20.0, 20.0, 20.0)
        val windows = PriceAnalysis.windows(intervals)
        assertEquals(20.0, PriceAnalysis.average(intervals), 1e-9)
        assertEquals(2, windows.size)
        val great = windows[0]
        assertTrue(great.great)
        assertEquals(t0.plusSeconds(1800), great.start)
        assertEquals(t0.plusSeconds(3 * 1800), great.end)
        assertEquals(75, great.percentFromAverage)
        val high = windows[1]
        assertTrue(!high.great)
        assertEquals(t0.plusSeconds(6 * 1800), high.start)
        assertEquals(40.0, high.averageCents, 1e-9)
    }

    @Test
    fun `a deep midday dip doesn't make the whole night high`() {
        // 24 hours like a sunny Victorian spring day: 11c overnight, 2c at midday, a 15c evening peak.
        val prices = List(48) { i ->
            when (i) {
                in 20..31 -> 2.0 // 10am-4pm solar
                in 36..41 -> 15.0 // 6pm-9pm
                else -> 11.0
            }
        }
        val windows = PriceAnalysis.windows(day(*prices.toDoubleArray()))
        val high = windows.single { !it.great }
        assertEquals(t0.plusSeconds(36 * 1800L), high.start)
        assertEquals(t0.plusSeconds(42 * 1800L), high.end)
        val great = windows.single { it.great }
        assertEquals(t0.plusSeconds(20 * 1800L), great.start)
    }

    @Test
    fun `greener is relative to the average renewables share`() {
        val intervals = day(20.0, 5.0, 5.0, 20.0, renewables = listOf(40.0, 80.0, 80.0, 40.0))
        val great = PriceAnalysis.windows(intervals).single()
        assertEquals(33, great.percentGreener) // 80 vs an average of 60
    }

    @Test
    fun `no windows when prices are flat or negative on average`() {
        assertTrue(PriceAnalysis.windows(day(20.0, 20.0, 20.0, 20.0)).isEmpty())
        assertTrue(PriceAnalysis.windows(day(-5.0, -5.0, -1.0, -1.0)).isEmpty())
    }
}
