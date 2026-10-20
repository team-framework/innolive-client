package com.framework.innolive.feature.live.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 녹화 중에 쓰는 조각(fragmented) MP4입니다. 처음에 트랙 설명(moov + mvex)을 쓰고 이후 약 1초마다
 * `moof` + `mdat` 조각을 덧붙입니다. 프로세스가 죽어도 마지막으로 다 쓴 조각까지는 그대로 읽을 수 있고,
 * [Mp4Finalizer]가 같은 파일을 일반 MP4로 바꿉니다(정상 종료와 복구가 같은 경로).
 *
 * 시각은 [epochUs] 기준 µs이며 트랙마다 엄격히 증가하는 표본만 받습니다. 스레드 안전하지 않으므로
 * 호출자가 잠금을 잡고 부릅니다.
 */
internal class FragmentedMp4Writer(
    file: File,
    video: VideoTrackConfig,
    audio: AudioTrackConfig,
    private val epochUs: Long,
    private val fragmentDurationUs: Long = 1_000_000L,
    private val maxFragmentDurationUs: Long = 2_000_000L,
    private val maxFragmentBytes: Long = 4L * 1024 * 1024,
    openChannel: (File) -> FileChannel = ::openRecordingChannel,
) : AutoCloseable {
    private class Sample(val data: ByteArray, val ticks: Long, val sync: Boolean, var duration: Int = 0)

    private inner class Track(val id: Int, val timescale: Int, val defaultDuration: Int) {
        val ready = ArrayList<Sample>()
        var held: Sample? = null
        var samples = 0L
        var lastDuration = defaultDuration
        fun ticks(timeUs: Long): Long = Math.floorDiv((timeUs - epochUs) * timescale, 1_000_000L)
        fun timeUs(ticks: Long): Long = epochUs + ticks * 1_000_000L / timescale
    }

    private val channel: FileChannel = openChannel(file)
    private val videoTrack = Track(Mp4Codec.VIDEO_TRACK_ID, Mp4Codec.VIDEO_TIMESCALE, Mp4Codec.VIDEO_TIMESCALE / RECORDING_FPS)
    private val audioTrack = Track(Mp4Codec.AUDIO_TRACK_ID, audio.sampleRate, AAC_FRAME_SAMPLES)
    private var sequence = 0
    private var readyBytes = 0L
    private var closed = false

    /** 지금까지 파일에 쓴 바이트 수입니다. */
    var bytesWritten = 0L
        private set

    init {
        try {
            channel.truncate(0)
            writeFully(ByteBuffer.wrap(initSegment(video, audio)))
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw error
        }
    }

    val videoSamples: Long get() = videoTrack.samples
    val audioSamples: Long get() = audioTrack.samples

    /** 표본을 받았으면 true입니다. 시각이 앞 표본보다 늦지 않으면 버리고 false를 돌려줍니다. */
    fun writeVideo(sample: ByteArray, timeUs: Long, sync: Boolean): Boolean = append(videoTrack, sample, timeUs, sync)

    fun writeAudio(sample: ByteArray, timeUs: Long): Boolean = append(audioTrack, sample, timeUs, true)

    private fun append(track: Track, data: ByteArray, timeUs: Long, sync: Boolean): Boolean {
        check(!closed) { "이미 닫은 녹화 파일입니다." }
        if (timeUs < epochUs) return false
        val ticks = track.ticks(timeUs)
        val previous = track.held
        if (previous != null) {
            if (ticks <= previous.ticks) return false
            val duration = ticks - previous.ticks
            if (duration > Int.MAX_VALUE) return false
            previous.duration = duration.toInt()
            track.lastDuration = previous.duration
            track.ready += previous
            readyBytes += previous.data.size
        }
        track.held = Sample(data, ticks, sync)
        // 영상 키프레임 앞에서 조각을 끊어 다음 조각이 키프레임으로 시작하게 합니다.
        if (track === videoTrack && sync && fragmentSpanUs() >= fragmentDurationUs) {
            flush()
            return true
        }
        if (readyBytes >= maxFragmentBytes || fragmentSpanUs() >= maxFragmentDurationUs) flush()
        return true
    }

    private fun fragmentSpanUs(): Long {
        val first = listOfNotNull(
            videoTrack.ready.firstOrNull()?.let { videoTrack.timeUs(it.ticks) },
            audioTrack.ready.firstOrNull()?.let { audioTrack.timeUs(it.ticks) },
        ).minOrNull() ?: return 0L
        val last = listOfNotNull(
            videoTrack.held?.let { videoTrack.timeUs(it.ticks) },
            audioTrack.held?.let { audioTrack.timeUs(it.ticks) },
        ).maxOrNull() ?: return 0L
        return last - first
    }

    /** 길이를 아는 표본을 조각 하나로 씁니다. */
    fun flush() {
        val tracks = listOf(videoTrack, audioTrack).filter { it.ready.isNotEmpty() }
        if (tracks.isEmpty()) return
        val moof = Mp4ByteSink(256 + tracks.sumOf { it.ready.size } * 12)
        val moofStart = moof.begin("moof")
        val mfhd = moof.beginFull("mfhd", 0, 0)
        moof.u32(++sequence)
        moof.end(mfhd)
        val offsetPositions = IntArray(tracks.size)
        tracks.forEachIndexed { index, track ->
            val traf = moof.begin("traf")
            val tfhd = moof.beginFull("tfhd", 0, TFHD_DEFAULT_BASE_IS_MOOF)
            moof.u32(track.id)
            moof.end(tfhd)
            val tfdt = moof.beginFull("tfdt", 1, 0)
            moof.u64(track.ready.first().ticks)
            moof.end(tfdt)
            val trun = moof.beginFull("trun", 0, TRUN_FLAGS)
            moof.u32(track.ready.size)
            offsetPositions[index] = moof.size
            moof.u32(0)
            track.ready.forEach { sample ->
                moof.u32(sample.duration)
                moof.u32(sample.data.size)
                moof.u32(if (sample.sync) Mp4Codec.SAMPLE_FLAGS_SYNC else Mp4Codec.SAMPLE_FLAGS_NON_SYNC)
            }
            moof.end(trun)
            moof.end(traf)
        }
        moof.end(moofStart)
        var dataOffset = moof.size + 8L
        tracks.forEachIndexed { index, track ->
            moof.putU32(offsetPositions[index], dataOffset)
            dataOffset += track.ready.sumOf { it.data.size.toLong() }
        }
        val mdatSize = dataOffset - moof.size
        val header = Mp4ByteSink(8).u32(mdatSize).fourcc("mdat")
        val buffers = ArrayList<ByteBuffer>(2 + tracks.sumOf { it.ready.size })
        buffers += moof.asByteBuffer()
        buffers += header.asByteBuffer()
        tracks.forEach { track -> track.ready.forEach { buffers += ByteBuffer.wrap(it.data) } }
        writeFully(*buffers.toTypedArray())
        tracks.forEach { track ->
            track.samples += track.ready.size
            track.ready.clear()
        }
        readyBytes = 0L
    }

    /** 남은 표본을 마지막 조각으로 쓰고 닫습니다. 마지막 표본 길이는 바로 앞 표본과 같게 둡니다. */
    fun finish() {
        if (closed) return
        try {
            listOf(videoTrack, audioTrack).forEach { track ->
                track.held?.let { last ->
                    last.duration = track.lastDuration
                    track.ready += last
                    readyBytes += last.data.size
                }
                track.held = null
            }
            flush()
            channel.force(false)
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
    }

    private fun writeFully(vararg buffers: ByteBuffer) {
        val total = buffers.sumOf { it.remaining().toLong() }
        var written = 0L
        while (written < total) written += channel.write(buffers)
        bytesWritten += total
    }

    internal companion object {
        /** 녹화 파일의 기본 채널입니다. 테스트는 쓰기 오류를 내는 채널로 바꿉니다. */
        fun openRecordingChannel(file: File): FileChannel = RandomAccessFile(file, "rw").channel

        const val AAC_FRAME_SAMPLES = 1_024
        const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
        const val TRUN_FLAGS = 0x000001 or 0x000100 or 0x000200 or 0x000400

        /** 녹화 중 파일의 첫 부분입니다. 길이가 0인 트랙 설명과 조각 기본값(mvex)을 담습니다. */
        fun initSegment(video: VideoTrackConfig, audio: AudioTrackConfig): ByteArray {
            val sink = Mp4ByteSink(1024)
            Mp4Codec.ftyp(sink)
            val moov = sink.begin("moov")
            Mp4Codec.mvhd(sink, 0L, nextTrackId = 3)
            emptyTrack(sink, Mp4Codec.VIDEO_TRACK_ID, audio = false, Mp4Codec.VIDEO_TIMESCALE,
                video.width.toLong() shl 16, video.height.toLong() shl 16, Mp4Codec.videoSampleDescription(video))
            emptyTrack(sink, Mp4Codec.AUDIO_TRACK_ID, audio = true, audio.sampleRate, 0L, 0L,
                Mp4Codec.audioSampleDescription(audio))
            val mvex = sink.begin("mvex")
            listOf(Mp4Codec.VIDEO_TRACK_ID, Mp4Codec.AUDIO_TRACK_ID).forEach { id ->
                val trex = sink.beginFull("trex", 0, 0)
                sink.u32(id); sink.u32(1); sink.u32(0); sink.u32(0); sink.u32(0)
                sink.end(trex)
            }
            sink.end(mvex)
            sink.end(moov)
            return sink.toByteArray()
        }

        private fun emptyTrack(
            sink: Mp4ByteSink, id: Int, audio: Boolean, timescale: Int, width: Long, height: Long, stsd: ByteArray,
        ) {
            val trak = sink.begin("trak")
            Mp4Codec.tkhd(sink, id, 0L, audio, width, height)
            val mdia = sink.begin("mdia")
            Mp4Codec.mdhd(sink, timescale, 0L)
            Mp4Codec.hdlr(sink, audio)
            val minf = sink.begin("minf")
            Mp4Codec.mediaHeader(sink, audio)
            val stbl = sink.begin("stbl")
            sink.bytes(stsd)
            for (type in listOf("stts", "stsc", "stco")) {
                val box = sink.beginFull(type, 0, 0)
                sink.u32(0)
                sink.end(box)
            }
            val stsz = sink.beginFull("stsz", 0, 0)
            sink.u32(0); sink.u32(0)
            sink.end(stsz)
            sink.end(stbl)
            sink.end(minf)
            sink.end(mdia)
            sink.end(trak)
        }
    }
}
