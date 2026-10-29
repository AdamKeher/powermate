package com.aksevenfour.powermate.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerpalProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `u32 round trips little-endian`() {
        assertArrayEquals(bytes(0x78, 0x56, 0x34, 0x12), PowerpalProtocol.encodeU32(0x12345678))
        assertEquals(0xFFFF_FFFFL, PowerpalProtocol.decodeU32(bytes(0xFF, 0xFF, 0xFF, 0xFF)))
        assertEquals(1_761_609_600L, PowerpalProtocol.decodeU32(PowerpalProtocol.encodeU32(1_761_609_600L)))
    }

    @Test
    fun `pairing code is sent as a u32, matching the ESPHome component`() {
        // 123456 = 0x0001E240
        assertArrayEquals(bytes(0x40, 0xE2, 0x01, 0x00), PowerpalProtocol.encodePairingCode(123456))
    }

    @Test
    fun `pairing code must be exactly six digits`() {
        assertEquals(12345, PowerpalProtocol.parsePairingCode("012345"))
        assertEquals(999999, PowerpalProtocol.parsePairingCode(" 999999 "))
        assertNull(PowerpalProtocol.parsePairingCode("12345"))
        assertNull(PowerpalProtocol.parsePairingCode("1234567"))
        assertNull(PowerpalProtocol.parsePairingCode("12a456"))
    }

    @Test
    fun `time range is start then end`() {
        assertArrayEquals(
            bytes(0x01, 0, 0, 0, 0x02, 0, 0, 0),
            PowerpalProtocol.encodeTimeRange(1, 2),
        )
    }

    @Test
    fun `buffer range decodes first and last timestamps`() {
        val range = PowerpalProtocol.parseBufferRange(PowerpalProtocol.encodeTimeRange(1_700_000_000, 1_700_086_400))!!
        assertEquals(1_700_000_000L, range.first)
        assertEquals(1_700_086_400L, range.last)
        assertTrue(PowerpalProtocol.parseBufferRange(ByteArray(8))!!.isEmpty)
        assertNull(PowerpalProtocol.parseBufferRange(ByteArray(4)))
    }

    @Test
    fun `measurement is timestamp then u16 pulses`() {
        val packet = PowerpalProtocol.encodeU32(1_700_000_060) + bytes(0x2C, 0x01)
        assertEquals(listOf(PowerpalProtocol.Measurement(1_700_000_060, 300)), PowerpalProtocol.parseMeasurements(packet))
    }

    @Test
    fun `short packets yield nothing`() {
        assertTrue(PowerpalProtocol.parseMeasurements(bytes(1, 2, 3, 4, 5)).isEmpty())
    }

    @Test
    fun `packets with trailing bytes are read as one record`() {
        val packet = PowerpalProtocol.encodeU32(1_700_000_060) + bytes(0x0A, 0x00) + bytes(0xAA, 0xBB)
        assertEquals(1, PowerpalProtocol.parseMeasurements(packet).size)
    }

    @Test
    fun `packed records are split while timestamps advance plausibly`() {
        val packet = PowerpalProtocol.encodeU32(1_700_000_060) + bytes(5, 0) +
            PowerpalProtocol.encodeU32(1_700_000_120) + bytes(6, 0) +
            PowerpalProtocol.encodeU32(1_700_000_180) + bytes(7, 0)
        assertEquals(listOf(5, 6, 7), PowerpalProtocol.parseMeasurements(packet).map { it.pulses })
    }

    @Test
    fun `packed records stop at an implausible timestamp`() {
        val packet = PowerpalProtocol.encodeU32(1_700_000_060) + bytes(5, 0) + bytes(0x10, 0x20, 0, 0, 0, 0)
        assertEquals(1, PowerpalProtocol.parseMeasurements(packet).size)
    }

    @Test
    fun `live watts follow the documented formula`() {
        // From the ESPHome notes: ~2370 ms at 1000 imp/kWh is ~1519 W.
        assertEquals(1518.99, PowerpalProtocol.wattsFromInterval(2370, 1000.0), 0.01)
        assertEquals(1125.0, PowerpalProtocol.wattsFromInterval(1000, 3200.0), 0.001)
        assertEquals(0.0, PowerpalProtocol.wattsFromInterval(0, 1000.0), 0.0)
    }

    @Test
    fun `live watts decay when reports stop arriving`() {
        assertEquals(1800.0, PowerpalProtocol.liveWatts(2000, 1500, 1000.0), 0.001)
        // 20 s with no newer pulse means at most one pulse in 20 s.
        assertEquals(180.0, PowerpalProtocol.liveWatts(2000, 20_000, 1000.0), 0.001)
    }

    @Test
    fun `live watts hold through gaps between reports under heavy load`() {
        // At 12 kW the Powerpal reported a 300 ms interval only every 0.4–2.7 s.
        assertEquals(12_000.0, PowerpalProtocol.liveWatts(300, 2_700, 1000.0), 0.001)
        assertEquals(12_000.0, PowerpalProtocol.liveWatts(300, 5_000, 1000.0), 0.001)
        // Silent for longer than that, the load must have dropped.
        assertEquals(360.0, PowerpalProtocol.liveWatts(300, 10_000, 1000.0), 0.001)
    }

    @Test
    fun `average watts smooth uneven pulses`() {
        // Every pulse reported, alternating 1.0 s and 1.5 s apart: 2 Wh every 2.5 s.
        var t = 0L
        val reports = List(20) { i -> (if (i % 2 == 0) 1_000L else 1_500L).let { t += it; LivePower(it, t) } }
        assertEquals(2_880.0, PowerpalProtocol.averageWatts(reports, t, 0, 1000.0)!!, 0.001)
    }

    @Test
    fun `average watts hold between sparse reports and fall once they stop`() {
        // 12 kW, reported every 2.5 s, the last at 10 s.
        val reports = (0..4).map { LivePower(300, it * 2_500L) }
        assertEquals(12_000.0, PowerpalProtocol.averageWatts(reports, 12_000, 0, 1000.0)!!, 0.001)
        // 5 s of the window at 12 kW, then 10 s silent: at most one pulse (1 Wh) since.
        assertEquals((5 * 12_000.0 + 10 * 360.0) / 15, PowerpalProtocol.averageWatts(reports, 20_000, 0, 1000.0)!!, 0.001)
        assertEquals(180.0, PowerpalProtocol.averageWatts(reports, 30_000, 0, 1000.0)!!, 0.001)
        // A minute without a pulse: solar or a battery is covering the house.
        assertEquals(0.0, PowerpalProtocol.averageWatts(reports, 70_000, 0, 1000.0)!!, 0.0)
    }

    @Test
    fun `average watts read zero after a minute listening without a pulse`() {
        assertNull(PowerpalProtocol.averageWatts(emptyList(), 59_000, 0, 1000.0))
        assertEquals(0.0, PowerpalProtocol.averageWatts(emptyList(), 60_000, 0, 1000.0)!!, 0.0)
        // Quiet before listening started proves nothing: 30 s listening caps it at 120 W, not 0.
        assertEquals(120.0, PowerpalProtocol.averageWatts(listOf(LivePower(300, 0)), 130_000, 100_000, 1000.0)!!, 0.001)
        // A slow steady load (40 W, a pulse every 90 s) isn't cut to 0 between its pulses.
        assertEquals(40.0, PowerpalProtocol.averageWatts(listOf(LivePower(90_000, 0)), 80_000, 0, 1000.0)!!, 0.001)
    }

    @Test
    fun `api key formats as a uuid`() {
        val key = PowerpalProtocol.formatApiKey(ByteArray(16) { it.toByte() })
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", key)
    }

    @Test
    fun `timestamps align to the start of their minute`() {
        assertEquals(1_793_150_220L, PowerpalProtocol.minuteOf(1_793_150_220))
        assertEquals(1_793_150_220L, PowerpalProtocol.minuteOf(1_793_150_229))
        assertEquals(1_793_150_220L, PowerpalProtocol.minuteOf(1_793_150_279))
    }

    @Test
    fun `serial comes from the advertised name`() {
        assertEquals("0002a1b2", PowerpalProtocol.serialFromName("Powerpal 0002a1b2"))
        assertNull(PowerpalProtocol.serialFromName("Powerpal"))
        assertNull(PowerpalProtocol.serialFromName(null))
    }
}
