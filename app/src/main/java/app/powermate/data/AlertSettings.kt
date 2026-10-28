package app.powermate.data

import org.json.JSONException
import org.json.JSONObject

/** Which notifications the user wants, and their thresholds. */
data class AlertSettings(
    /** Before the cheapest and the most expensive rate windows begin. */
    val rateReminders: Boolean = true,
    val reminderLeadMinutes: Int = 15,
    /** Sustained draw above [highUsageWatts] at any time. */
    val highUsage: Boolean = true,
    val highUsageWatts: Int = 5_000,
    /** Sustained draw above [peakUsageWatts] while the most expensive rate applies. */
    val peakUsage: Boolean = true,
    val peakUsageWatts: Int = 2_000,
    /** Always-on load noticeably higher than the week before. */
    val alwaysOnCreep: Boolean = true,
    val batteryLow: Boolean = true,
    val dailySummary: Boolean = false,
    val weeklySummary: Boolean = true,
    /** Wholesale price spikes forecast in the next hour or so. Mostly useful on wholesale plans like Amber. */
    val priceSpikes: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("rateReminders", rateReminders)
        .put("reminderLead", reminderLeadMinutes)
        .put("highUsage", highUsage)
        .put("highUsageWatts", highUsageWatts)
        .put("peakUsage", peakUsage)
        .put("peakUsageWatts", peakUsageWatts)
        .put("alwaysOnCreep", alwaysOnCreep)
        .put("batteryLow", batteryLow)
        .put("dailySummary", dailySummary)
        .put("weeklySummary", weeklySummary)
        .put("priceSpikes", priceSpikes)
        .toString()

    companion object {
        fun fromJson(json: String?): AlertSettings {
            if (json.isNullOrBlank()) return AlertSettings()
            return try {
                val o = JSONObject(json)
                val d = AlertSettings()
                AlertSettings(
                    rateReminders = o.optBoolean("rateReminders", d.rateReminders),
                    reminderLeadMinutes = o.optInt("reminderLead", d.reminderLeadMinutes),
                    highUsage = o.optBoolean("highUsage", d.highUsage),
                    highUsageWatts = o.optInt("highUsageWatts", d.highUsageWatts),
                    peakUsage = o.optBoolean("peakUsage", d.peakUsage),
                    peakUsageWatts = o.optInt("peakUsageWatts", d.peakUsageWatts),
                    alwaysOnCreep = o.optBoolean("alwaysOnCreep", d.alwaysOnCreep),
                    batteryLow = o.optBoolean("batteryLow", d.batteryLow),
                    dailySummary = o.optBoolean("dailySummary", d.dailySummary),
                    weeklySummary = o.optBoolean("weeklySummary", d.weeklySummary),
                    priceSpikes = o.optBoolean("priceSpikes", d.priceSpikes),
                )
            } catch (e: JSONException) {
                AlertSettings()
            }
        }
    }
}
