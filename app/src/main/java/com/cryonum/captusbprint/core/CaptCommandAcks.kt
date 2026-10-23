package com.cryonum.captusbprint.core

/** These commands return an Error byte (zero means accepted), then an opaque byte.
 * Status queries, ReserveUnit and SetPowerDown have different contracts.
 * SetPowerDown is followed by an ExtendedStatus check, as in the pinned prologue.
 */
object CaptCommandAcks {
    private val commands = setOf(
        CaptJobProtocol.SET_JOB_INFO2,
        CaptJobProtocol.GO_OFFLINE, CaptJobProtocol.GO_ONLINE,
        CaptJobProtocol.DISCARD_DATA, CaptJobProtocol.CLEAR_MIS_PRINT,
        CaptJobProtocol.CLEAR_ERROR, CaptJobProtocol.START_PRINT, CaptJobProtocol.RELEASE_UNIT
    )

    fun requireAccepted(command: Int, frame: ByteArray) {
        if (command !in commands) return
        val payload = CaptFrame.parseReply(command, frame)
        // Do not assign a meaning to the second byte or use a status-bit mask here.
        if ((payload[0].toInt() and 255) != 0) {
            throw CaptCommandAckFailure(command, payload[0].toInt() and 255)
        }
    }
}

class CaptCommandAckFailure(val command: Int, val resultByte: Int) :
    IllegalStateException("CAPT control command 0x%04X not accepted: result=0x%02X; no replay".format(
        java.util.Locale.ROOT, command, resultByte))
