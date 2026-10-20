package com.framework.innolive.feature.live.recording

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class Mp4RepairException(message: String) : IOException(message)

/** 일반 MP4로 바꾼 결과입니다. 시각은 µs입니다. */
internal data class Mp4FinalizeResult(
    val durationUs: Long,
    val videoSamples: Int,
    val audioSamples: Int,
    val videoStartUs: Long,
    val audioStartUs: Long,
    val fragments: Int,
    val truncatedBytes: Long,
)

/**
 * [FragmentedMp4Writer]가 쓴 파일을 일반 MP4(moov 끝 배치)로 바꿉니다. 정상 종료와 강제 종료 뒤 복구가
 * 같은 경로를 씁니다.
 *
 * 1. 다 쓴 `moof` + `mdat` 조각만 읽고 그 뒤(쓰다 만 조각, 이전 실행이 붙인 moov)는 잘라 냅니다.
 * 2. 표본 표를 담은 moov를 파일 끝에 붙입니다. 표본 데이터는 옮기지 않고 기존 mdat을 가리킵니다.
 * 3. 조각 머리(`moof`)를 `skip`으로, 녹화 중 트랙 설명(`moov`)을 `free`로 바꿉니다. 마지막 단계가 확정입니다.
 *
 * 어느 단계에서 멈춰도 다시 실행하면 같은 결과가 나옵니다. [videoShiftUs]는 영상 트랙 시작을 옮기는 값으로,
 * 서버가 돌려준 영상의 왕복 지연을 빼는 데 씁니다.
 */
internal object Mp4Finalizer {
    private class Box(val position: Long, val size: Long, val headerSize: Int, val type: String) {
        val end: Long get() = position + size
        val payload: Long get() = position + headerSize
    }

    private class InitTrack(val id: Int, val audio: Boolean, val timescale: Int, val width: Long, val height: Long, val stsd: ByteArray)

    private class TrackRun(val baseTime: Long, val dataOffset: Long, val durations: IntArray, val sizes: IntArray, val flags: IntArray)

    private class TrackTable(val init: InitTrack) {
        val durations = IntList()
        val sizes = IntList()
        val syncSamples = IntList()
        val chunkOffsets = LongList()
        val chunkCounts = IntList()
        var firstTime = -1L
        var nextTime = 0L
        var allSync = true

        fun add(run: TrackRun) {
            if (run.durations.isEmpty()) return
            if (firstTime < 0) {
                firstTime = run.baseTime
                nextTime = run.baseTime
            } else if (run.baseTime != nextTime && durations.size > 0) {
                // 조각 사이 시각이 어긋나면 앞 표본 길이로 메워 이후 표본의 시각을 지킵니다.
                val last = durations.size - 1
                val adjusted = durations[last].toLong() + (run.baseTime - nextTime)
                if (adjusted in 1L..Int.MAX_VALUE.toLong()) {
                    durations[last] = adjusted.toInt()
                    nextTime = run.baseTime
                }
            }
            chunkOffsets.add(run.dataOffset)
            chunkCounts.add(run.durations.size)
            for (index in run.durations.indices) {
                durations.add(run.durations[index])
                sizes.add(run.sizes[index])
                val sync = run.flags[index] and SAMPLE_IS_NON_SYNC == 0
                if (sync) syncSamples.add(durations.size) else allSync = false
                nextTime += run.durations[index]
            }
        }

        val mediaDuration: Long get() = nextTime - maxOf(firstTime, 0L)
    }

    fun finish(file: File, videoShiftUs: Long = 0L): Mp4FinalizeResult =
        RandomAccessFile(file, "rw").use { raf -> finish(raf.channel, videoShiftUs) }

