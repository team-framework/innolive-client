// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "InnoLiveCameraSwitchCore",
    platforms: [
        .macOS(.v15)
    ],
    targets: [
        .target(
            name: "CameraSwitchCore",
            path: "InnoLive/Camera",
            exclude: [
                "CameraManager.swift",
                "CameraPreview.swift",
                "SceneRecorder.swift"
            ],
            sources: ["CameraSwitchTransaction.swift"]
        ),
        .testTarget(
            name: "CameraSwitchCoreTests",
            dependencies: ["CameraSwitchCore"],
            path: "Tests/CameraSwitchCoreTests"
        )
    ]
)
