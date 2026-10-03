package com.framework.innolive.feature.live

import com.framework.innolive.feature.youtube.StreamingAccount

internal data class ChzzkAccountVerification(
    val account: StreamingAccount? = null,
    val verified: Boolean = false,
    val revision: Long = 0,
) {
    val canPrepare: Boolean
        get() = verified && account?.let { it.provider == "chzzk" && !it.reconnectRequired } == true

    fun invalidate(): ChzzkAccountVerification =
        copy(account = null, verified = false, revision = revision + 1)

    fun confirm(expectedRevision: Long, currentAccount: StreamingAccount?): ChzzkAccountVerification =
        if (revision == expectedRevision) copy(account = currentAccount, verified = true) else this
}