    private fun finish(channel: FileChannel, videoShiftUs: Long): Mp4FinalizeResult {
        val length = channel.size()
        val ftyp = readBox(channel, 0L, length) ?: throw Mp4RepairException("파일 머리가 없습니다.")
        if (ftyp.type != "ftyp") throw Mp4RepairException("MP4 파일이 아닙니다.")
        val init = readBox(channel, ftyp.end, length) ?: throw Mp4RepairException("트랙 설명이 없습니다.")
        if (init.type != "moov" && init.type != "free") throw Mp4RepairException("트랙 설명이 없습니다.")
        val tracks = parseInit(channel, init)
        val video = tracks.firstOrNull { !it.audio } ?: throw Mp4RepairException("영상 트랙 설명이 없습니다.")
        val audio = tracks.firstOrNull { it.audio } ?: throw Mp4RepairException("음성 트랙 설명이 없습니다.")
        val tables = mapOf(video.id to TrackTable(video), audio.id to TrackTable(audio))

        val fragmentHeaders = mutableListOf<Long>()
        var position = init.end
        while (true) {
            val box = readBox(channel, position, length) ?: break
            if (!isFragment(channel, box)) break
            val mdat = readBox(channel, box.end, length) ?: break
            if (mdat.type != "mdat") break
            val runs = parseMoof(channel, box) ?: break
            if (!runs.all { (_, run) -> runFits(run, mdat) }) break
            runs.forEach { (trackId, run) -> tables[trackId]?.add(run) }
            fragmentHeaders += box.position
            position = mdat.end
        }
        val videoTable = checkNotNull(tables[video.id])
        val audioTable = checkNotNull(tables[audio.id])
        if (videoTable.sizes.size == 0) throw Mp4RepairException("영상 표본이 없습니다.")
        if (audioTable.sizes.size == 0) throw Mp4RepairException("음성 표본이 없습니다.")

        val truncated = length - position
        channel.truncate(position)
        val videoStartUs = videoTable.firstTime * 1_000_000L / video.timescale + videoShiftUs
        val audioStartUs = audioTable.firstTime * 1_000_000L / audio.timescale
        val movieStartUs = minOf(videoStartUs, audioStartUs)
        val moov = Mp4ByteSink(64 * 1024)
        val moovStart = moov.begin("moov")
        val trackDurations = mutableListOf<Long>()
        val trakBytes = Mp4ByteSink(32 * 1024)
        for ((table, startUs) in listOf(videoTable to videoStartUs, audioTable to audioStartUs)) {
            trackDurations += writeTrak(trakBytes, table, startUs - movieStartUs)
        }
        val durationMs = trackDurations.max()
        Mp4Codec.mvhd(moov, durationMs, nextTrackId = 3)
        moov.bytes(trakBytes.toByteArray())
        moov.end(moovStart)
        writeAt(channel, position, moov.asByteBuffer())
        channel.force(false)
        val skip = ByteBuffer.wrap("skip".toByteArray(Charsets.US_ASCII))
        fragmentHeaders.forEach { header -> writeAt(channel, header + 4, skip.duplicate()) }
        channel.force(false)
        writeAt(channel, init.position + 4, ByteBuffer.wrap("free".toByteArray(Charsets.US_ASCII)))
        channel.force(true)
        val videoEndUs = videoStartUs + videoTable.mediaDuration * 1_000_000L / video.timescale
        val audioEndUs = audioStartUs + audioTable.mediaDuration * 1_000_000L / audio.timescale
        return Mp4FinalizeResult(
            durationUs = maxOf(videoEndUs, audioEndUs) - movieStartUs,
            videoSamples = videoTable.sizes.size,
            audioSamples = audioTable.sizes.size,
            videoStartUs = videoStartUs - movieStartUs,
            audioStartUs = audioStartUs - movieStartUs,
            fragments = fragmentHeaders.size,
            truncatedBytes = truncated,
        )
    }

    /** trak 하나를 쓰고 트랙 길이(ms, 앞 빈 구간 포함)를 돌려줍니다. */
    private fun writeTrak(sink: Mp4ByteSink, table: TrackTable, emptyUs: Long): Long {
        val init = table.init
        val mediaDuration = table.mediaDuration
        val mediaMs = mediaDuration * 1_000L / init.timescale
        val emptyMs = emptyUs / 1_000L
        val trackMs = emptyMs + mediaMs
        val trak = sink.begin("trak")
        Mp4Codec.tkhd(sink, init.id, trackMs, init.audio, init.width, init.height)
        if (emptyMs > 0) {
            val edts = sink.begin("edts")
            val elst = sink.beginFull("elst", 1, 0)
            sink.u32(2)
            sink.u64(emptyMs); sink.u64(-1L); sink.u16(1); sink.u16(0)
            sink.u64(mediaMs); sink.u64(0L); sink.u16(1); sink.u16(0)
            sink.end(elst)
            sink.end(edts)
        }
        val mdia = sink.begin("mdia")
        Mp4Codec.mdhd(sink, init.timescale, mediaDuration)
        Mp4Codec.hdlr(sink, init.audio)
        val minf = sink.begin("minf")
        Mp4Codec.mediaHeader(sink, init.audio)
        val stbl = sink.begin("stbl")
        sink.bytes(init.stsd)
        writeStts(sink, table.durations)
        if (!table.allSync) {
            val stss = sink.beginFull("stss", 0, 0)
            sink.u32(table.syncSamples.size)
            for (index in 0 until table.syncSamples.size) sink.u32(table.syncSamples[index])
            sink.end(stss)
        }
        writeStsc(sink, table.chunkCounts)
        writeStsz(sink, table.sizes)
        val co64 = sink.beginFull("co64", 0, 0)
        sink.u32(table.chunkOffsets.size)
        for (index in 0 until table.chunkOffsets.size) sink.u64(table.chunkOffsets[index])
        sink.end(co64)
        sink.end(stbl)
        sink.end(minf)
        sink.end(mdia)
        sink.end(trak)
        return trackMs
    }

