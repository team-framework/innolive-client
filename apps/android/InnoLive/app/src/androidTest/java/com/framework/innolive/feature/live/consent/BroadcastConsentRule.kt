package com.framework.innolive.feature.live.consent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** 테스트 동안 방송 동의 기록을 [accepted]로 맞추고, 끝나면 기기의 원래 기록으로 되돌립니다. */
class BroadcastConsentRule internal constructor(
    private val accepted: Set<BroadcastConsentStep>,
) : ExternalResource() {
    /** 모든 동의·안내를 수락한 기기로 맞춥니다. */
    constructor() : this(BroadcastConsentStep.entries.toSet())

    private val store = BroadcastConsentStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private var saved: List<BroadcastConsentStep> = emptyList()

    override fun before() {
        saved = BroadcastConsentStep.entries.filter(store::hasAccepted)
        store.clearAll()
        accepted.forEach(store::record)
    }

    override fun after() {
        store.clearAll()
        saved.forEach(store::record)
    }
}
