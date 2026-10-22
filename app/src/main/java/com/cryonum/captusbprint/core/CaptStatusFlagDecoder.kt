package com.cryonum.captusbprint.core

import java.util.Locale

/** Names of CAPT status bits cross-checked against public captdriver definitions. */
object CaptStatusFlagDecoder {
    fun basic(value: Int): List<String> = decode(value, 0xff, 0xdf, listOf(
        0x01 to "PRINTER_FREE",
        0x02 to "NOT_READY",
        0x04 to "CMD_BUSY",
        0x08 to "IM_DATA_BUSY",
        0x10 to "OFFLINE",
        0x40 to "UNIT_FREE",
        0x80 to "ERROR_BIT"
    ))

    fun secondary(value: Int): List<String> = decode(value, 0xff, 0x03, listOf(
        0x01 to "EXTENDED_STATUS_CHANGED",
        0x02 to "NEED_INPUT_STATUS"
    ))

    fun auxiliary(value: Int): List<String> = decode(value, 0xff, 0x86, listOf(
        0x02 to "PRINTER_BUSY",
        0x04 to "PAPER_DELIVERY",
        0x80 to "SAFE_TIMER"
    ))

    fun engineCommunication(value: Int): List<String> = decode(value, 0xff, 0x7f, listOf(
        0x01 to "OVERRUN",
        0x02 to "UNDERRUN",
        0x04 to "MISSING_EOP",
        0x08 to "INVALID_DATA",
        0x10 to "ENGINE_COMM_ERROR",
        0x20 to "ENGINE_RESET",
        0x40 to "PRINT_REJECTED"
    ))

    fun engine(value: Int): List<String> = decode(value, 0xffff, 0x610e, listOf(
        0x0002 to "SERVICE_CALL",
        0x0004 to "CLEANING",
        0x0008 to "ILLEGAL_CONNECTION",
        0x0100 to "PAPER_JAM",
        0x2000 to "NO_CARTRIDGE",
        0x4000 to "DOOR_OPEN"
    ))

    fun paper(value: Int): String = when {
        (value and 0x80) != 0 -> "PAPER_AVAILABLE"
        (value and 0xff) == 0 -> "NO_PAPER"
        else -> "UNKNOWN_0x" + hex(value)
    }

    private fun decode(
        value: Int,
        mask: Int,
        knownMask: Int,
        bits: List<Pair<Int, String>>
    ): List<String> {
        val v = value and mask
        val names = bits.filter { (bit, _) -> v and bit != 0 }.map { it.second }.toMutableList()
        val unknown = v and knownMask.inv() and mask
        if (unknown != 0) names += "UNKNOWN_0x" + if (mask == 0xff) hex(unknown) else hex16(unknown)
        return names.ifEmpty { listOf("NONE") }
    }

    private fun hex(value: Int) = String.format(Locale.ROOT, "%02X", value and 0xff)
    private fun hex16(value: Int) = String.format(Locale.ROOT, "%04X", value and 0xffff)
}
