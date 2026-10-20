package com.framework.innolive.feature.live.recording

import java.nio.ByteBuffer

/** 녹화 영상 트랙 설정입니다. [sps]·[pps]는 시작 코드 없는 NAL 단위입니다. */
internal class VideoTrackConfig(
    val width: Int,
    val height: Int,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
) {
    init {
        require(width > 0 && height > 0) { "영상 크기가 올바르지 않습니다." }
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "SPS·PPS가 필요합니다." }
        require(sps.all { it.size >= 4 }) { "SPS가 너무 짧습니다." }
    }
}

/** 녹화 음성 트랙 설정입니다. [audioSpecificConfig]는 AAC `csd-0`입니다. */
internal class AudioTrackConfig(
    val sampleRate: Int,
    val channels: Int,
    val audioSpecificConfig: ByteArray,
    val bitrateBps: Int = RECORDING_AUDIO_BITRATE_BPS,
) {
    init {
        require(sampleRate in 8_000..96_000 && channels in 1..2) { "음성 형식이 올바르지 않습니다." }
        require(audioSpecificConfig.isNotEmpty()) { "AAC 설정이 필요합니다." }
    }
}

/** MP4 상자를 큰 끝(big-endian)으로 쌓는 버퍼입니다. 상자 크기는 [end]에서 채웁니다. */
internal class Mp4ByteSink(initialCapacity: Int = 256) {
    private var bytes = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= bytes.size) return
        var capacity = bytes.size * 2
        while (capacity < size + extra) capacity *= 2
        bytes = bytes.copyOf(capacity)
    }

    fun u8(value: Int) = apply { ensure(1); bytes[size++] = value.toByte() }
    fun u16(value: Int) = apply { u8(value ushr 8); u8(value) }
    fun u24(value: Int) = apply { u8(value ushr 16); u16(value) }
    fun u32(value: Long) = apply { u16((value ushr 16).toInt()); u16(value.toInt()) }
    fun u32(value: Int) = u32(value.toLong() and 0xffffffffL)
    fun u64(value: Long) = apply { u32(value ushr 32); u32(value and 0xffffffffL) }
    fun zeros(count: Int) = apply { ensure(count); size += count }

    fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset) = apply {
        ensure(length)
        System.arraycopy(value, offset, bytes, size, length)
        size += length
    }

    fun fourcc(type: String) = apply {
        require(type.length == 4)
        type.forEach { u8(it.code) }
    }

    /** 상자 머리를 쓰고 시작 위치를 돌려줍니다. */
    fun begin(type: String): Int {
        val start = size
        u32(0)
        fourcc(type)
        return start
    }

    fun beginFull(type: String, version: Int, flags: Int): Int {
        val start = begin(type)
        u8(version)
        u24(flags)
        return start
    }

    fun end(start: Int) = putU32(start, (size - start).toLong())

    fun putU32(position: Int, value: Long) {
        bytes[position] = (value ushr 24).toByte()
        bytes[position + 1] = (value ushr 16).toByte()
        bytes[position + 2] = (value ushr 8).toByte()
        bytes[position + 3] = value.toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes, 0, size)
}

internal object Mp4Codec {
    const val VIDEO_TRACK_ID = 1
    const val AUDIO_TRACK_ID = 2
    const val VIDEO_TIMESCALE = 90_000
    const val MOVIE_TIMESCALE = 1_000
    const val SAMPLE_FLAGS_SYNC = 0x02000000
    const val SAMPLE_FLAGS_NON_SYNC = 0x01010000
    private const val LANGUAGE_UNDETERMINED = 0x55c4
    private val UNITY_MATRIX = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    fun writeMatrix(sink: Mp4ByteSink) = UNITY_MATRIX.forEach { sink.u32(it) }

