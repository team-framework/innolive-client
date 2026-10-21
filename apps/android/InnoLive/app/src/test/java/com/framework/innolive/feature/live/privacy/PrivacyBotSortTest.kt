package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyBotSortTest {
    private class T(val id: Int, val activated: Boolean, val box: PrivacySegmentation.Box)
    private class L(val id: Int, val box: PrivacySegmentation.Box)
    private class Frame(val detections: List<PrivacySegmentation.Detection>, val ids: IntArray,
                        val tracked: List<T>, val lost: List<L>)

    private fun b(left: Float, top: Float, right: Float, bottom: Float) = PrivacySegmentation.Box(left, top, right, bottom)
    private fun det(left: Float, top: Float, right: Float, bottom: Float, score: Float, classId: Int) =
        PrivacySegmentation.Detection(b(left, top, right, bottom), score, classId, FloatArray(32))

    /**
     * ultralytics 8.4.104 `BOTSORT` with innolive-ai `config/botsort.yaml` (GMC none, ReID off) on the same frames:
     * a low-score continuation (frame 4), a missed and refound track (6-7), a track confirmed on its second frame
     * (8-9), a one-frame candidate that is removed (11-12), and a weak candidate that never starts a track.
     */
    private val reference = listOf(
        Frame(listOf(det(100f, 100f, 150f, 150f, 0.9f, 0), det(300f, 120f, 360f, 190f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(57f, 500f, 97f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(100f, 100f, 150f, 150f)), T(2, true, b(300f, 120f, 360f, 190f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf()),
        Frame(listOf(det(104f, 100f, 154f, 150f, 0.9f, 0), det(300f, 117f, 360f, 187f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(64f, 500f, 104f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(103.4711f, 100f, 153.4711f, 150f)), T(2, true, b(300f, 117.3967f, 360f, 187.3967f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf()),
        Frame(listOf(det(108f, 100f, 158f, 150f, 0.9f, 0), det(300f, 114f, 360f, 184f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(71f, 500f, 111f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(107.1838f, 100f, 157.1838f, 150f)), T(2, true, b(300f, 114.6121f, 360f, 184.6121f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf()),
        Frame(listOf(det(112f, 100f, 162f, 150f, 0.15f, 0), det(300f, 111f, 360f, 181f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(78f, 500f, 118f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(111.3379f, 100f, 161.3379f, 150f)), T(2, true, b(300f, 111.4966f, 360f, 181.4966f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf()),
        Frame(listOf(det(116f, 100f, 166f, 150f, 0.9f, 0), det(300f, 108f, 360f, 178f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(85f, 500f, 125f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(115.5017f, 100f, 165.5017f, 150f)), T(2, true, b(300f, 108.3737f, 360f, 178.3737f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf()),
        Frame(listOf(det(120f, 100f, 170f, 150f, 0.9f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(92f, 500f, 132f, 540f, 0.1f, 0)),
            intArrayOf(1, 3, -1),
            listOf(T(1, true, b(119.6178f, 100f, 169.6178f, 150f)), T(3, true, b(400f, 400f, 480f, 430f))),
            listOf(L(2, b(300f, 106.0092f, 360f, 176.0092f)))),
        Frame(listOf(det(124f, 100f, 174f, 150f, 0.9f, 0), det(300f, 102f, 360f, 172f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(99f, 500f, 139f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(123.6977f, 100f, 173.6977f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 102.2712f, 360f, 172.2712f))),
            listOf()),
        Frame(listOf(det(128f, 100f, 178f, 150f, 0.9f, 0), det(300f, 99f, 360f, 169f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(500f, 100f, 540f, 140f, 0.6f, 0), det(106f, 500f, 146f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1, -1),
            listOf(T(1, true, b(127.7545f, 100f, 177.7545f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 99.1895f, 360f, 169.1895f)), T(4, false, b(500f, 100f, 540f, 140f))),
            listOf()),
        Frame(listOf(det(132f, 100f, 182f, 150f, 0.9f, 0), det(300f, 96f, 360f, 166f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(502f, 100f, 542f, 140f, 0.6f, 0), det(113f, 500f, 153f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, 4, -1),
            listOf(T(1, true, b(131.7964f, 100f, 181.7964f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 96.1536f, 360f, 166.1536f)), T(4, true, b(501.6f, 100f, 541.6f, 140f))),
            listOf()),
        Frame(listOf(det(136f, 100f, 186f, 150f, 0.9f, 0), det(300f, 93f, 360f, 163f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(120f, 500f, 160f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1),
            listOf(T(1, true, b(135.8286f, 100f, 185.8286f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 93.1287f, 360f, 163.1287f))),
            listOf(L(4, b(501.6f, 100f, 541.6f, 140f)))),
        Frame(listOf(det(140f, 100f, 190f, 150f, 0.9f, 0), det(300f, 90f, 360f, 160f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(506f, 100f, 546f, 140f, 0.6f, 0), det(200f, 300f, 240f, 340f, 0.3f, 0), det(127f, 500f, 167f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, 4, -1, -1),
            listOf(T(1, true, b(139.8541f, 100f, 189.8541f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 90.1094f, 360f, 160.1094f)), T(5, false, b(200f, 300f, 240f, 340f)), T(4, true, b(505.5629f, 100f, 545.5629f, 140f))),
            listOf()),
        Frame(listOf(det(144f, 100f, 194f, 150f, 0.9f, 0), det(300f, 87f, 360f, 157f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(508f, 100f, 548f, 140f, 0.03f, 0), det(134f, 500f, 174f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, -1, -1),
            listOf(T(1, true, b(143.8747f, 100f, 193.8747f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 87.0939f, 360f, 157.0939f))),
            listOf(L(4, b(506.9357f, 100f, 546.9357f, 140f)))),
        Frame(listOf(det(148f, 100f, 198f, 150f, 0.9f, 0), det(300f, 84f, 360f, 154f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(510f, 100f, 550f, 140f, 0.6f, 0), det(141f, 500f, 181f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, 4, -1),
            listOf(T(1, true, b(147.8917f, 100f, 197.8917f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 84.0811f, 360f, 154.0811f)), T(4, true, b(509.778f, 100f, 549.778f, 140f))),
            listOf()),
        Frame(listOf(det(152f, 100f, 202f, 150f, 0.9f, 0), det(300f, 81f, 360f, 151f, 0.8f, 0), det(400f, 400f, 480f, 430f, 0.7f, 1), det(512f, 100f, 552f, 140f, 0.6f, 0), det(148f, 500f, 188f, 540f, 0.1f, 0)),
            intArrayOf(1, 2, 3, 4, -1),
            listOf(T(1, true, b(151.906f, 100f, 201.906f, 150f)), T(3, true, b(400f, 400f, 480f, 430f)), T(2, true, b(300f, 81.0704f, 360f, 151.0704f)), T(4, true, b(511.8602f, 100f, 551.8602f, 140f))),
            listOf()),
    )

    private fun assertBox(message: String, expected: PrivacySegmentation.Box, actual: PrivacySegmentation.Box) {
        assertEquals(message, expected.left, actual.left, 2e-3f)
        assertEquals(message, expected.top, actual.top, 2e-3f)
        assertEquals(message, expected.right, actual.right, 2e-3f)
        assertEquals(message, expected.bottom, actual.bottom, 2e-3f)
    }

    @Test fun matchesUltralyticsBotSortTrajectory() {
        val tracker = PrivacyBotSort()
        reference.forEachIndexed { index, frame ->
            val message = "frame=${index + 1}"
            assertArrayEquals(message, frame.ids, tracker.update(frame.detections))
            assertEquals(message, frame.tracked.map { it.id }, tracker.trackedTracks.map { it.id })
            assertEquals(message, frame.tracked.map { it.activated }, tracker.trackedTracks.map { it.isActivated })
            frame.tracked.zip(tracker.trackedTracks).forEach { (expected, actual) -> assertBox(message, expected.box, actual.box) }
            assertEquals(message, frame.lost.map { it.id }, tracker.lostTracks.map { it.id })
            frame.lost.zip(tracker.lostTracks).forEach { (expected, actual) -> assertBox(message, expected.box, actual.box) }
        }
    }

    @Test fun lostTrackIsRemovedAfterTrackBuffer() {
        val tracker = PrivacyBotSort()
        val face = det(100f, 100f, 150f, 150f, .9f, 0)
        assertArrayEquals(intArrayOf(1), tracker.update(listOf(face)))
        repeat(PrivacyBotSort.TRACK_BUFFER_FRAMES) { tracker.update(emptyList()) }
        assertEquals(listOf(1), tracker.lostTracks.map { it.id })
        tracker.update(emptyList())
        assertTrue(tracker.lostTracks.isEmpty())
    }

    @Test fun resetNeverReusesTrackIds() {
        val tracker = PrivacyBotSort()
        val face = listOf(det(100f, 100f, 150f, 150f, .9f, 0))
        assertArrayEquals(intArrayOf(1), tracker.update(face))
        tracker.reset()
        assertArrayEquals(intArrayOf(2), tracker.update(face))
    }

    @Test fun assignmentIsGloballyOptimalNotGreedy() {
        val result = linearAssignment(arrayOf(doubleArrayOf(.1, .2), doubleArrayOf(.15, .7)), 2, .8)
        assertEquals(listOf(0 to 1, 1 to 0), result.matches)
        assertTrue(result.unmatchedRows.isEmpty() && result.unmatchedCols.isEmpty())
    }

    @Test fun assignmentLeavesPairsAtOrAboveThresholdUnmatched() {
        val result = linearAssignment(arrayOf(doubleArrayOf(.85, .5), doubleArrayOf(.79, .9)), 2, .8)
        assertEquals(listOf(0 to 1, 1 to 0), result.matches)
        val unmatched = linearAssignment(arrayOf(doubleArrayOf(.85)), 1, .8)
        assertTrue(unmatched.matches.isEmpty())
        assertEquals(listOf(0), unmatched.unmatchedRows)
        assertEquals(listOf(0), unmatched.unmatchedCols)
        val noTracks = linearAssignment(arrayOf(), 2, .8)
        assertTrue(noTracks.matches.isEmpty() && noTracks.unmatchedRows.isEmpty())
        assertEquals("detections stay available for new tracks", listOf(0, 1), noTracks.unmatchedCols)
    }
}
