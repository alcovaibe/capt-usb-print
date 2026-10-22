package com.cryonum.captusbprint.core

/**
 * HISCOA encoder based on ValdikSS/captdriver commit
 * 892f320c019974a5b737e000fcc2c8582264a131.
 * The source and GPLv3-or-later license are bundled under cpp/upstream/.
 */
object HiscoaNative {
    init { System.loadLibrary("capt_hiscoa") }

    external fun encodeBand(pixels: ByteArray, lineSize: Int, lines: Int, lastBand: Boolean): ByteArray

    /** Same upstream HISCOA parameter bytes for origins (1,4,0,-7,0). */
    fun defaultParameters(): ByteArray = byteArrayOf(1, 4, 1, 1, 0, (-7).toByte(), 0, 0)
}