    /** 시작 코드로 구분한 NAL 단위를 시작 코드 없이 돌려줍니다. 시작 코드가 없으면 전체를 NAL 하나로 봅니다. */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<IntRange> {
        val end = offset + length
        val starts = mutableListOf<Pair<Int, Int>>() // (start code 위치, NAL 시작)
        var index = offset
        while (index + 2 < end) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                if (data[index + 2].toInt() == 1) {
                    starts += index to index + 3
                    index += 3
                    continue
                }
                if (index + 3 < end && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1) {
                    starts += index to index + 4
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) return if (length > 0) listOf(offset until end) else emptyList()
        return starts.mapIndexedNotNull { position, (_, nalStart) ->
            val nalEnd = if (position + 1 < starts.size) starts[position + 1].first else end
            // 다음 시작 코드 앞의 0 패딩(trailing_zero_8bits)을 뺍니다.
            var trimmed = nalEnd
            while (trimmed > nalStart && data[trimmed - 1].toInt() == 0 && position + 1 < starts.size) trimmed--
            if (trimmed > nalStart) nalStart until trimmed else null
        }
    }

    /**
     * MediaCodec의 Annex-B 접근 단위를 MP4 표본(4바이트 길이 + NAL)으로 바꿉니다. 매개변수 집합과 AUD는
     * 표본 설명(avcC)에 있으므로 뺍니다. 남는 NAL이 없으면 null입니다.
     */
    fun annexBToLengthPrefixed(source: ByteBuffer): ByteArray? {
        val data = ByteArray(source.remaining())
        source.duplicate().get(data)
        val units = splitAnnexB(data).filter { range ->
            when (data[range.first].toInt() and 0x1f) {
                NAL_SPS, NAL_PPS, NAL_AUD -> false
                else -> true
            }
        }
        if (units.isEmpty()) return null
        val output = Mp4ByteSink(data.size + units.size * 4)
        units.forEach { range ->
            output.u32(range.last - range.first + 1)
            output.bytes(data, range.first, range.last - range.first + 1)
        }
        return output.toByteArray()
    }

    /** `csd-0`·`csd-1`의 NAL 단위를 SPS·PPS로 나눕니다. */
    fun parameterSets(vararg buffers: ByteArray): Pair<List<ByteArray>, List<ByteArray>> {
        val sps = mutableListOf<ByteArray>()
        val pps = mutableListOf<ByteArray>()
        buffers.forEach { buffer ->
            splitAnnexB(buffer).forEach { range ->
                val unit = buffer.copyOfRange(range.first, range.last + 1)
                when (unit[0].toInt() and 0x1f) {
                    NAL_SPS -> sps += unit
                    NAL_PPS -> pps += unit
                }
            }
        }
        return sps to pps
    }

    fun avcC(config: VideoTrackConfig): ByteArray {
        val sink = Mp4ByteSink()
        val sps = config.sps.first()
        val box = sink.begin("avcC")
        sink.u8(1)
        sink.u8(sps[1].toInt())
        sink.u8(sps[2].toInt())
        sink.u8(sps[3].toInt())
        sink.u8(0xff)
        sink.u8(0xe0 or config.sps.size)
        config.sps.forEach { sink.u16(it.size); sink.bytes(it) }
        sink.u8(config.pps.size)
        config.pps.forEach { sink.u16(it.size); sink.bytes(it) }
        if ((sps[1].toInt() and 0xff) in HIGH_PROFILES) {
            // MediaCodec Surface 인코더 출력은 8비트 4:2:0입니다.
            sink.u8(0xfc or 1)
            sink.u8(0xf8)
            sink.u8(0xf8)
            sink.u8(0)
        }
        sink.end(box)
        return sink.toByteArray()
    }

    fun esds(config: AudioTrackConfig): ByteArray {
        val asc = config.audioSpecificConfig
        val sink = Mp4ByteSink()
        val box = sink.beginFull("esds", 0, 0)
        val decoderSpecificLength = asc.size
        val decoderConfigLength = 13 + 2 + decoderSpecificLength
        val esLength = 3 + 2 + decoderConfigLength + 3
        sink.u8(0x03); sink.u8(esLength)
        sink.u16(0); sink.u8(0)
        sink.u8(0x04); sink.u8(decoderConfigLength)
        sink.u8(0x40); sink.u8(0x15); sink.u24(0)
        sink.u32(config.bitrateBps); sink.u32(config.bitrateBps)
        sink.u8(0x05); sink.u8(decoderSpecificLength); sink.bytes(asc)
        sink.u8(0x06); sink.u8(1); sink.u8(0x02)
        sink.end(box)
        return sink.toByteArray()
    }

