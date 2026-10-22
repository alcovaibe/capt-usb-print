package com.cryonum.captusbprint.document

interface PreparedCaptPage {
    val bandCount: Int
    val compressedBytes: Int
    fun descriptorFrame(): ByteArray
    fun forEachVideoFrame(chunkLimit: Int, cancelled: () -> Boolean = { false },
                          consumer: (bandIndex: Int, frame: ByteArray) -> Unit)
}
