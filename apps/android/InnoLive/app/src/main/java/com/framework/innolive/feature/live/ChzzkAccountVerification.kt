package com.framework.innolive.feature.live

import com.framework.innolive.feature.youtube.StreamingAccount

internal data class ChzzkAccountVerification(
    val account: StreamingAccount? = null,
    val verified: Boolean = false,
    val revision: Long = 0,
    val mutationRevision: Long? = null,
) {
    val mutationInProgress: Boolean get() = mutationRevision != null

    val canPrepare: Boolean
        get() = !mutationInProgress && verified &&
            account?.let { it.provider == "chzzk" && !it.reconnectRequired } == true

    fun invalidate(): ChzzkAccountVerification =
        copy(account = null, verified = false, revision = revision + 1)

    fun beginRefresh(): ChzzkAccountVerification? =
        if (mutationInProgress) null else invalidate()

    fun beginMutation(): ChzzkAccountVerification {
        check(!mutationInProgress) { "치지직 계정 변경이 진행 중입니다." }
        val pending = invalidate()
        return pending.copy(mutationRevision = pending.revision)
    }

    fun finishMutation(expectedRevision: Long): ChzzkAccountVerification =
        if (mutationRevision == expectedRevision) copy(mutationRevision = null) else this

    fun confirm(expectedRevision: Long, currentAccount: StreamingAccount?): ChzzkAccountVerification =
        if (!mutationInProgress && revision == expectedRevision)
            copy(account = currentAccount, verified = true) else this
}
