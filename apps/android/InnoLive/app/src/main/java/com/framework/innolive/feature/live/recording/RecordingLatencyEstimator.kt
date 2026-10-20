package com.framework.innolive.feature.live.recording

import org.webrtc.VideoFrame
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 서버 AI 모드에서 서버가 돌려준 영상(블러 후)과 카메라 원본 사이의 지연을 영상 내용으로 잽니다.
 *
 * 돌려받은 프레임의 타임스탬프는 서버 왕복·지터 버퍼를 거친 화면 표시 시각이고 음성은 기기 마이크 시각이라
 * 그대로 저장하면 영상이 왕복 지연만큼 늦습니다. 서버는 RTP 시각을 다시 만들고 Android WebRTC는 받은
 * 프레임의 RTP 시각을 넘기지 않으므로, 두 영상의 프레임 간 움직임 크기(16×16 밝기 격자의 평균 차이)를
 * 시계열로 만들고 상호상관이 가장 큰 지연을 찾습니다. 움직임은 회전·색 보정·얼굴 블러에도 거의 같게
 * 남습니다. 신뢰도가 충분한 측정값의 중앙값을 녹화 끝에서 영상 트랙 시작에 한 번 적용합니다.
 *
 * [addRaw]·[addProcessed]는 캡처·디코더 스레드에서, [estimate]는 작업 스레드에서 부릅니다.
 */
internal class RecordingLatencyEstimator(
    private val maxLagUs: Long = 3_000_000L,
    private val lagStepUs: Long = 10_000L,
    private val windowUs: Long = 8_000_000L,
    private val rawHistoryUs: Long = 12_000_000L,
    private val minSamples: Int = 60,
    private val minCorrelation: Double = 0.5,
    private val minProminence: Double = 0.15,
    private val maxAccepted: Int = 31,
) {
    private class Series(capacity: Int) {
        var times = LongArray(capacity)
        var values = DoubleArray(capacity)
        var size = 0
        var previous: FloatArray? = null
        var previousTimeUs = Long.MIN_VALUE

        fun add(timeUs: Long, value: Double) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                values = values.copyOf(size * 2)
            }
            times[size] = timeUs
            values[size] = value
            size++
        }

        fun trimBefore(timeUs: Long) {
            var first = 0
            while (first < size && times[first] < timeUs) first++
            if (first == 0) return
            System.arraycopy(times, first, times, 0, size - first)
            System.arraycopy(values, first, values, 0, size - first)
            size -= first
        }
    }

    private val lock = Any()
    private val raw = Series(512)
    private val processed = Series(512)
    private val accepted = ArrayDeque<Long>()

    /** 지금까지 받아들인 측정값의 중앙값(µs)입니다. 측정값이 없으면 null입니다. */
    val offsetUs: Long?
        get() = synchronized(lock) {
            if (accepted.isEmpty()) null else accepted.sorted()[accepted.size / 2]
        }

    val acceptedCount: Int get() = synchronized(lock) { accepted.size }

    fun addRaw(timeUs: Long, grid: FloatArray) = synchronized(lock) {
        add(raw, timeUs, grid)
        raw.trimBefore(timeUs - rawHistoryUs)
    }

    fun addProcessed(timeUs: Long, grid: FloatArray) = synchronized(lock) {
        add(processed, timeUs, grid)
        processed.trimBefore(timeUs - windowUs)
    }

    /** 처리 영상 표본 수입니다. 측정 주기를 정하는 데 씁니다. */
    val processedSamples: Int get() = synchronized(lock) { processed.size }

    private fun add(series: Series, timeUs: Long, grid: FloatArray) {
        val previous = series.previous
        val interval = timeUs - series.previousTimeUs
        series.previous = grid
        series.previousTimeUs = timeUs
        if (previous == null || previous.size != grid.size || interval <= 0 || interval > MAX_INTERVAL_US) return
        var sum = 0.0
        for (index in grid.indices) sum += abs(grid[index] - previous[index])
        // 프레임이 빠지면 차이가 커지므로 기본 프레임 간격으로 나눠 움직임 속도로 맞춥니다.
        series.add(timeUs, sum / grid.size * NOMINAL_INTERVAL_US / interval)
    }

    /** 지금 창에서 지연을 한 번 잽니다. 받아들인 측정값이 있으면 그 값을 돌려줍니다. */
    fun estimate(): Long? {
        val (rawTimes, rawValues, procTimes, procValues) = synchronized(lock) {
            Snapshot(raw.times.copyOf(raw.size), raw.values.copyOf(raw.size),
                processed.times.copyOf(processed.size), processed.values.copyOf(processed.size))
        }
        if (procTimes.size < minSamples || rawTimes.size < minSamples) return null
        val procMean = procValues.average()
        val procStd = sqrt(procValues.sumOf { (it - procMean) * (it - procMean) } / procValues.size)
        if (procStd < MIN_MOTION_STD) return null
        val lags = (0..(maxLagUs / lagStepUs).toInt()).map { it * lagStepUs }
        val scores = DoubleArray(lags.size) { index -> correlation(rawTimes, rawValues, procTimes, procValues, lags[index]) }
        var best = -1
        for (index in scores.indices) if (!scores[index].isNaN() && (best < 0 || scores[index] > scores[best])) best = index
        if (best < 0 || scores[best] < minCorrelation) return null
        val exclusion = (PEAK_EXCLUSION_US / lagStepUs).toInt()
        val runnerUp = scores.indices.filter { abs(it - best) > exclusion && !scores[it].isNaN() }
            .maxOfOrNull { scores[it] } ?: Double.NEGATIVE_INFINITY
        if (scores[best] - runnerUp < minProminence) return null
        var lag = lags[best].toDouble()
        if (best in 1 until scores.size - 1 && !scores[best - 1].isNaN() && !scores[best + 1].isNaN()) {
            val left = scores[best - 1]
            val right = scores[best + 1]
            val denominator = left - 2 * scores[best] + right
            if (denominator < 0) lag += (left - right) / (2 * denominator) * lagStepUs
        }
        val measured = lag.toLong().coerceIn(0L, maxLagUs)
        synchronized(lock) {
            accepted.addLast(measured)
            while (accepted.size > maxAccepted) accepted.removeFirst()
        }
        return offsetUs
    }

    private data class Snapshot(val rawTimes: LongArray, val rawValues: DoubleArray, val procTimes: LongArray, val procValues: DoubleArray)

    /** 처리 영상 시각에서 [lagUs]를 뺀 원본 값과의 피어슨 상관입니다. 겹치는 표본이 모자라면 NaN입니다. */
    private fun correlation(rawTimes: LongArray, rawValues: DoubleArray, procTimes: LongArray, procValues: DoubleArray, lagUs: Long): Double {
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        var cursor = 0
        for (index in procTimes.indices) {
            val target = procTimes[index] - lagUs
            if (target < rawTimes[0] || target > rawTimes[rawTimes.size - 1]) continue
            while (cursor < rawTimes.size - 2 && rawTimes[cursor + 1] < target) cursor++
            val t0 = rawTimes[cursor]
            val t1 = rawTimes[minOf(cursor + 1, rawTimes.size - 1)]
            val x = if (t1 == t0) rawValues[cursor]
                else rawValues[cursor] + (rawValues[minOf(cursor + 1, rawTimes.size - 1)] - rawValues[cursor]) *
                    ((target - t0).toDouble() / (t1 - t0))
            val y = procValues[index]
            count++
            sumX += x; sumY += y; sumXX += x * x; sumYY += y * y; sumXY += x * y
        }
        if (count < minSamples || count < procTimes.size * 3 / 4) return Double.NaN
        val covariance = sumXY - sumX * sumY / count
        val varianceX = sumXX - sumX * sumX / count
        val varianceY = sumYY - sumY * sumY / count
        if (varianceX <= 0.0 || varianceY <= 0.0) return Double.NaN
        return covariance / sqrt(varianceX * varianceY)
    }

    private companion object {
        const val NOMINAL_INTERVAL_US = 33_333.0
        const val MAX_INTERVAL_US = 250_000L
        const val PEAK_EXCLUSION_US = 60_000L
        const val MIN_MOTION_STD = 0.05
    }
}

