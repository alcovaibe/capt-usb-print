package com.cryonum.captusbprint.core

import java.util.Calendar
import java.util.TimeZone

/** Commands and job layout verified against ValdikSS/captdriver's LBP2900 path. */
object CaptJobProtocol {
    const val RESERVE_UNIT = 0xA2A0
    const val GET_PRINTER_INFO = 0xA1A1
    const val GET_BASIC_STATUS = 0xE0A0
    const val GET_EXTENDED_STATUS = 0xA0A8
    const val GET_INPUT_STATUS = 0xA0A1
    const val SET_POWER_DOWN = 0xE0BA
    const val SET_JOB_INFO2 = 0xE1A1
    const val GO_OFFLINE = 0xE0A6
    const val GO_ONLINE = 0xE0A5
    const val DISCARD_DATA = 0xE0A4
    const val CLEAR_MIS_PRINT = 0xE0A3
    const val CLEAR_ERROR = 0xE0A2
    const val BLACK_END = 0xC0A4
    const val START_PRINT = 0xE0A7
    const val RELEASE_UNIT = 0xE0A9

    const val START_FLAG = 1
    const val END_FLAG = 6

    fun reserveRequest(): ByteArray = ByteArray(8)
    fun powerDownSettings(): ByteArray = byteArrayOf(0, 0)
    fun goOnlineSettings(): ByteArray = byteArrayOf(
        0xEE.toByte(), 0xDB.toByte(), 0xEA.toByte(), 0xAD.toByte(), 0, 0, 0, 0
    )

    fun parseReservedJob(frame: ByteArray): Int {
        val payload = CaptFrame.parseReply(RESERVE_UNIT, frame)
        require(payload.size >= 4) { "ReserveUnit reply has no JobID" }
        require((payload[0].toInt() and 0x80) == 0) {
            "ReserveUnit signaled an error; no automatic recovery"
        }
        val jobId = u8(payload[2]) or (u8(payload[3]) shl 8)
        require(jobId != 0) { "ReserveUnit returned zero JobID" }
        return jobId
    }

    fun pageNumber(page: Int): ByteArray {
        require(page in 1..65535)
        return byteArrayOf(page.toByte(), (page ushr 8).toByte())
    }

    /** Upstream 72-byte SetJobInfo2, with no host/user/job identifying strings. */
    fun jobInfo(jobId: Int, flag: Int, nowMillis: Long = System.currentTimeMillis(),
                timeZone: TimeZone = TimeZone.getDefault()): ByteArray {
        require(jobId in 1..65535)
        require(flag == START_FLAG || flag == END_FLAG)
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val data = ByteArray(72)
        data[16] = flag.toByte()
        data[17] = 1  // one-up
        word(data, 18, jobId)
        // CAPT offset is minutes WEST of UTC, opposite Java TimeZone.getOffset.
        val west = -timeZone.getOffset(nowMillis) / 60000
        word(data, 20, west and 0xffff)
        word(data, 22, west and 0xffff)
        word(data, 24, calendar.get(Calendar.YEAR))
        data[26] = (calendar.get(Calendar.MONTH) + 1).toByte()
        data[27] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        data[28] = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        data[29] = calendar.get(Calendar.MINUTE).toByte()
        data[30] = calendar.get(Calendar.SECOND).toByte()
        return data
    }

    private fun word(bytes: ByteArray, at: Int, value: Int) {
        require(value in 0..65535)
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }

    private fun u8(value: Byte) = value.toInt() and 0xff
}
