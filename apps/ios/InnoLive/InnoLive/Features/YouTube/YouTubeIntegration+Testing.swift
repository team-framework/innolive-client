import Foundation

#if DEBUG
extension YouTubeIntegration {
    func applyLiveEditingSnapshotForTesting(_ snapshot: YouTubeSessionResponse) {
        guard let session else { return }
        _ = applySessionResponse(snapshot, sessionID: session.sessionID, generation: sessionOperationGeneration)
    }

    func markServerVideoReadyForTesting() {
        videoUplink.updateState(.connected, "connected")
        videoTrack = YouTubeVideoTrackState(id: "video", kind: "video", readyState: "live")
        videoUplink.setRemoteVideoAvailable(true)
    }

    func seedPreparedVideoSessionForTesting(
        connection: YouTubeConnection?,
        session: YouTubeBroadcastSession,
        videoTrack: YouTubeVideoTrackState,
        preparationStatus: BroadcastPreparationStatus? = nil,
        streamingAccounts: [StreamingAccountSummary]? = nil
    ) {
        if let streamingAccounts { self.streamingAccounts = streamingAccounts }
        self.preparationStatus = preparationStatus
        self.connection = connection
        self.session = session
        self.stream = session.stream
        self.responseState.stream = session.stream
        self.responseState.details = session.details
        self.videoTrack = videoTrack
        videoUplink.setRemoteVideoAvailable(true)
        videoUplink.updateState(.connected, "connected")
    }
}
#endif
