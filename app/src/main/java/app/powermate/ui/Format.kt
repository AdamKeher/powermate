package app.powermate.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** Display formatting. Costs are carried as cents (or pence) and shown in the phone's currency. */
object Format {
    private val currency: NumberFormat get() = NumberFormat.getCurrencyInstance(Locale.getDefault())

    /** Splits power into a number and unit for large displays, e.g. ("1,234", "W") or ("12.3", "kW"). */
    fun powerParts(watts: Double): Pair<String, String> =
        if (watts >= 10_000) String.format(Locale.getDefault(), "%.1f", watts / 1000) to "kW"
        else String.format(Locale.getDefault(), "%,d", watts.roundToLong()) to "W"

    fun power(watts: Double): String = powerParts(watts).let { (value, unit) -> "$value $unit" }

    fun kwh(kwh: Double): String = when {
        kwh < 10 -> String.format(Locale.getDefault(), "%.2f kWh", kwh)
        kwh < 100 -> String.format(Locale.getDefault(), "%.1f kWh", kwh)
        else -> String.format(Locale.getDefault(), "%,.0f kWh", kwh)
    }

    fun money(cents: Double): String = currency.format(cents / 100.0)

    /** Prices keep their precision (plans often quote three or more decimals) but show at least two. */
    private fun price(cents: Double): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 5
        isGroupingUsed = false
    }.format(cents)

    fun rate(centsPerKwh: Double): String = "${price(centsPerKwh)} c/kWh"

    fun supply(centsPerDay: Double): String = "${price(centsPerDay)} c/day"

    /** Plain number for editing: no rounding beyond what a Double can't represent, no trailing zeros. */
    fun number(value: Double): String =
        java.math.BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Keeps digits and a single decimal point, for price fields. */
    fun decimalInput(text: String, maxLength: Int = 12): String {
        val cleaned = text.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
        val point = cleaned.indexOf('.')
        val single = if (point < 0) cleaned else cleaned.substring(0, point + 1) + cleaned.substring(point + 1).replace(".", "")
        return single.take(maxLength)
    }

    fun dateTime(epochSeconds: Long): String =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

    fun ago(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0) return "never"
        val seconds = (nowMillis - epochMillis) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} h ago"
            else -> Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }

    fun date(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}
