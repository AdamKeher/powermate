package app.powermate.notify

import app.powermate.data.AlertSettings
import app.powermate.data.Rate
import app.powermate.data.Tariff
import app.powermate.data.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RateRemindersTest {
    // The user's plan: free 11–3, peak 4–9, off-peak otherwise.
    private val plan = Tariff(
        supplyCentsPerDay = 132.077,
        otherTimes = Rate("Off-peak", 23.276),
        rates = listOf(
            Rate("Super off-peak", 0.0, listOf(TimeRange(11 * 60, 15 * 60))),
            Rate("Peak", 49.06, listOf(TimeRange(16 * 60, 21 * 60))),
        ),
    )
    private val alerts = AlertSettings(reminderLeadMinutes = 15)
    private val day = LocalDate.of(2026, 10, 28)

    @Test
    fun `reminds before the free window and before peak, not off-peak`() {
        val first = RateReminders.next(plan, alerts, day.atTime(9, 0))!!
        assertEquals(day.atTime(10, 45), first.fireAt)
        assertEquals("Super off-peak", first.rate.name)
        assertEquals(day.atTime(15, 0), first.endsAt)

        val second = RateReminders.next(plan, alerts, day.atTime(10, 50))!!
        assertEquals(day.atTime(15, 45), second.fireAt)
        assertEquals("Peak", second.rate.name)

        // After peak starts, the next is tomorrow's free window (off-peak at 3pm and 9pm is skipped).
        val third = RateReminders.next(plan, alerts, day.atTime(16, 0))!!
        assertEquals(day.plusDays(1).atTime(10, 45), third.fireAt)
    }

    @Test
    fun `no reminders when off or on a flat plan`() {
        assertNull(RateReminders.next(plan, alerts.copy(rateReminders = false), day.atTime(9, 0)))
        assertNull(RateReminders.next(Tariff(), alerts, day.atTime(9, 0)))
    }
}
