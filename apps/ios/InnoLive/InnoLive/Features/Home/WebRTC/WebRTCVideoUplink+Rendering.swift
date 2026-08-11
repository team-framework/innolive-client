@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func attachRenderers(local: LKRTCMTLVideoView, remote: LKRTCMTLVideoView) {
        if localRenderer !== local {
            if let localRenderer {
                localVideoTrack?.remove(localRenderer)
            }
            localRenderer = local
            localVideoTrack?.add(local)
        }

        if remoteRenderer !== remote {
            if let remoteRenderer {
                remoteVideoTrack?.remove(remoteRenderer)
            }
            remoteRenderer = remote
            remoteVideoTrack?.add(remote)
        }
    }

    func detachRenderers(local: LKRTCMTLVideoView, remote: LKRTCMTLVideoView) {
        localVideoTrack?.remove(local)
        remoteVideoTrack?.remove(remote)
        if localRenderer === local { localRenderer = nil }
        if remoteRenderer === remote { remoteRenderer = nil }
    }

    func setRemoteVideoTrack(_ track: LKRTCVideoTrack) {
        if let remoteRenderer {
            remoteVideoTrack?.remove(remoteRenderer)
        }
        remoteVideoTrack = track
        if let remoteRenderer {
            track.add(remoteRenderer)
        }
        setRemoteVideoAvailable(true)
    }

    func detachTracksFromRenderers() {
        if let localRenderer {
            localVideoTrack?.remove(localRenderer)
        }
        if let remoteRenderer {
            remoteVideoTrack?.remove(remoteRenderer)
        }
        localRenderer = nil
        remoteRenderer = nil
    }

}
