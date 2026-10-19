package com.framework.innolive.feature.live.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 저장한 녹화(일반 MP4)에서 트랙 하나만 새 파일로 옮깁니다. 영상·음성 분리 내보내기에 씁니다.
 *
 * 재인코딩 없이 표본 바이트를 복사하고 원래 파일의 앞 빈 구간(elst)을 그대로 남겨, 두 파일을 다시 맞추면
 * 원래 시각과 같습니다(iOS 분리 내보내기의 "offset 유지"와 같음). 표본 표는 원래 파일 moov에서 읽습니다.
 */
internal object Mp4TrackExtractor {
    private class Box(val position: Long, val size: Long, val headerSize: Int, val type: String) {
        val end: Long get() = position + size
        val payload: Long get() = position + headerSize
    }

    private class Track(
        val id: Int,
        val audio: Boolean,
        val timescale: Int,
        val mediaDuration: Long,
        val emptyEditMs: Long,
        val width: Long,
        val height: Long,
        val stsd: ByteArray,
        val stts: ByteArray,
        val stss: ByteArray?,
        val sizes: IntArray,
        val chunkOffsets: LongArray,
        val chunkCounts: IntArray,
    )

    /** [audio]가 true면 음성, false면 영상 트랙을 [target]에 씁니다. 옮긴 표본 수를 돌려줍니다. */
    fun extract(source: File, target: File, audio: Boolean): Int {
        RandomAccessFile(source, "r").use { input ->
            val channel = input.channel
            val track = readTracks(channel).firstOrNull { it.audio == audio }
                ?: throw Mp4RepairException(if (audio) "음성 트랙이 없습니다." else "영상 트랙이 없습니다.")
            target.delete()
            try {
                RandomAccessFile(target, "rw").use { output -> write(channel, output.channel, track) }
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            return track.sizes.size
        }
    }

    private fun write(input: FileChannel, output: FileChannel, track: Track) {
        val head = Mp4ByteSink(64)
        Mp4Codec.ftyp(head)
        val dataBytes = track.sizes.sumOf { it.toLong() }
        head.u32(1L); head.fourcc("mdat"); head.u64(16L + dataBytes)
        writeAt(output, 0L, head.asByteBuffer())
        var position = head.size.toLong()
        val newOffsets = LongArray(track.chunkOffsets.size)
        var sample = 0
        for (chunk in track.chunkOffsets.indices) {
            var bytes = 0L
            repeat(track.chunkCounts[chunk]) { bytes += track.sizes[sample++] }
            newOffsets[chunk] = position
            var copied = 0L
            while (copied < bytes) {
                val count = input.transferTo(track.chunkOffsets[chunk] + copied, bytes - copied, output.position(position + copied))
                if (count <= 0) throw Mp4RepairException("표본을 복사하지 못했습니다.")
                copied += count
            }
            position += bytes
        }
        val moov = Mp4ByteSink(16 * 1024)
        val moovStart = moov.begin("moov")
        val mediaMs = track.mediaDuration * 1_000L / track.timescale
        val trackMs = track.emptyEditMs + mediaMs
        Mp4Codec.mvhd(moov, trackMs, nextTrackId = 2)
        val trak = moov.begin("trak")
        Mp4Codec.tkhd(moov, 1, trackMs, track.audio, track.width, track.height)
        if (track.emptyEditMs > 0) {
            val edts = moov.begin("edts")
            val elst = moov.beginFull("elst", 1, 0)
            moov.u32(2)
            moov.u64(track.emptyEditMs); moov.u64(-1L); moov.u16(1); moov.u16(0)
            moov.u64(mediaMs); moov.u64(0L); moov.u16(1); moov.u16(0)
            moov.end(elst)
            moov.end(edts)
        }
        val mdia = moov.begin("mdia")
        Mp4Codec.mdhd(moov, track.timescale, track.mediaDuration)
        Mp4Codec.hdlr(moov, track.audio)
        val minf = moov.begin("minf")
        Mp4Codec.mediaHeader(moov, track.audio)
        val stbl = moov.begin("stbl")
        moov.bytes(track.stsd)
        moov.bytes(track.stts)
        track.stss?.let(moov::bytes)
        val stsc = moov.beginFull("stsc", 0, 0)
        val firstChunks = mutableListOf<Int>()
        val perChunk = mutableListOf<Int>()
        track.chunkCounts.forEachIndexed { index, count ->
            if (perChunk.lastOrNull() != count) { firstChunks += index + 1; perChunk += count }
        }
        moov.u32(firstChunks.size)
        firstChunks.indices.forEach { moov.u32(firstChunks[it]); moov.u32(perChunk[it]); moov.u32(1) }
        moov.end(stsc)
        val stsz = moov.beginFull("stsz", 0, 0)
        moov.u32(0); moov.u32(track.sizes.size)
        track.sizes.forEach { moov.u32(it) }
        moov.end(stsz)
        val co64 = moov.beginFull("co64", 0, 0)
        moov.u32(newOffsets.size)
        newOffsets.forEach { moov.u64(it) }
        moov.end(co64)
        moov.end(stbl)
        moov.end(minf)
        moov.end(mdia)
        moov.end(trak)
        moov.end(moovStart)
        writeAt(output, position, moov.asByteBuffer())
        output.force(true)
    }

    private fun readTracks(channel: FileChannel): List<Track> {
        val length = channel.size()
        var position = 0L
        var moov: Box? = null
        while (true) {
            val box = readBox(channel, position, length) ?: break
            // 녹화 중 트랙 설명은 마무리 때 free로 바뀌므로 마지막 moov가 실제 표본 표입니다.
            if (box.type == "moov") moov = box
            position = box.end
        }
        val movie = moov ?: throw Mp4RepairException("moov가 없습니다.")
        var movieTimescale = Mp4Codec.MOVIE_TIMESCALE
        children(channel, movie).firstOrNull { it.type == "mvhd" }?.let { mvhd ->
            val data = read(channel, mvhd.payload, 24)
            movieTimescale = if (data.get(0).toInt() == 1) data.getInt(20) else data.getInt(12)
        }
        return children(channel, movie).filter { it.type == "trak" }.mapNotNull { parseTrack(channel, it, movieTimescale) }
    }

    private fun parseTrack(channel: FileChannel, trak: Box, movieTimescale: Int): Track? {
        var id = 0
        var width = 0L
        var height = 0L
        var emptyEdit = 0L
        var timescale = 0
        var duration = 0L
        var audio: Boolean? = null
        var stsd: ByteArray? = null
        var stts: ByteArray? = null
        var stss: ByteArray? = null
        var sizes: IntArray? = null
        var offsets: LongArray? = null
        var stscEntries: List<IntArray> = emptyList()
        for (child in children(channel, trak)) when (child.type) {
            "tkhd" -> {
                val data = full(channel, child)
                id = if (data.get(0).toInt() == 1) data.getInt(20) else data.getInt(12)
                width = data.getInt(data.limit() - 8).toLong() and 0xffffffffL
                height = data.getInt(data.limit() - 4).toLong() and 0xffffffffL
            }
            "edts" -> children(channel, child).firstOrNull { it.type == "elst" }?.let { elst ->
                val data = full(channel, elst)
                val version = data.get(0).toInt()
                val count = data.getInt(4)
                if (count > 0) {
                    val segment = if (version == 1) data.getLong(8) else data.getInt(8).toLong() and 0xffffffffL
                    val mediaTime = if (version == 1) data.getLong(16) else data.getInt(12).toLong()
                    if (mediaTime == -1L) emptyEdit = segment * 1_000L / movieTimescale
                }
            }
            "mdia" -> for (media in children(channel, child)) when (media.type) {
                "mdhd" -> {
                    val data = full(channel, media)
                    if (data.get(0).toInt() == 1) { timescale = data.getInt(20); duration = data.getLong(24) }
                    else { timescale = data.getInt(12); duration = data.getInt(16).toLong() and 0xffffffffL }
                }
                "hdlr" -> {
                    val data = full(channel, media)
                    audio = when (String(ByteArray(4) { data.get(8 + it) }, Charsets.US_ASCII)) {
                        "soun" -> true; "vide" -> false; else -> null
                    }
                }
                "minf" -> children(channel, media).firstOrNull { it.type == "stbl" }?.let { stbl ->
                    for (table in children(channel, stbl)) when (table.type) {
                        "stsd" -> stsd = raw(channel, table)
                        "stts" -> stts = raw(channel, table)
                        "stss" -> stss = raw(channel, table)
                        "stsz" -> {
                            val data = full(channel, table)
                            val constant = data.getInt(4)
                            val count = data.getInt(8)
                            sizes = IntArray(count) { if (constant != 0) constant else data.getInt(12 + it * 4) }
                        }
                        "stsc" -> {
                            val data = full(channel, table)
                            stscEntries = List(data.getInt(4)) { index ->
                                intArrayOf(data.getInt(8 + index * 12), data.getInt(12 + index * 12))
                            }
                        }
                        "stco" -> {
                            val data = full(channel, table)
                            offsets = LongArray(data.getInt(4)) { data.getInt(8 + it * 4).toLong() and 0xffffffffL }
                        }
                        "co64" -> {
                            val data = full(channel, table)
                            offsets = LongArray(data.getInt(4)) { data.getLong(8 + it * 8) }
                        }
                    }
                }
            }
        }
        val kind = audio ?: return null
        val sampleSizes = sizes ?: return null
        val chunkOffsets = offsets ?: return null
        val counts = IntArray(chunkOffsets.size)
        stscEntries.forEachIndexed { index, entry ->
            val first = entry[0] - 1
            val last = if (index + 1 < stscEntries.size) stscEntries[index + 1][0] - 1 else counts.size
            for (chunk in first until minOf(last, counts.size)) counts[chunk] = entry[1]
        }
        if (counts.sum() != sampleSizes.size || timescale <= 0) return null
        return Track(id, kind, timescale, duration, emptyEdit, width, height,
            stsd ?: return null, stts ?: return null, stss, sampleSizes, chunkOffsets, counts)
    }

    private fun children(channel: FileChannel, parent: Box): List<Box> {
        val boxes = mutableListOf<Box>()
        var position = parent.payload
        while (position < parent.end) {
            val box = readBox(channel, position, parent.end) ?: break
            boxes += box
            position = box.end
        }
        return boxes
    }

    private fun readBox(channel: FileChannel, position: Long, limit: Long): Box? {
        if (position + 8 > limit) return null
        val header = read(channel, position, 8)
        var size = header.getInt(0).toLong() and 0xffffffffL
        val type = String(ByteArray(4) { header.get(4 + it) }, Charsets.US_ASCII)
        var headerSize = 8
        if (size == 1L) {
            if (position + 16 > limit) return null
            size = read(channel, position + 8, 8).getLong(0)
            headerSize = 16
        } else if (size == 0L) {
            size = limit - position
        }
        if (size < headerSize || position + size > limit) return null
        return Box(position, size, headerSize, type)
    }

    private fun full(channel: FileChannel, box: Box): ByteBuffer = read(channel, box.payload, (box.end - box.payload).toInt())

    private fun raw(channel: FileChannel, box: Box): ByteArray = read(channel, box.position, box.size.toInt()).array()

    private fun read(channel: FileChannel, position: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length)
        var offset = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, offset)
            if (count < 0) throw Mp4RepairException("파일이 예상보다 짧습니다.")
            offset += count
        }
        buffer.flip()
        return buffer
    }

    private fun writeAt(channel: FileChannel, position: Long, buffer: ByteBuffer) {
        var offset = position
        while (buffer.hasRemaining()) offset += channel.write(buffer, offset)
    }
}
