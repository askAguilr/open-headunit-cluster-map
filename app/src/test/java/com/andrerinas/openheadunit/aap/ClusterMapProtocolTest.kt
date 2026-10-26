package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class ClusterMapProtocolTest {
    @Test fun `secondary display survives protobuf serialization with its own identity and codec`() {
        val video = Control.Service.parseFrom(ClusterMapProtocol.videoService().toByteArray())
        assertNotEquals(Channel.ID_VID, video.id)
        assertEquals(Channel.ID_CLUSTER_VIDEO, video.id)
        with(video.mediaSinkService) {
            assertEquals(1, displayId)
            assertEquals(1, displayType)
            assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, availableType)
            assertEquals(1, videoConfigsCount)
            with(videoConfigsList.single()) {
                assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480, codecResolution)
                assertEquals(0, marginWidth)
                assertEquals(0, marginHeight)
                assertEquals(160, density)
                assertTrue(hasUiConfig())
                assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, videoCodecType)
            }
        }
        val input = Control.Service.parseFrom(ClusterMapProtocol.inputService().toByteArray())
        assertNotEquals(Channel.ID_INP, input.id)
        assertNotEquals(video.id, input.id)
        assertEquals(video.mediaSinkService.displayId, input.inputSourceService.displayId)
        assertFalse(input.inputSourceService.hasTouchscreen())
    }

    @Test fun `cluster frames reassemble at every boundary without mixing main video or audio`() {
        val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e)
        val data = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1, 0x65, 9, 8, 7)
        for (bytes in listOf(csd, data)) for (split in 1 until bytes.size) {
            val acks = mutableListOf<Int>()
            val assembler = AapMessageReassembler(onDroppedMediaData = { acks.add(it) })
            assertNull(assembler.accept(fragment(Channel.ID_CLUSTER_VIDEO, 9, bytes.copyOfRange(0, split)), bytes.size))
            val main = fragment(Channel.ID_VID, 11, byteArrayOf(0, 0) + ByteArray(8) + byteArrayOf(0, 0, 1, 0x65))
            assertSame(main, assembler.accept(main, 0))
            val audio = fragment(Channel.ID_AUD, 11, byteArrayOf(0, 0) + ByteArray(8) + byteArrayOf(42))
            assertSame(audio, assembler.accept(audio, 0))
            val done = assembler.accept(fragment(Channel.ID_CLUSTER_VIDEO, 10, bytes.copyOfRange(split, bytes.size)), 0)!!
            assertArrayEquals(bytes, done.data.copyOf(done.size))
            assertEquals(Channel.ID_CLUSTER_VIDEO, done.channel)
            assertEquals(2, done.dataOffset)
            assertEquals(bytes[1].toInt(), done.type)
            assertTrue(acks.isEmpty())
            val offset = ClusterMapProtocol.payloadOffset(done.type, done.dataOffset, done.size)!!
            assertEquals(if (done.type == 0) 10 else 2, offset)
        }
    }

    @Test fun `rejected cluster DATA returns exactly one credit and rejected codec config returns none`() {
        for (type in 0..1) {
            val credits = mutableListOf<Int>()
            val assembler = AapMessageReassembler(onDroppedMediaData = { credits.add(it) })
            assembler.accept(fragment(Channel.ID_CLUSTER_VIDEO, 9, byteArrayOf(0, type.toByte(), 1)), 6)
            assertNull(assembler.accept(fragment(Channel.ID_CLUSTER_VIDEO, 10, byteArrayOf(2)), 0))
            assembler.accept(fragment(Channel.ID_CLUSTER_VIDEO, 10, byteArrayOf(3)), 0)
            assertEquals(if (type == 0) listOf(Channel.ID_CLUSTER_VIDEO) else emptyList<Int>(), credits)
        }
    }

    @Test fun `DATA timestamp and CODEC_CONFIG offsets reject incomplete headers`() {
        assertNull(ClusterMapProtocol.payloadOffset(0, 2, 9))
        assertNull(ClusterMapProtocol.payloadOffset(0, 2, 10))
        assertNull(ClusterMapProtocol.payloadOffset(1, 2, 2))
        assertNull(ClusterMapProtocol.payloadOffset(1, -1, 100))
        assertNull(ClusterMapProtocol.payloadOffset(0x8000, 2, 100))
        assertEquals(10, ClusterMapProtocol.payloadOffset(0, 2, 11))
        assertEquals(2, ClusterMapProtocol.payloadOffset(1, 2, 3))
        assertEquals(66051L, ClusterMapProtocol.timestampUs(byteArrayOf(0, 0) + byteArrayOf(0, 0, 0, 0, 0, 1, 2, 3), 2, 10))
    }

    @Test fun `automatic PiP is withheld for phone keyboard covers disabled sessions and activity exit`() {
        assertTrue(ClusterMapProtocol.shouldAutoEnter(true, false, false, false))
        assertFalse(ClusterMapProtocol.shouldAutoEnter(true, false, false, true))
        assertFalse(ClusterMapProtocol.shouldAutoEnter(false, false, false, false))
        assertFalse(ClusterMapProtocol.shouldAutoEnter(true, true, false, false))
        assertFalse(ClusterMapProtocol.shouldAutoEnter(true, false, true, false))
    }

    @Test fun `Annex B scanning identifies mixed prefixes without mistaking emulation prevention for a delimiter`() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 3, 1)
        val pps = byteArrayOf(0, 0, 1, 0x68, 5)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 8)
        val nals = ClusterMapH264.nals(sps + pps + idr)
        assertEquals(listOf(7, 8, 5), nals.map { it.type })
        assertArrayEquals(sps, nals[0].bytes)
        assertArrayEquals(pps, nals[1].bytes)
        assertArrayEquals(idr, nals[2].bytes)
        assertTrue(ClusterMapH264.nals(byteArrayOf(0, 0, 1)).isEmpty())
    }

    private fun fragment(channel: Int, flags: Int, data: ByteArray): AapMessage {
        val first = flags and 1 != 0
        val type = if (first && data.size >= 2) (data[0].toInt() and 255) * 256 + (data[1].toInt() and 255) else -1
        return AapMessage(channel, flags.toByte(), type, if (first) 2 else 0, data.size, data)
    }
}
