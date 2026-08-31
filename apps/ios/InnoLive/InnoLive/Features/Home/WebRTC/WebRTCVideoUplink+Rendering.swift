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

    func attachFaceRegistrationRenderer(_ renderer: LKRTCMTLVideoView) {
        guard faceRegistrationRenderer !== renderer else { return }
        if let faceRegistrationRenderer {
            localVideoTrack?.remove(faceRegistrationRenderer)
        }
        faceRegistrationRenderer = renderer
        localVideoTrack?.add(renderer)
    }

    func detachFaceRegistrationRenderer(_ renderer: LKRTCMTLVideoView) {
        localVideoTrack?.remove(renderer)
        if faceRegistrationRenderer === renderer {
            faceRegistrationRenderer = nil
        }
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
        if let faceRegistrationRenderer {
            localVideoTrack?.remove(faceRegistrationRenderer)
        }
        localRenderer = nil
        remoteRenderer = nil
        faceRegistrationRenderer = nil
    }

}
