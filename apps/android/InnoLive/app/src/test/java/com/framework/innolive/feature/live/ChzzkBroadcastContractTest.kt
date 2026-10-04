package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.feature.youtube.StreamingAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test fun emptyTargetsConfirmIdleEvenWithAnOlderTopLevelStream() {
        val payload = """{"stream":{"broadcast_phase":"live","status":"streaming"},"targets":[]}"""
        for (provider in BroadcastProvider.entries) {
            assertEquals(BroadcastState.IDLE, parseBroadcastState(payload, provider))
        }
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

    @Test fun accountMutationInvalidatesPreviousVerificationUntilFreshResponse() {
        val oldAccount = StreamingAccount("chzzk", "old", "old channel", false)
        val verified = ChzzkAccountVerification().confirm(0, oldAccount)
        assertTrue(verified.canPrepare)

        val pending = verified.invalidate()
        assertFalse(pending.canPrepare)
        assertNull(pending.account)
        assertFalse(pending.confirm(0, oldAccount).canPrepare)

        val reconnectRequired = pending.confirm(
            pending.revision, StreamingAccount("chzzk", "new", "new channel", true))
        assertFalse(reconnectRequired.canPrepare)
        assertTrue(reconnectRequired.confirm(reconnectRequired.revision,
            StreamingAccount("chzzk", "new", "new channel", false)).canPrepare)
    }

    @Test fun reopeningSettingsDuringAccountDeletionCannotVerifyTheOldAccount() {
        val oldAccount = StreamingAccount("chzzk", "old", "old channel", false)
        val verified = ChzzkAccountVerification().confirm(0, oldAccount)
        val deleting = verified.beginMutation()

        assertTrue(deleting.mutationInProgress)
        assertNull(deleting.beginRefresh())
        assertFalse(deleting.confirm(deleting.revision, oldAccount).canPrepare)
        assertFalse(deleting.canPrepare)

        val finished = deleting.finishMutation(deleting.revision)
        assertFalse(finished.mutationInProgress)
        assertFalse(finished.canPrepare)
        val refreshed = finished.beginRefresh()!!
        assertFalse(refreshed.canPrepare)
        assertFalse(refreshed.confirm(refreshed.revision, null).canPrepare)
    }

    @Test fun emptySettingsRequireSuccessfulDefaultsBeforeSaving() {
        assertThrows(IOException::class.java) {
            resolveChzzkSettingsForPrepare(ChzzkBroadcastSettings()) {
                throw IOException("defaults unavailable")
            }
        }
        val explicit = ChzzkBroadcastSettings("직접 입력", "GAME", "game-id")
        assertEquals(explicit, resolveChzzkSettingsForPrepare(explicit) {
            throw AssertionError("explicit settings must not load defaults")
        })
    }

    @Test fun youtubeSettingsSaveIsDisabledForChzzkEvenWhenPreviewIsConnectedAndIdle() {
        assertEquals(R.string.broadcast_settings_youtube_only,
            broadcastSettingsSaveDisabledReason(BroadcastProvider.CHZZK,
                WebRtcConnectionState.CONNECTED, BroadcastState.IDLE, audienceSelected = true))
        assertNull(broadcastSettingsSaveDisabledReason(BroadcastProvider.YOUTUBE,
            WebRtcConnectionState.CONNECTED, BroadcastState.IDLE, audienceSelected = true))
    }
}
