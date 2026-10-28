package app.powermate.ble

import kotlin.math.min

/** Encoding and decoding of Powerpal payloads. Free of Android types so it can be unit tested. */
object PowerpalProtocol {
    /** Bytes per buffered record: u32 timestamp + u16 pulses. */
    const val RECORD_SIZE = 6

    /** Minutes per buffered record. One minute is what every known client uses. */
    const val RECORD_MINUTES = 1
    const val RECORD_SECONDS = RECORD_MINUTES * 60

    /**
     * Records stamped before this (2020-01-01) were taken while the device clock was unset,
     * e.g. straight after a battery change, and their times are meaningless.
     */
    const val MIN_VALID_TIMESTAMP = 1_577_836_800L

    private const val MILLIS_PER_KWH_PULSE_NUMERATOR = 3.6e9

    data class Measurement(val timestamp: Long, val pulses: Int)

    data class BufferRange(val first: Long, val last: Long) {
        /** The device reports zeros until its clock has been set and it has recorded something. */
        val isEmpty: Boolean get() = first == 0L || last == 0L
    }

    /** How far the newest record may trail the phone's clock before it's worth a warning. */
    const val CLOCK_WARNING_SECONDS = 15 * 60L

    fun encodeU32(value: Long): ByteArray {
        require(value in 0..0xFFFF_FFFFL) { "$value does not fit in a u32" }
        return byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )
    }

    fun decodeU32(bytes: ByteArray, offset: Int = 0): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    fun decodeU16(bytes: ByteArray, offset: Int = 0): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** The pairing code printed on the Powerpal's card: exactly six digits, leading zeros allowed. */
    fun parsePairingCode(text: String): Int? {
        val digits = text.trim()
        return if (digits.length == 6 && digits.all { it in '0'..'9' }) digits.toInt() else null
    }

    fun encodePairingCode(code: Int): ByteArray {
        require(code in 0..999_999) { "Pairing codes are six digits" }
        return encodeU32(code.toLong())
    }

    fun encodeTimeRange(start: Long, end: Long): ByteArray = encodeU32(start) + encodeU32(end)

    fun parseBufferRange(bytes: ByteArray): BufferRange? =
        if (bytes.size < 8) null else BufferRange(decodeU32(bytes, 0), decodeU32(bytes, 4))

    /**
     * Decodes a measurement notification. The reference implementation only ever reads the first
     * record. A packet that is an exact multiple of [RECORD_SIZE] is read as several records, but only
     * while each timestamp follows the last within a day, so a single record with trailing fields we
     * don't know about is never misread as two.
     */
    fun parseMeasurements(packet: ByteArray): List<Measurement> {
        if (packet.size < RECORD_SIZE) return emptyList()
        val count = if (packet.size % RECORD_SIZE == 0) packet.size / RECORD_SIZE else 1
        val records = ArrayList<Measurement>(count)
        for (i in 0 until count) {
            val offset = i * RECORD_SIZE
            val record = Measurement(decodeU32(packet, offset), decodeU16(packet, offset + 4))
            if (i > 0 && record.timestamp - records.last().timestamp !in 1..86_400) break
            records += record
        }
        return records
    }

    /** Power implied by a pulse interval: one pulse is 1/[pulsesPerKwh] kWh, i.e. 3.6e9/ppk watt-milliseconds. */
    fun wattsFromInterval(intervalMs: Long, pulsesPerKwh: Double): Double =
        if (intervalMs <= 0 || pulsesPerKwh <= 0) 0.0 else MILLIS_PER_KWH_PULSE_NUMERATOR / (pulsesPerKwh * intervalMs)

    /**
     * Live power, allowing for time since the last report. If more time has passed than the last pulse
     * interval without a newer report, the load can't be higher than one pulse over the elapsed time.
     */
    fun liveWatts(intervalMs: Long, ageMs: Long, pulsesPerKwh: Double): Double {
        val reported = wattsFromInterval(intervalMs, pulsesPerKwh)
        return if (ageMs > intervalMs) min(reported, wattsFromInterval(ageMs, pulsesPerKwh)) else reported
    }

    /** Start of the minute containing [timestamp]. */
    fun minuteOf(timestamp: Long): Long = timestamp - Math.floorMod(timestamp, 60L)

    fun kwh(pulses: Long, pulsesPerKwh: Double): Double = if (pulsesPerKwh <= 0) 0.0 else pulses / pulsesPerKwh

    /** Formats the 16-byte [PowerpalUuids.API_KEY] value the way Powerpal's cloud expects it. */
    fun formatApiKey(bytes: ByteArray): String = buildString {
        bytes.forEachIndexed { i, b ->
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            append(HEX[(b.toInt() shr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }

    /** The Powerpal advertises as "Powerpal 0001abcd"; the suffix is its cloud device ID. */
    fun serialFromName(name: String?): String? =
        name?.trim()?.substringAfter(' ', "")?.trim()?.takeIf { it.isNotEmpty() }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { b ->
            append(HEX[(b.toInt() shr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }

    private const val HEX = "0123456789abcdef"
}
