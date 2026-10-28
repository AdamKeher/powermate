package app.powermate.prices

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** A half-hour of electricity pricing, past or forecast. */
data class PriceInterval(
    val start: Instant,
    val end: Instant,
    /** The price shown: an Amber customer's all-in price (incl. GST), or the wholesale spot price. */
    val centsPerKwh: Double,
    /** Share of the grid's generation from renewables, when known. */
    val renewables: Double? = null,
    val isForecast: Boolean = false,
    /** Amber flags intervals it expects to spike. */
    val spike: Boolean = false,
)

enum class PriceSource(val label: String, val explanation: String) {
    SPOT(
        "Wholesale spot price · Victoria",
        "What generators are paid in the Victorian market. Retail bills add network and other charges, " +
            "so this is a guide to when power is cheap or dear, not what you pay.",
    ),
    AMBER(
        "Your Amber price",
        "What you pay on Amber Electric for this half hour, including network charges and GST.",
    ),
}

/** Prices for roughly the last day and the next day and a half. */
data class PriceOutlook(
    val source: PriceSource,
    val intervals: List<PriceInterval>,
    val fetchedAt: Instant,
) {
    fun current(now: Instant): PriceInterval? = intervals.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }

    /** The coming 24 hours, starting with the current interval. */
    fun upcoming(now: Instant): List<PriceInterval> =
        intervals.filter { it.end.isAfter(now) && it.start.isBefore(now.plus(Duration.ofHours(24))) }
}

/** How a price compares with the rest of the day. */
enum class PriceLevel { GREAT, GOOD, ELEVATED, HIGH }

/** A stretch of notably cheap or expensive prices worth planning around. */
data class PriceWindow(
    val start: Instant,
    val end: Instant,
    val great: Boolean,
    val averageCents: Double,
    /** How much cheaper (great) or dearer (high) than the 24-hour average, as a percentage. */
    val percentFromAverage: Int,
    /** How much greener than the 24-hour average, as a percentage, when renewables are known. */
    val percentGreener: Int?,
)

object PriceAnalysis {
    const val GREAT_RATIO = 0.6
    const val HIGH_RATIO = 1.4
    private const val MIN_WINDOW_INTERVALS = 2

    fun level(cents: Double, average: Double): PriceLevel {
        if (average <= 0) return if (cents <= average) PriceLevel.GREAT else PriceLevel.GOOD
        val ratio = cents / average
        return when {
            ratio <= GREAT_RATIO -> PriceLevel.GREAT
            ratio <= 1.0 -> PriceLevel.GOOD
            ratio < HIGH_RATIO -> PriceLevel.ELEVATED
            else -> PriceLevel.HIGH
        }
    }

    fun average(intervals: List<PriceInterval>): Double =
        if (intervals.isEmpty()) 0.0 else intervals.sumOf { it.centsPerKwh } / intervals.size

    /**
     * Runs of at least an hour that stand out from [intervals]: at least 25% below the average and
     * in the cheapest fifth, or at least 25% above it and in the dearest fifth. Using both keeps
     * the cards to the stretches worth planning around; on a day with a deep solar dip, the average
     * alone would make most of the night look "high".
     */
    fun windows(intervals: List<PriceInterval>): List<PriceWindow> {
        if (intervals.size < MIN_WINDOW_INTERVALS) return emptyList()
        val average = average(intervals)
        if (average <= 0) return emptyList()
        val sorted = intervals.map { it.centsPerKwh }.sorted()
        fun percentile(p: Double) = sorted[((sorted.size - 1) * p).roundToInt()]
        val greatAt = minOf(average * 0.75, percentile(0.2))
        val highAt = maxOf(average * 1.25, percentile(0.8))
        val renewablesAverage = intervals.mapNotNull { it.renewables }.takeIf { it.isNotEmpty() }?.average()
        val found = mutableListOf<PriceWindow>()
        for (great in listOf(true, false)) {
            var run = mutableListOf<PriceInterval>()
            fun close() {
                if (run.size >= MIN_WINDOW_INTERVALS) {
                    val mean = average(run)
                    val greener = renewablesAverage?.takeIf { it > 0 }?.let { overall ->
                        run.mapNotNull { it.renewables }.takeIf { it.isNotEmpty() }?.average()
                            ?.let { ((it - overall) / overall * 100).roundToInt() }
                    }
                    found += PriceWindow(
                        start = run.first().start,
                        end = run.last().end,
                        great = great,
                        averageCents = mean,
                        percentFromAverage = (kotlin.math.abs(mean - average) / average * 100).roundToInt(),
                        percentGreener = greener?.takeIf { it != 0 },
                    )
                }
                run = mutableListOf()
            }
            for (interval in intervals) {
                val price = interval.centsPerKwh
                if ((great && price <= greatAt) || (!great && price >= highAt)) run += interval else close()
            }
            close()
        }
        return found.sortedBy { it.start }
    }
}
