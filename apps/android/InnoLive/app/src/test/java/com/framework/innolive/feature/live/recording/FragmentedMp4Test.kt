package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FragmentedMp4Test {
    @get:Rule val folder = TemporaryFolder()

    private fun finalMoov(file: File): TestBox = readTopLevel(file).last { it.type == "moov" }

    private fun track(moov: TestBox, handler: String): TestBox = moov.children().first { trak ->
        trak.type == "trak" && trak.path("mdia", "hdlr")!!.let { String(it.data, it.payloadStart + 8, 4) } == handler
    }

    /** stbl 표에서 표본 크기와 파일 위치를 다시 계산합니다. */
    private fun samples(trak: TestBox): List<Pair<Long, Int>> {
        val stbl = trak.path("mdia", "minf", "stbl")!!
        val stsz = stbl.child("stsz")!!
        val constant = stsz.int(4)
        val count = stsz.int(8)
        val sizes = List(count) { if (constant != 0) constant else stsz.int(12 + it * 4) }
        val co64 = stbl.child("co64")!!
        val offsets = List(co64.int(4)) { co64.long(8 + it * 8) }
        val stsc = stbl.child("stsc")!!
        val entries = List(stsc.int(4)) { stsc.int(8 + it * 12) to stsc.int(12 + it * 12) }
        val result = mutableListOf<Pair<Long, Int>>()
        var sample = 0
        offsets.forEachIndexed { chunk, offset ->
            val perChunk = entries.last { it.first <= chunk + 1 }.second
            var position = offset
            repeat(perChunk) {
                result += position to sizes[sample]
                position += sizes[sample++]
            }
        }
        return result
    }

    private fun emptyEditMs(trak: TestBox): Long? = trak.path("edts", "elst")?.let { elst ->
        assertEquals(-1L, elst.long(16))
        elst.long(8)
    }

    @Test fun recordingIsReadableWhileWritingAndBecomesAPlainMp4() {
        val file = folder.newFile("rec.mp4")
        val writer = writeSyntheticRecording(file, seconds = 3, finish = false)
        // 녹화 중에도 다 쓴 조각은 그대로 파일에 있다.
        val live = readTopLevel(file)
        assertEquals(listOf("ftyp", "moov"), live.take(2).map { it.type })
        assertNotNull(live[1].child("mvex"))
        assertTrue(live.count { it.type == "moof" } >= 2)
        writer.finish()
        assertEquals(90L, writer.videoSamples)

        val result = Mp4Finalizer.finish(file)
        assertEquals(90, result.videoSamples)
        assertEquals(result.audioSamples.toLong(), writer.audioSamples)
        assertTrue(result.durationUs in 2_900_000L..3_100_000L)
        val top = readTopLevel(file)
        // 녹화 중 트랙 설명과 조각 머리는 건너뛰는 상자가 되고 표본 표는 끝의 moov에 있다.
        assertEquals("free", top[1].type)
        assertTrue(top.none { it.type == "moof" })
        assertEquals("moov", top.last().type)
        assertNull(top.last().child("mvex"))

        val bytes = file.readBytes()
        val video = samples(track(top.last(), "vide"))
        assertEquals(90, video.size)
        video.forEachIndexed { index, (offset, size) ->
            assertArrayEquals(lengthPrefixedSample(index, index % 30 == 0), bytes.copyOfRange(offset.toInt(), offset.toInt() + size))
        }
        val stss = track(top.last(), "vide").path("mdia", "minf", "stbl", "stss")!!
        assertEquals(listOf(1, 31, 61), List(stss.int(4)) { stss.int(8 + it * 4) })
        val audio = samples(track(top.last(), "soun"))
        audio.forEachIndexed { index, (offset, size) ->
            assertEquals(64, size)
            assertEquals(index.toByte(), bytes[offset.toInt()])
        }
    }

    @Test fun laterTrackStartAndVideoShiftBecomeEmptyEdits() {
        val file = folder.newFile("late.mp4")
        writeSyntheticRecording(file, seconds = 2, videoStartUs = 500_000L)
        val result = Mp4Finalizer.finish(file)
        assertEquals(500_000L, result.videoStartUs)
        assertEquals(500L, emptyEditMs(track(finalMoov(file), "vide")))
        assertNull(emptyEditMs(track(finalMoov(file), "soun")))

        // 서버 반환 영상: 표시 시각이 700ms 늦은 영상을 700ms 당기면 음성이 200ms 늦게 시작한다.
        val shifted = folder.newFile("shifted.mp4")
        writeSyntheticRecording(shifted, seconds = 2, videoStartUs = 500_000L)
        val aligned = Mp4Finalizer.finish(shifted, videoShiftUs = -700_000L)
        assertEquals(0L, aligned.videoStartUs)
        assertEquals(200_000L, aligned.audioStartUs)
        assertNull(emptyEditMs(track(finalMoov(shifted), "vide")))
        assertEquals(200L, emptyEditMs(track(finalMoov(shifted), "soun")))
    }

    @Test fun crashAtAnyPointKeepsEveryCompleteFragment() {
        val reference = folder.newFile("reference.mp4")
        writeSyntheticRecording(reference, seconds = 4)
        val fragmentEnds = readTopLevel(reference).filter { it.type == "mdat" }.map { it.start + it.size }
        val length = reference.length()
        for (cut in listOf(length - 1, length - 300, fragmentEnds[1] + 10L, fragmentEnds[1].toLong())) {
            val copy = folder.newFile("cut-$cut.mp4")
            reference.copyTo(copy, overwrite = true)
            RandomAccessFile(copy, "rw").use { it.setLength(cut) }
            val result = Mp4Finalizer.finish(copy)
            val complete = fragmentEnds.count { it <= cut }
            assertEquals(complete, readTopLevel(copy).count { it.type == "skip" })
            assertTrue(result.videoSamples > 0)
            assertTrue(result.truncatedBytes >= 0)
        }
    }

    @Test fun finalizingTwiceOrAfterAnInterruptedFinalizeGivesTheSameFile() {
        val file = folder.newFile("twice.mp4")
        writeSyntheticRecording(file, seconds = 3)
        val first = Mp4Finalizer.finish(file)
        val bytes = file.readBytes()
        assertEquals(first, Mp4Finalizer.finish(file).copy(truncatedBytes = first.truncatedBytes))
        assertArrayEquals(bytes, file.readBytes())

        // 마무리 중 moov만 붙이고 멈춘 파일(조각 머리는 아직 moof)도 다시 마무리된다.
        val interrupted = folder.newFile("interrupted.mp4")
        writeSyntheticRecording(interrupted, seconds = 3)
        Mp4Finalizer.finish(interrupted)
        val restored = interrupted.readBytes()
        readBoxes(restored).filter { it.type == "skip" }.take(2).forEach { box ->
            "moof".toByteArray().copyInto(restored, box.start + 4)
        }
        "moov".toByteArray().copyInto(restored, readBoxes(restored)[1].start + 4)
        interrupted.writeBytes(restored)
        Mp4Finalizer.finish(interrupted)
        assertArrayEquals(bytes, interrupted.readBytes())
    }

    @Test fun recordingWithoutAudioCannotBeFinalized() {
        val file = folder.newFile("silent.mp4")
        val writer = FragmentedMp4Writer(file, TEST_VIDEO, TEST_AUDIO, 0L)
        repeat(60) { writer.writeVideo(lengthPrefixedSample(it, it % 30 == 0), it * 33_333L, it % 30 == 0) }
        writer.finish()
        val before = file.readBytes()
        try {
            Mp4Finalizer.finish(file)
            fail("음성 표본이 없으면 마무리하지 않는다")
        } catch (_: Mp4RepairException) {
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun writerRejectsNonIncreasingAndPreEpochSamples() {
        val writer = FragmentedMp4Writer(folder.newFile("order.mp4"), TEST_VIDEO, TEST_AUDIO, 1_000_000L)
        assertTrue(!writer.writeAudio(ByteArray(4), 999_999L))
        assertTrue(writer.writeAudio(ByteArray(4), 1_000_000L))
        assertTrue(!writer.writeAudio(ByteArray(4), 1_000_000L))
        assertTrue(!writer.writeAudio(ByteArray(4), 1_000_010L))
        assertTrue(writer.writeAudio(ByteArray(4), 1_021_333L))
        writer.close()
    }

    @Test fun trackExtractionKeepsSamplesAndStartOffset() {
        val source = folder.newFile("source.mp4")
        writeSyntheticRecording(source, seconds = 2, videoStartUs = 300_000L)
        Mp4Finalizer.finish(source)
        val video = File(folder.root, "video.mp4")
        val audio = File(folder.root, "audio.m4a")
        assertEquals(51, Mp4TrackExtractor.extract(source, video, audio = false))
        assertTrue(Mp4TrackExtractor.extract(source, audio, audio = true) > 90)

        val videoMoov = finalMoov(video)
        assertEquals(1, videoMoov.children().count { it.type == "trak" })
        val videoTrak = track(videoMoov, "vide")
        assertEquals(300L, emptyEditMs(videoTrak))
        val bytes = video.readBytes()
        samples(videoTrak).forEachIndexed { index, (offset, size) ->
            assertArrayEquals(lengthPrefixedSample(index, index % 30 == 0), bytes.copyOfRange(offset.toInt(), offset.toInt() + size))
        }
        val audioTrak = track(finalMoov(audio), "soun")
        assertNull(emptyEditMs(audioTrak))
        assertEquals(Mp4Codec.audioSampleDescription(TEST_AUDIO).size,
            audioTrak.path("mdia", "minf", "stbl", "stsd")!!.size)
    }

    @Test fun annexBOutputBecomesLengthPrefixedWithoutParameterSets() {
        val annexB = byteArrayOf(0, 0, 0, 1) + TEST_SPS + byteArrayOf(0, 0, 1) + TEST_PPS +
            byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3) + byteArrayOf(0, 0, 1, 0x65, 4, 5)
        val sample = checkNotNull(Mp4Codec.annexBToLengthPrefixed(ByteBuffer.wrap(annexB)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 0x65, 1, 2, 3, 0, 0, 0, 3, 0x65, 4, 5), sample)
        val (sps, pps) = Mp4Codec.parameterSets(byteArrayOf(0, 0, 0, 1) + TEST_SPS, byteArrayOf(0, 0, 0, 1) + TEST_PPS)
        assertArrayEquals(TEST_SPS, sps.single())
        assertArrayEquals(TEST_PPS, pps.single())
        assertNull(Mp4Codec.annexBToLengthPrefixed(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + TEST_SPS)))
    }
}
