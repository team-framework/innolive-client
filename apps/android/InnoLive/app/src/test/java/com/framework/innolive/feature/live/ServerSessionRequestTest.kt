package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSessionRequestTest {
    @Test fun serverSessionRequestCarriesSelectedResolution() {
        val fhd = buildServerSessionRequest(BroadcastProvider.YOUTUBE, DirectPublishResolution.FHD)
        assertEquals("youtube", fhd.getString("provider"))
        assertEquals("fhd", fhd.getString("broadcast_resolution"))
        assertEquals("innolive-android", fhd.getJSONObject("metadata").getString("client"))
        // 서버 AI 세션은 처리 위치를 보내지 않아 서버 기본값(server)을 씁니다.
        assertFalse(fhd.has("ai_processing"))
        assertEquals("720p", buildServerSessionRequest(BroadcastProvider.CHZZK, DirectPublishResolution.HD_720)
            .getString("broadcast_resolution"))
    }

    @Test fun faceSwapIsSentOnlyWhenSelectedForServerProcessing() {
        // 블러는 서버 기본값이라 생략합니다.
        assertFalse(buildServerSessionRequest(BroadcastProvider.YOUTUBE, DirectPublishResolution.HD_720)
            .has("face_anonymization"))
        val faceSwap = buildServerSessionRequest(
            BroadcastProvider.YOUTUBE,
            DirectPublishResolution.HD_720,
            sessionFaceAnonymization(faceSwapSelected = true, onDeviceProcessing = false),
        )
        assertEquals("face_swap", faceSwap.getString("face_anonymization"))
        // 서버가 온디바이스 세션의 합성을 400으로 거절하므로 보내지 않습니다.
        assertNull(sessionFaceAnonymization(faceSwapSelected = true, onDeviceProcessing = true))
        assertNull(sessionFaceAnonymization(faceSwapSelected = false, onDeviceProcessing = false))
    }
}