/** 지연 측정용 밝기 격자입니다. 큰 프레임은 점 몇 개만 읽고, GPU 프레임은 작게 줄여 읽습니다. */
internal object RecordingFrameProbe {
    const val GRID = 16
    private const val READBACK = GRID * 2

    fun lumaGrid(buffer: VideoFrame.Buffer): FloatArray? {
        if (buffer is VideoFrame.I420Buffer) return sample(buffer)
        val scaled = buffer.cropAndScale(0, 0, buffer.width, buffer.height, READBACK, READBACK)
        try {
            val i420 = scaled.toI420() ?: return null
            try {
                return sample(i420)
            } finally {
                i420.release()
            }
        } finally {
            scaled.release()
        }
    }

    /** 칸마다 2×2 점의 평균 밝기입니다. */
    fun sample(buffer: VideoFrame.I420Buffer): FloatArray? {
        val width = buffer.width
        val height = buffer.height
        if (width < GRID || height < GRID) return null
        val luma = buffer.dataY
        val base = luma.position()
        val stride = buffer.strideY
        val grid = FloatArray(GRID * GRID)
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                var sum = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val x = ((column * 4 + 1 + dx * 2) * width / (GRID * 4)).coerceIn(0, width - 1)
                    val y = ((row * 4 + 1 + dy * 2) * height / (GRID * 4)).coerceIn(0, height - 1)
                    sum += luma.get(base + y * stride + x).toInt() and 0xff
                }
                grid[row * GRID + column] = sum / 4f
            }
        }
        return grid
    }
}
