package com.aksevenfour.powermate.ble

import java.util.Locale
import java.util.UUID

/**
 * GATT layout of the Powerpal, as reverse engineered by
 * https://github.com/pento/powerpal-esphome (and WeekendWarrior1 before it).
 * All multi-byte values on the Powerpal service are little-endian.
 */
object PowerpalUuids {
    private fun powerpal(id: String): UUID = UUID.fromString("59DA$id-12F4-25A6-7D4F-55961DCE4205")
    private fun sig(id: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04X-0000-1000-8000-00805F9B34FB", id))

    val SERVICE: UUID = powerpal("ABCD")

    /** Notify: buffered records, each a u32 timestamp followed by a u16 pulse count. */
    val MEASUREMENT: UUID = powerpal("0001")

    /** Write `[start, end]` (two u32 timestamps) to stream that slice of the buffer over [MEASUREMENT]. */
    val MEASUREMENT_ACCESS: UUID = powerpal("0002")

    /** Notify: u32 milliseconds between the most recent meter pulses. */
    val PULSE: UUID = powerpal("0003")

    /** Read/write: the device clock as u32 Unix seconds. Lost whenever the battery is pulled. */
    val TIME: UUID = powerpal("0004")

    /** Read: `[first, last]` timestamps of the records held in the device buffer. */
    val FIRST_REC: UUID = powerpal("0005")

    /** Read: LED sensor sensitivity. Format not yet understood, so only shown raw. */
    val LED_SENSITIVITY: UUID = powerpal("0008")

    /** Read: 16 bytes which, formatted as a UUID, are the key for Powerpal's cloud API. */
    val API_KEY: UUID = powerpal("0009")

    /** Write: the 6-digit pairing code as a u32. Nothing else on the service works until this is sent. */
    val PAIRING_CODE: UUID = powerpal("0011")

    /** Read/write: minutes per buffered record, as a u32. Writing it is what starts record notifications. */
    val READING_BATCH_SIZE: UUID = powerpal("0013")

    val BATTERY_SERVICE: UUID = sig(0x180F)
    val BATTERY_LEVEL: UUID = sig(0x2A19)

    val DEVICE_INFO_SERVICE: UUID = sig(0x180A)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val SERIAL_NUMBER: UUID = sig(0x2A25)
    val FIRMWARE_REVISION: UUID = sig(0x2A26)
    val HARDWARE_REVISION: UUID = sig(0x2A27)
    val SOFTWARE_REVISION: UUID = sig(0x2A28)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)

    /** Client Characteristic Configuration descriptor, written to enable notifications. */
    val CCCD: UUID = sig(0x2902)

    /** Device-information characteristics worth showing, with display labels. */
    val DEVICE_INFO_FIELDS: List<Pair<String, UUID>> = listOf(
        "Manufacturer" to MANUFACTURER_NAME,
        "Model" to MODEL_NUMBER,
        "Serial number" to SERIAL_NUMBER,
        "Hardware" to HARDWARE_REVISION,
        "Firmware" to FIRMWARE_REVISION,
        "Software" to SOFTWARE_REVISION,
    )

    private val names = mapOf(
        MEASUREMENT to "measurement",
        MEASUREMENT_ACCESS to "measurement_access",
        PULSE to "pulse",
        TIME to "time",
        FIRST_REC to "first_rec",
        LED_SENSITIVITY to "led_sensitivity",
        API_KEY to "api_key",
        PAIRING_CODE to "pairing_code",
        READING_BATCH_SIZE to "reading_batch_size",
        BATTERY_LEVEL to "battery_level",
        CCCD to "cccd",
    ) + DEVICE_INFO_FIELDS.associate { (label, uuid) -> uuid to label.lowercase(Locale.ROOT) }

    /** Short name for logs. */
    fun nameOf(uuid: UUID): String = names[uuid] ?: uuid.toString()
}