    private fun writeStts(sink: Mp4ByteSink, durations: IntList) {
        val counts = IntList()
        val values = IntList()
        for (index in 0 until durations.size) {
            val value = durations[index]
            if (values.size > 0 && values[values.size - 1] == value) counts[counts.size - 1] = counts[counts.size - 1] + 1
            else { counts.add(1); values.add(value) }
        }
        val box = sink.beginFull("stts", 0, 0)
        sink.u32(counts.size)
        for (index in 0 until counts.size) { sink.u32(counts[index]); sink.u32(values[index]) }
        sink.end(box)
    }

    private fun writeStsc(sink: Mp4ByteSink, chunkCounts: IntList) {
        val firstChunks = IntList()
        val perChunk = IntList()
        for (index in 0 until chunkCounts.size) {
            val count = chunkCounts[index]
            if (perChunk.size == 0 || perChunk[perChunk.size - 1] != count) {
                firstChunks.add(index + 1)
                perChunk.add(count)
            }
        }
        val box = sink.beginFull("stsc", 0, 0)
        sink.u32(firstChunks.size)
        for (index in 0 until firstChunks.size) { sink.u32(firstChunks[index]); sink.u32(perChunk[index]); sink.u32(1) }
        sink.end(box)
    }

    private fun writeStsz(sink: Mp4ByteSink, sizes: IntList) {
        val box = sink.beginFull("stsz", 0, 0)
        val first = sizes[0]
        val constant = (0 until sizes.size).all { sizes[it] == first }
        if (constant) {
            sink.u32(first); sink.u32(sizes.size)
        } else {
            sink.u32(0); sink.u32(sizes.size)
            for (index in 0 until sizes.size) sink.u32(sizes[index])
        }
        sink.end(box)
    }

    private fun runFits(run: TrackRun, mdat: Box): Boolean {
        val bytes = run.sizes.sumOf { it.toLong() }
        return run.dataOffset >= mdat.payload && run.dataOffset + bytes <= mdat.end
    }

    /** 조각 머리는 `moof`이거나, 이전 마무리가 `skip`으로 바꾼 것(첫 자식이 mfhd)입니다. */
    private fun isFragment(channel: FileChannel, box: Box): Boolean = when (box.type) {
        "moof" -> true
        "skip" -> readBox(channel, box.payload, box.end)?.type == "mfhd"
        else -> false
    }

    private fun parseInit(channel: FileChannel, init: Box): List<InitTrack> {
        val tracks = mutableListOf<InitTrack>()
        children(channel, init).filter { it.type == "trak" }.forEach { trak ->
            var id = -1
            var width = 0L
            var height = 0L
            var timescale = 0
            var audio: Boolean? = null
            var stsd: ByteArray? = null
            children(channel, trak).forEach { child ->
                when (child.type) {
                    "tkhd" -> {
                        val data = read(channel, child.payload, (child.end - child.payload).toInt())
                        val version = data.get(0).toInt()
                        id = if (version == 1) data.getInt(4 + 16) else data.getInt(4 + 8)
                        width = data.getInt(data.limit() - 8).toLong() and 0xffffffffL
                        height = data.getInt(data.limit() - 4).toLong() and 0xffffffffL
                    }
                    "mdia" -> children(channel, child).forEach { media ->
                        when (media.type) {
                            "mdhd" -> {
                                val data = read(channel, media.payload, (media.end - media.payload).toInt())
                                timescale = if (data.get(0).toInt() == 1) data.getInt(4 + 16) else data.getInt(4 + 8)
                            }
                            "hdlr" -> {
                                val data = read(channel, media.payload, 12)
                                val handler = String(ByteArray(4).also { data.position(8); data.get(it) }, Charsets.US_ASCII)
                                audio = when (handler) { "soun" -> true; "vide" -> false; else -> null }
                            }
                            "minf" -> children(channel, media).firstOrNull { it.type == "stbl" }?.let { stbl ->
                                children(channel, stbl).firstOrNull { it.type == "stsd" }?.let { box ->
                                    stsd = read(channel, box.position, box.size.toInt()).array()
                                }
                            }
                        }
                    }
                }
            }
            val description = stsd
            val kind = audio
            if (id > 0 && timescale > 0 && description != null && kind != null) {
                tracks += InitTrack(id, kind, timescale, width, height, description)
            }
        }
        return tracks
    }

