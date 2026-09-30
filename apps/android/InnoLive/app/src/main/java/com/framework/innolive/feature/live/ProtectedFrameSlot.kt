package com.framework.innolive.feature.live

/** One processing frame plus the newest waiting frame; callers own and close displaced inputs. */
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
