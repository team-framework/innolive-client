package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChzzkBroadcastContractTest {
    @Test fun settingsPayloadContainsOnlyChzzkFields() {
        val value = ChzzkBroadcastSettings("치지직 방송", "GAME", "GTA5", listOf("게임", "retro2"))
        assertNull(value.validationField())
        val json = buildChzzkSettingsPayload(value)
        assertEquals(setOf("title", "category_type", "category_id", "tags"), json.keys().asSequence().toSet())
        assertFalse(json.has("made_for_kids"))
        assertFalse(json.has("privacy"))
    }

    @Test fun invalidCategoryAndTagsAreRejectedBeforeSave() {
        assertEquals("category_type", ChzzkBroadcastSettings(categoryType = "MUSIC").validationField())
        assertEquals("category_id", ChzzkBroadcastSettings(categoryType = "GAME").validationField())
        assertEquals("tags[0]", ChzzkBroadcastSettings(tags = listOf("고전 명작")).validationField())
        assertEquals("tags[0]", ChzzkBroadcastSettings(tags = listOf("retro!")).validationField())
        assertEquals("tags", ChzzkBroadcastSettings(tags = List(6) { "tag$it" }).validationField())
    }

    @Test fun prepareResponseCannotBeMistakenForLiveOrAnotherTarget() {
        val prepared = """{"stream":{"broadcast_phase":"prepared","status":"idle"},"targets":[{"provider":"chzzk","stream":{"broadcast_phase":"prepared","status":"idle"}}]}"""
        assertEquals(BroadcastState.PREPARED, parseBroadcastState(prepared, BroadcastProvider.CHZZK))
        assertNull(parseBroadcastState(prepared, BroadcastProvider.YOUTUBE))
        assertEquals(BroadcastState.PAUSED, parseBroadcastState("""{"broadcast_phase":"live","status":"paused"}""", BroadcastProvider.CHZZK))
    }

    @Test fun callbackRequiresExactRedirectAndMatchingState() {
        val redirect = "https://innolive.studio/auth/chzzk/callback"
        assertEquals("abc", parseChzzkCallback("$redirect?code=abc&state=expected", redirect, "expected"))
        assertNull(parseChzzkCallback("https://evil.example/cb?code=abc&state=expected", redirect, "expected"))
        assertThrows(SecurityException::class.java) {
            parseChzzkCallback("$redirect?code=abc&state=other", redirect, "expected")
        }
        assertThrows(SecurityException::class.java) {
            parseChzzkCallback("$redirect?code=abc&state=expected&state=expected", redirect, "expected")
        }
        assertTrue(newChzzkOAuthState().length >= 40)
    }

    @Test fun serverErrorsDescribeRecoveryWithoutLeakingServerText() {
        assertTrue(chzzkBroadcastErrorMessage("streaming_not_connected", null).contains("연결"))
        assertTrue(chzzkBroadcastErrorMessage("streaming_reconnect_required", null).contains("다시 연결"))
        assertTrue(chzzkBroadcastErrorMessage("not_supported", null).contains("서버"))
        assertTrue(chzzkBroadcastErrorMessage("broadcast_not_ready", null).contains("준비"))
        assertTrue(chzzkBroadcastErrorMessage("bad_request", "tags[0]").contains("태그"))
    }
}
