package com.framework.innolive.feature.live
import org.junit.Assert.*
import org.junit.Test
class LiveVideoPreviewPolicyTest {
    @Test fun onDeviceShowsLocalProtectionEvenIfServerReturnStops() {
        val local = Any(); val remote = Any()
        assertSame(local, liveMainVideoTrack(true, true, local, remote))
        assertSame(local, liveMainVideoTrack(true, true, local, null))
    }
    @Test fun onDeviceDoesNotSubstituteRemoteWhileProtectionIsNotReady() {
        assertNull(liveMainVideoTrack(true, true, null, Any()))
    }
    @Test fun serverAIContinuesToShowServerProtection() {
        val local=Any();val remote=Any()
        assertSame(remote, liveMainVideoTrack(false,true,local,remote))
        assertNull(liveMainVideoTrack(false,true,local,null))
        assertNull(liveMainVideoTrack(true,false,local,remote))
        assertNull(liveMainVideoTrack(false,false,local,remote))
    }
}
