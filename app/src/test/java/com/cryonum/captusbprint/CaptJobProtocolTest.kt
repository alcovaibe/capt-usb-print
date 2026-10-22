package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptFrame
import com.cryonum.captusbprint.core.CaptJobProtocol
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CaptJobProtocolTest {
    @Test fun parsesReserveUnitJobIdWithoutGuessing() {
        val frame = CaptFrame.encode(CaptJobProtocol.RESERVE_UNIT,
            byteArrayOf(0, 0, 0x34, 0x12))
        assertEquals(0x1234, CaptJobProtocol.parseReservedJob(frame))
        assertThrows(IllegalArgumentException::class.java) {
            CaptJobProtocol.parseReservedJob(
                CaptFrame.encode(CaptJobProtocol.RESERVE_UNIT, byteArrayOf(0, 0, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptJobProtocol.parseReservedJob(
                CaptFrame.encode(CaptJobProtocol.RESERVE_UNIT, byteArrayOf(0x80.toByte(), 0, 1, 0)))
        }
    }

    @Test fun jobInfoIsAnonymous72BytesWithCorrectLocalClockAndWestOffset() {
        val t = Instant.parse("2026-10-22T08:00:00Z").toEpochMilli()
        val bytes = CaptJobProtocol.jobInfo(0x1234, CaptJobProtocol.START_FLAG, t,
            TimeZone.getTimeZone("GMT+03:00"))
        assertEquals(72, bytes.size)
        assertEquals(1, bytes[16].toInt())
        assertEquals(1, bytes[17].toInt())
        assertEquals(0x34, bytes[18].toInt() and 0xff)
        assertEquals(0x12, bytes[19].toInt() and 0xff)
        assertEquals(0x4c, bytes[20].toInt() and 0xff)
        assertEquals(0xff, bytes[21].toInt() and 0xff)
        assertEquals(bytes[20], bytes[22])
        assertEquals(bytes[21], bytes[23])
        assertEquals(0xea, bytes[24].toInt() and 0xff) // 2026 LE
        assertEquals(7, bytes[25].toInt() and 0xff)
        assertEquals(10, bytes[26].toInt() and 0xff)
        assertEquals(22, bytes[27].toInt() and 0xff)
        assertEquals(11, bytes[28].toInt() and 0xff)
        assertEquals(0, bytes[29].toInt() and 0xff)
        assertTrue(bytes.copyOfRange(32, 72).all { it == 0.toByte() })
        val end = CaptJobProtocol.jobInfo(0x1234, CaptJobProtocol.END_FLAG, t,
            TimeZone.getTimeZone("UTC"))
        assertEquals(6, end[16].toInt())
    }

    @Test fun continuationUsesSameAnonymousJobIdAndSingleFlag2() {
        val instant = Instant.parse("2026-10-22T08:00:00Z").toEpochMilli()
        val continued = CaptJobProtocol.jobInfo(
            0x1234, CaptJobProtocol.CONTINUE_FLAG, instant, TimeZone.getTimeZone("UTC"))
        assertEquals(72, continued.size)
        assertEquals(2, continued[16].toInt() and 255)
        assertEquals(0x34, continued[18].toInt() and 255)
        assertEquals(0x12, continued[19].toInt() and 255)
        assertThrows(IllegalArgumentException::class.java) {
            CaptJobProtocol.jobInfo(0x1234, 4)
        }
        assertArrayEquals(byteArrayOf(2, 0), CaptJobProtocol.pageNumber(2))
    }

    @Test fun pageNumberRequiresNonZeroSixteenBitIndex() {
        assertArrayEquals(byteArrayOf(1, 0), CaptJobProtocol.pageNumber(1))
        assertThrows(IllegalArgumentException::class.java) { CaptJobProtocol.pageNumber(0) }
    }
}
