package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptPageProtocol
import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.document.PreparedPdfCaptPage
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class StageCPreparedPdfCaptPageTest {
    private fun page(consume: (Int, ByteArray) -> Unit) {
        var y = 0
        while (y < CaptTestPage.HEIGHT) {
            val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
            consume(y, ByteArray(lines * CaptTestPage.LINE_BYTES))
            y += lines
        }
    }

    @Test fun preparesOneCompletePageAndUsesUnchangedStageBDescriptorAndFrames() {
        var lastCount = 0
        val ready = PreparedPdfCaptPageBuilder.prepare(
            produceBands = ::page,
            encode = { raw, lineBytes, lines, last ->
                assertEquals(CaptTestPage.LINE_BYTES * lines, raw.size)
                assertEquals(620, lineBytes)
                if (last) {
                    lastCount++
                    assertEquals(40, lines)
                }
                byteArrayOf(if (last) 127.toByte() else 1.toByte())
            }
        )
        assertEquals(110, ready.bandCount)
        assertEquals(110, ready.compressedBytes)
        assertEquals(1, ready.maximumBandBytes)
        assertEquals(1, lastCount)
        assertArrayEquals(CaptPageProtocol.beginA4TestPage(), ready.descriptorFrame())

        var count = 0
        var lastSeen = -1
        ready.forEachVideoFrame(65280) { index, frame ->
            assertTrue(index >= lastSeen)
            assertEquals(5, frame.size)
            assertEquals(if (index == 109) 127 else 1, frame[4].toInt() and 0xff)
            count++
            lastSeen = index
        }
        assertEquals(110, count)
        assertEquals(109, lastSeen)
    }

    @Test fun copiesCodecBuffersAndCannotBeMutatedThroughEmittedFrames() {
        val reused = byteArrayOf(17)
        val ready = PreparedPdfCaptPageBuilder.prepare(
            produceBands = ::page,
            encode = { _, _, _, _ -> reused }
        )
        reused[0] = 88
        var frames = 0
        ready.forEachVideoFrame(64) { _, frame ->
            assertEquals(17, frame[4].toInt() and 0xff)
            frame[4] = 99
            frames++
        }
        assertEquals(110, frames)
        ready.forEachVideoFrame(64) { _, frame ->
            assertEquals(17, frame[4].toInt() and 0xff)
        }
    }

    @Test fun splitsOneEncodedBandUsingStageBValidatedPacketizer() {
        val ready = PreparedPdfCaptPageBuilder.prepare(
            produceBands = ::page,
            encode = { _, _, _, _ -> ByteArray(130) { 42 } }
        )
        assertEquals(130 * 110, ready.compressedBytes)
        var count = 0
        ready.forEachVideoFrame(64) { _, frame ->
            assertTrue(frame.size in 5..68)
            count++
        }
        assertEquals(3 * 110, count)
    }

    @Test fun rejectsMoreThanFourMibEvenWhenCodecOutputIsIndividuallyValid() {
        var produced = 0
        assertThrows(IllegalStateException::class.java) {
            PreparedPdfCaptPageBuilder.prepare(
                produceBands = ::page,
                encode = { _, _, _, _ ->
                    produced++
                    ByteArray(40_000)
                }
            )
        }
        assertTrue(produced >= 100)
        assertTrue(produced <= 110)
    }

    @Test fun incompleteAndDuplicateBandsFailBeforeReturningPreparedPage() {
        assertThrows(IllegalStateException::class.java) {
            PreparedPdfCaptPageBuilder.prepare(
                produceBands = { accept -> accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES)) },
                encode = { _, _, _, _ -> byteArrayOf(1) }
            )
        }
        assertThrows(IllegalStateException::class.java) {
            PreparedPdfCaptPageBuilder.prepare(
                produceBands = { accept ->
                    accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES))
                    accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES))
                },
                encode = { _, _, _, _ -> byteArrayOf(1) }
            )
        }
    }

    @Test fun packetConsumptionCancellationDoesNotReplayAnyFrame() {
        val ready = PreparedPdfCaptPageBuilder.prepare(::page,
            { _, _, _, _ -> ByteArray(130) })
        var stop = false
        var count = 0
        assertThrows(CancellationException::class.java) {
            ready.forEachVideoFrame(64, cancelled = { stop }) { _, _ ->
                count++
                stop = true
            }
        }
        assertEquals(1, count)
    }

    @Test fun forbidsUnvalidatedTransferChunkSize() {
        val ready = PreparedPdfCaptPageBuilder.prepare(::page,
            { _, _, _, _ -> byteArrayOf(1) })
        assertThrows(IllegalArgumentException::class.java) {
            ready.forEachVideoFrame(0) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ready.forEachVideoFrame(0xFF01) { _, _ -> }
        }
    }
}
