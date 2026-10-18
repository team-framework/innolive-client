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
                renderingLocalTrack?.remove(localRenderer)
            }
            localRenderer = renderer
            renderingLocalTrack?.add(renderer)
        }
    }

    func detachLocalRenderer(_ renderer: LKRTCMTLVideoView) {
        renderingLocalTrack?.remove(renderer)
        if localRenderer === renderer { localRenderer = nil }
    }

    func attachRemoteRenderer(_ renderer: LKRTCMTLVideoView) {
        guard !remoteRenderers.contains(renderer) else { return }
        remoteRenderers.add(renderer)
        remoteVideoTrack?.add(renderer)
    }

    func detachRemoteRenderer(_ renderer: LKRTCMTLVideoView) {
        remoteVideoTrack?.remove(renderer)
        remoteRenderers.remove(renderer)
    }

    func attachFaceRegistrationRenderer(_ renderer: LKRTCMTLVideoView) {
        guard faceRegistrationRenderer !== renderer else { return }
        if let faceRegistrationRenderer {
            renderingLocalTrack?.remove(faceRegistrationRenderer)
        }
        faceRegistrationRenderer = renderer
        renderingLocalTrack?.add(renderer)
    }

    func detachFaceRegistrationRenderer(_ renderer: LKRTCMTLVideoView) {
        renderingLocalTrack?.remove(renderer)
        if faceRegistrationRenderer === renderer {
            faceRegistrationRenderer = nil
        }
    }

    func setRemoteVideoTrack(_ track: LKRTCVideoTrack) {
        let renderers = remoteRenderers.allObjects
        for renderer in renderers {
            remoteVideoTrack?.remove(renderer)
        }
        remoteVideoTrack = track
        for renderer in renderers {
            track.add(renderer)
        }
        setRemoteVideoAvailable(true)
    }

    func detachTracksFromRenderers() {
        if let localRenderer {
            renderingLocalTrack?.remove(localRenderer)
        }
        for renderer in remoteRenderers.allObjects {
            remoteVideoTrack?.remove(renderer)
        }
        if let faceRegistrationRenderer {
            renderingLocalTrack?.remove(faceRegistrationRenderer)
        }
        localRenderer = nil
        remoteRenderers.removeAllObjects()
        faceRegistrationRenderer = nil
    }

}
