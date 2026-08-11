import AVFoundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func switchAudioInput(to audioInputID: String) throws {
        guard isAudioSessionActivated, localAudioTrack != nil else {
            throw WebRTCVideoUplinkError.failed("비식별화를 시작한 뒤 오디오 기기를 전환해 주세요.")
        }

        let audioSession = LKRTCAudioSession.sharedInstance()
        guard let input = audioSession.session.availableInputs?.first(where: { $0.uid == audioInputID }) else {
            throw WebRTCVideoUplinkError.failed("선택한 오디오 기기를 사용할 수 없습니다.")
        }

        do {
            audioSession.lockForConfiguration()
            defer { audioSession.unlockForConfiguration() }
            try audioSession.setPreferredInput(input)
            activeAudioInputID = input.uid
        } catch {
            throw WebRTCVideoUplinkError.failed("오디오 기기를 전환하지 못했습니다: \(error.localizedDescription)")
        }
    }

    func prepareNativeMicrophone(
        preferredAudioID: String?,
        operationGeneration: UInt
    ) async throws {
        guard await requestMicrophoneAccess() else {
            markMediaPermissionRequired()
            throw WebRTCVideoUplinkError.failed("마이크 권한이 필요합니다. 설정에서 마이크 접근을 허용해 주세요.")
        }
        try ensureCurrentCameraOperation(operationGeneration)

        let audioSession = LKRTCAudioSession.sharedInstance()
        let configuration = LKRTCAudioSessionConfiguration.webRTC()
        configuration.category = AVAudioSession.Category.playAndRecord.rawValue
        configuration.mode = AVAudioSession.Mode.videoChat.rawValue
        configuration.categoryOptions = [.allowBluetoothHFP, .defaultToSpeaker]

        do {
            audioSession.lockForConfiguration()
            defer { audioSession.unlockForConfiguration() }
            try audioSession.setConfiguration(configuration, active: true)
            isAudioSessionActivated = true

            if let preferredAudioID,
               let preferredInput = audioSession.session.availableInputs?.first(where: {
                   $0.uid == preferredAudioID
               }) {
                try audioSession.setPreferredInput(preferredInput)
                activeAudioInputID = preferredInput.uid
            } else {
                activeAudioInputID = audioSession.session.currentRoute.inputs.first?.uid
                    ?? audioSession.session.availableInputs?.first?.uid
            }
        } catch {
            throw WebRTCVideoUplinkError.failed("마이크를 준비하지 못했습니다: \(error.localizedDescription)")
        }

        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let source = peerConnectionFactory.audioSource(with: constraints)
        let track = peerConnectionFactory.audioTrack(with: source, trackId: "innolive-microphone")
        track.isEnabled = true
        audioSource = source
        localAudioTrack = track
    }

    private func requestMicrophoneAccess() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    func deactivateAudioSessionIfNeeded() {
        guard isAudioSessionActivated else { return }
        let audioSession = LKRTCAudioSession.sharedInstance()
        audioSession.lockForConfiguration()
        defer { audioSession.unlockForConfiguration() }
        do {
            try audioSession.setActive(false)
            isAudioSessionActivated = false
        } catch {
            print("오디오 session을 종료하지 못했습니다: \(error.localizedDescription)")
        }
    }

}