    fun videoSampleDescription(config: VideoTrackConfig): ByteArray {
        val sink = Mp4ByteSink()
        val stsd = sink.beginFull("stsd", 0, 0)
        sink.u32(1)
        val entry = sink.begin("avc1")
        sink.zeros(6); sink.u16(1)
        sink.u16(0); sink.u16(0); sink.zeros(12)
        sink.u16(config.width); sink.u16(config.height)
        sink.u32(0x00480000); sink.u32(0x00480000)
        sink.u32(0); sink.u16(1)
        sink.zeros(32)
        sink.u16(0x0018); sink.u16(0xffff)
        sink.bytes(avcC(config))
        sink.end(entry)
        sink.end(stsd)
        return sink.toByteArray()
    }

    fun audioSampleDescription(config: AudioTrackConfig): ByteArray {
        val sink = Mp4ByteSink()
        val stsd = sink.beginFull("stsd", 0, 0)
        sink.u32(1)
        val entry = sink.begin("mp4a")
        sink.zeros(6); sink.u16(1)
        sink.zeros(8)
        sink.u16(config.channels); sink.u16(16)
        sink.u16(0); sink.u16(0)
        sink.u32(config.sampleRate.toLong() shl 16)
        sink.bytes(esds(config))
        sink.end(entry)
        sink.end(stsd)
        return sink.toByteArray()
    }

    fun ftyp(sink: Mp4ByteSink) {
        val box = sink.begin("ftyp")
        sink.fourcc("isom"); sink.u32(0x200)
        listOf("isom", "iso2", "avc1", "mp41", "iso6").forEach(sink::fourcc)
        sink.end(box)
    }

    fun mvhd(sink: Mp4ByteSink, durationMs: Long, nextTrackId: Int) {
        val box = sink.beginFull("mvhd", 1, 0)
        sink.u64(0); sink.u64(0)
        sink.u32(MOVIE_TIMESCALE); sink.u64(durationMs)
        sink.u32(0x00010000); sink.u16(0x0100); sink.zeros(10)
        writeMatrix(sink)
        sink.zeros(24)
        sink.u32(nextTrackId)
        sink.end(box)
    }

    fun tkhd(sink: Mp4ByteSink, trackId: Int, durationMs: Long, audio: Boolean, widthFixed: Long, heightFixed: Long) {
        val box = sink.beginFull("tkhd", 1, 0x3)
        sink.u64(0); sink.u64(0)
        sink.u32(trackId); sink.u32(0)
        sink.u64(durationMs)
        sink.zeros(8)
        sink.u16(0); sink.u16(0)
        sink.u16(if (audio) 0x0100 else 0); sink.u16(0)
        writeMatrix(sink)
        sink.u32(widthFixed); sink.u32(heightFixed)
        sink.end(box)
    }

    fun mdhd(sink: Mp4ByteSink, timescale: Int, duration: Long) {
        val box = sink.beginFull("mdhd", 1, 0)
        sink.u64(0); sink.u64(0)
        sink.u32(timescale); sink.u64(duration)
        sink.u16(LANGUAGE_UNDETERMINED); sink.u16(0)
        sink.end(box)
    }

    fun hdlr(sink: Mp4ByteSink, audio: Boolean) {
        val box = sink.beginFull("hdlr", 0, 0)
        sink.u32(0)
        sink.fourcc(if (audio) "soun" else "vide")
        sink.zeros(12)
        sink.bytes((if (audio) "SoundHandler" else "VideoHandler").toByteArray(Charsets.US_ASCII))
        sink.u8(0)
        sink.end(box)
    }

    fun mediaHeader(sink: Mp4ByteSink, audio: Boolean) {
        if (audio) {
            val box = sink.beginFull("smhd", 0, 0)
            sink.u16(0); sink.u16(0)
            sink.end(box)
        } else {
            val box = sink.beginFull("vmhd", 0, 1)
            sink.u16(0); sink.u16(0); sink.u16(0); sink.u16(0)
            sink.end(box)
        }
        val dinf = sink.begin("dinf")
        val dref = sink.beginFull("dref", 0, 0)
        sink.u32(1)
        val url = sink.beginFull("url ", 0, 1)
        sink.end(url)
        sink.end(dref)
        sink.end(dinf)
    }

    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9
    private val HIGH_PROFILES = setOf(100, 110, 122, 144)
}
