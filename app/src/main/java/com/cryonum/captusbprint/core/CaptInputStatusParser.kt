package com.cryonum.captusbprint.core

/**
 * Raw read-only GetInputStatus reply.
 *
 * The public captdriver sends this query when NEED_INPUT_STATUS is set but
 * does not decode the returned payload. Keep the payload opaque until its
 * fields are independently documented or validated on hardware.
 */
object CaptInputStatusParser {
    const val GET_INPUT_STATUS = 0xA0A1

    fun parse(frame: ByteArray): ByteArray =
        CaptFrame.parseReply(GET_INPUT_STATUS, frame)
}
