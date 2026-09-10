@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func attachRenderers(local: LKRTCMTLVideoView, remote: LKRTCMTLVideoView) {
        attachLocalRenderer(local)
        attachRemoteRenderer(remote)
    }

    func detachRenderers(local: LKRTCMTLVideoView, remote: LKRTCMTLVideoView) {
        detachLocalRenderer(local)
        detachRemoteRenderer(remote)
    }

    func attachLocalRenderer(_ renderer: LKRTCMTLVideoView) {
        if localRenderer !== renderer {
            if let localRenderer {
                localVideoTrack?.remove(localRenderer)
            }
            localRenderer = renderer
            localVideoTrack?.add(renderer)
        }
    }

    func detachLocalRenderer(_ renderer: LKRTCMTLVideoView) {
        localVideoTrack?.remove(renderer)
        if localRenderer === renderer { localRenderer = nil }
    }

    func attachRemoteRenderer(_ renderer: LKRTCMTLVideoView) {
        if remoteRenderer !== renderer {
            if let remoteRenderer {
                remoteVideoTrack?.remove(remoteRenderer)
            }
            remoteRenderer = renderer
            remoteVideoTrack?.add(renderer)
        }
    }

    func detachRemoteRenderer(_ renderer: LKRTCMTLVideoView) {
        remoteVideoTrack?.remove(renderer)
        if remoteRenderer === renderer { remoteRenderer = nil }
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
