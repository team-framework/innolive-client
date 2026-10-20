package com.framework.innolive.feature.live.recording

import java.io.File
import java.nio.ByteBuffer

/** 테스트용 MP4 상자 읽기입니다. */
internal class TestBox(val type: String, val start: Int, val size: Int, val data: ByteArray) {
    val payloadStart: Int get() = start + 8
    fun children(offset: Int = 0): List<TestBox> = readBoxes(data, payloadStart + offset, start + size)
    fun child(type: String, offset: Int = 0): TestBox? = children(offset).firstOrNull { it.type == type }
    fun path(vararg types: String): TestBox? {
        var current: TestBox = this
        for (type in types) current = current.child(type) ?: return null
        return current
    }
    fun int(at: Int): Int = ByteBuffer.wrap(data, payloadStart + at, 4).int
    fun long(at: Int): Long = ByteBuffer.wrap(data, payloadStart + at, 8).long
}

internal fun readBoxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<TestBox> {
    val boxes = mutableListOf<TestBox>()
    var position = from
    while (position + 8 <= to) {
        var size = ByteBuffer.wrap(data, position, 4).int
        val type = String(data, position + 4, 4, Charsets.US_ASCII)
        // 64비트 크기(largesize)는 테스트 파일 크기 안에서만 다룹니다.
        if (size == 1 && position + 16 <= to) size = ByteBuffer.wrap(data, position + 8, 8).long.toInt()
        if (size < 8 || position + size > to) break
        boxes += TestBox(type, position, size, data)
        position += size
    }
    return boxes
}

internal fun readTopLevel(file: File): List<TestBox> = readBoxes(file.readBytes())

internal val TEST_SPS = byteArrayOf(0x67, 0x64, 0x00, 0x1f, 0xac.toByte(), 0xd9.toByte(), 0x40)
internal val TEST_PPS = byteArrayOf(0x68, 0xeb.toByte(), 0xe3.toByte(), 0xcb.toByte())
internal val TEST_VIDEO = VideoTrackConfig(1280, 720, listOf(TEST_SPS), listOf(TEST_PPS))
internal val TEST_AUDIO = AudioTrackConfig(48_000, 2, byteArrayOf(0x11, 0x90.toByte()))

/** 4바이트 길이가 붙은 가짜 H.264 표본입니다. 첫 바이트로 순서를 구분합니다. */
internal fun lengthPrefixedSample(index: Int, keyFrame: Boolean, size: Int = 40): ByteArray {
    val nal = ByteArray(size) { (index + it).toByte() }
    nal[0] = if (keyFrame) 0x65 else 0x41
    return ByteBuffer.allocate(4 + size).putInt(size).put(nal).array()
}

/** 영상 30fps·음성 AAC 프레임을 [seconds]초 동안 씁니다. 영상은 1초마다 키프레임입니다. */
internal fun writeSyntheticRecording(
    file: File,
    seconds: Int,
    epochUs: Long = 0L,
    videoStartUs: Long = 0L,
    audioStartUs: Long = 0L,
    finish: Boolean = true,
): FragmentedMp4Writer {
    val writer = FragmentedMp4Writer(file, TEST_VIDEO, TEST_AUDIO, epochUs)
    val endUs = seconds * 1_000_000L
    var videoIndex = 0
    var audioIndex = 0
    while (true) {
        val videoUs = videoStartUs + videoIndex * 1_000_000L / 30
        val audioUs = audioStartUs + audioIndex * 1_024L * 1_000_000L / 48_000
        if (videoUs >= endUs && audioUs >= endUs) break
        if (videoUs <= audioUs && videoUs < endUs) {
            writer.writeVideo(lengthPrefixedSample(videoIndex, videoIndex % 30 == 0), epochUs + videoUs, videoIndex % 30 == 0)
            videoIndex++
        } else {
            writer.writeAudio(ByteArray(64) { audioIndex.toByte() }, epochUs + audioUs)
            audioIndex++
        }
    }
    if (finish) writer.finish()
    return writer
}
