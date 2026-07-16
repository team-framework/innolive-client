struct CameraSwitchTransactionResult: Equatable {
    let activeCameraID: String?
    let isRunning: Bool
}

enum CameraSwitchTransaction {
    static func execute(
        wasRunning: Bool,
        stop: () -> Void,
        resetBufferedFrames: () -> Void,
        replaceInput: () -> Void,
        start: () -> Void,
        activeCameraID: () -> String?,
        isRunning: () -> Bool
    ) -> CameraSwitchTransactionResult {
        if wasRunning {
            stop()
        }

        resetBufferedFrames()
        replaceInput()

        if wasRunning {
            start()
        }

        return CameraSwitchTransactionResult(
            activeCameraID: activeCameraID(),
            isRunning: isRunning()
        )
    }
}