    /** moof 하나의 트랙별 표본입니다. 지원하지 않는 형태면 null입니다. */
    private fun parseMoof(channel: FileChannel, moof: Box): List<Pair<Int, TrackRun>>? {
        if (moof.size > MAX_MOOF_BYTES) return null
        val runs = mutableListOf<Pair<Int, TrackRun>>()
        for (traf in children(channel, moof).filter { it.type == "traf" }) {
            var trackId = -1
            var baseOffset = moof.position
            var defaultDuration = 0
            var defaultSize = 0
            var defaultFlags = 0
            var baseTime = -1L
            var run: TrackRun? = null
            for (child in children(channel, traf)) {
                if (child.type !in setOf("tfhd", "tfdt", "trun") || child.end - child.payload < 8) continue
                val data = read(channel, child.payload, (child.end - child.payload).toInt())
                val version = data.get(0).toInt()
                val flags = data.getInt(0) and 0xffffff
                data.position(4)
                when (child.type) {
                    "tfhd" -> {
                        trackId = data.getInt()
                        if (flags and 0x1 != 0) baseOffset = data.getLong()
                        if (flags and 0x2 != 0) data.getInt()
                        if (flags and 0x8 != 0) defaultDuration = data.getInt()
                        if (flags and 0x10 != 0) defaultSize = data.getInt()
                        if (flags and 0x20 != 0) defaultFlags = data.getInt()
                    }
                    "tfdt" -> baseTime = if (version == 1) data.getLong() else data.getInt().toLong() and 0xffffffffL
                    "trun" -> {
                        if (run != null) return null
                        val count = data.getInt()
                        if (count < 0 || count > MAX_RUN_SAMPLES) return null
                        val offset = if (flags and 0x1 != 0) data.getInt() else 0
                        val firstFlags = if (flags and 0x4 != 0) data.getInt() else null
                        val durations = IntArray(count)
                        val sizes = IntArray(count)
                        val sampleFlags = IntArray(count)
                        for (index in 0 until count) {
                            durations[index] = if (flags and 0x100 != 0) data.getInt() else defaultDuration
                            sizes[index] = if (flags and 0x200 != 0) data.getInt() else defaultSize
                            sampleFlags[index] = if (flags and 0x400 != 0) data.getInt()
                                else if (index == 0 && firstFlags != null) firstFlags else defaultFlags
                            if (flags and 0x800 != 0) data.getInt()
                            if (durations[index] <= 0 || sizes[index] < 0) return null
                        }
                        run = TrackRun(0L, baseOffset + offset, durations, sizes, sampleFlags)
                    }
                }
            }
            val parsed = run ?: continue
            if (trackId <= 0 || baseTime < 0) return null
            runs += trackId to TrackRun(baseTime, parsed.dataOffset, parsed.durations, parsed.sizes, parsed.flags)
        }
        return runs
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

    /** [limit]를 넘는 상자는 쓰다 만 것으로 보고 null을 돌려줍니다. */
    private fun readBox(channel: FileChannel, position: Long, limit: Long): Box? {
        if (position + 8 > limit) return null
        val header = read(channel, position, 8)
        var size = header.getInt(0).toLong() and 0xffffffffL
        val type = String(ByteArray(4).also { header.position(4); header.get(it) }, Charsets.US_ASCII)
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

    private const val SAMPLE_IS_NON_SYNC = 0x00010000
    private const val MAX_MOOF_BYTES = 16L * 1024 * 1024
    private const val MAX_RUN_SAMPLES = 1_000_000
}

/** 상자(boxing) 없이 정수를 모으는 목록입니다. 긴 녹화의 표본 표에 씁니다. */
internal class IntList(capacity: Int = 1024) {
    private var values = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Int = values[index]
    operator fun set(index: Int, value: Int) { values[index] = value }
}

internal class LongList(capacity: Int = 256) {
    private var values = LongArray(capacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Long = values[index]
}
