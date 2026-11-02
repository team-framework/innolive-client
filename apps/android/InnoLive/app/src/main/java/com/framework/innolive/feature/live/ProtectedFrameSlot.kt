package com.framework.innolive.feature.live

/** 처리 중 프레임 하나와 가장 최근 대기 프레임 하나를 둡니다. 밀려난 입력은 호출자가 소유하고 닫습니다. */
internal class ProtectedFrameSlot<T> {
    data class Offer<T>(val start:Boolean,val displaced:T?)
    private var busy=false
    private var pending:T?=null

    @Synchronized fun offer(item:T):Offer<T> {
        if(!busy) {busy=true;return Offer(true,null)}
        val displaced=pending
        pending=item
        return Offer(false,displaced)
    }

    @Synchronized fun finish():T? {
        val next=pending
        pending=null
        if(next==null)busy=false
        return next
    }

    @Synchronized fun clearPending():T? = pending.also {pending=null}

    @Synchronized fun abort():T? {
        busy=false
        return pending.also {pending=null}
    }
}
