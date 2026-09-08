# iOS Simulator MediaSource

## Purpose

The iOS Simulator has no physical camera input. The simulator path therefore
uses a decoded video file while the device path keeps the AVFoundation camera
capture flow. Both paths feed the same WebRTC video source and track.

## Flow

Before this change, the iOS uplink created an `LKRTCCameraVideoCapturer` as
part of its WebRTC setup. `CameraManager` also owned a camera preview for the
pre-broadcast state, so a simulator could reach the UI without producing a
usable video frame.

The final flow is:

```text
MediaSourceDebugConfiguration
        |
        +-- camera -> CameraSource -> WebRTCCameraFrameRelay
        |                              |
        +-- file   -> FileSource -----+
                                       v
                         WebRTCVideoFrameConsumer
                                       v
                         LKRTCVideoSource -> LKRTCVideoTrack
                                       v
                              WebRTCVideoUplink
```

`CameraSource` owns camera start, stop, switching, quality changes, and face
registration frame delivery. `FileSource` decodes BGRA pixel buffers on a
serial queue, paces delivery from the source timestamps, bounds pending frames,
and drops frames that arrive more than 100 ms late. Each loop pass normalizes
its first source timestamp and keeps the output timestamp monotonic. The
consumer is shared by both sources, so the WebRTC track and renderer contract
does not change with the selected input.

## Simulator selection

Debug builds select media in this order:

1. `MediaSourceDebugConfiguration`'s in-process override;
2. `-INNOLIVE_MEDIA_URL <absolute-path>` or `INNOLIVE_MEDIA_URL`;
3. the bundled `SimulatorFixture.mp4` when running in the Simulator;
4. the camera source.

Launch with `-INNOLIVE_DEBUG_MEDIA_PREVIEW` or
`INNOLIVE_DEBUG_MEDIA_PREVIEW=1` to open the DEBUG harness. The harness shows
the decoded frame, source metrics, file selection, and rotation. The importer
copies the security-scoped file into the app cache before playback. “앱으로
돌아가기” keeps that selection and returns to the normal app flow, allowing a
selected file to reach the production uplink. “카메라” explicitly selects the
camera for this process. Release builds always select the camera and omit the
DEBUG harness. Selection is not persisted across app launches.

`SimulatorFixture.mp4` is a small solid-color, 320x180, 15 fps, two-second
H.264 clip. It contains no face and is committed under the app's synchronized
`Resources` group so the DEBUG simulator default is deterministic. The
scenario input set and its generation rules are documented in
[`apps/ios/TestVideos/README.md`](../apps/ios/TestVideos/README.md).

## Metrics

`MediaSourceMetrics` reports `inputFPS`, decoded frame dimensions,
`deliveredFrames`, and `droppedFrames`. The DEBUG harness refreshes the values
every 250 ms. The same metrics are available from `FileSource.metrics` and
`CameraSource.metrics` for focused tests and diagnostics.

## Verification

`InnoLiveTests/MediaSourceIntegrationTests.swift` covers native pacing,
non-loop completion, loop timestamp monotonicity, stop/restart behavior,
container rotation, invalid input, slow-consumer drops, and preservation of a
leading presentation gap. Tests write real H.264 MP4 files with AVAssetWriter.

Verified on 2026-09-08 with Xcode 26.6 and iPhone 17 / iOS 26.5 Simulator:

| Check | Result |
| --- | --- |
| Existing tests plus MediaSource integration tests | 28 passed, 0 failed |
| Debug Simulator build | Passed, unsigned |
| Release generic iOS build | Passed, unsigned |
| Debug generic iOS build | Passed, signed |
| Bundled default video | 320×180, 15 FPS, 30 frames, 2 seconds; playback observed |
| Rotated MP4 | Verified 90-degree display matrix and portrait preview |
| Preview stop/restart | `중지됨` then `재생 중`; controls enabled appropriately |
| Return to app | Normal sign-in screen appeared |
| Scenario generator | Seven 640×640 / 24 FPS / 144-frame clips verified with ffprobe |
| Diff whitespace | Passed |

The final media test run is recorded in
`/tmp/innolive-ios-media/media-test-final.log`; final build logs are
`simulator-final.log`, `release-final-2.log`, and `device-final.log` in the same
directory. These are temporary local evidence, not archived CI artifacts.
The last callback-generation and permission-error integration changes were
compiled in the final builds and reviewed separately from the media tests.

To reproduce the tests:

```bash
xcodebuild \
  -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO test
```

Add `-INNOLIVE_DEBUG_MEDIA_PREVIEW` in the Xcode scheme's launch arguments to
open the offline preview. Add `-INNOLIVE_MEDIA_URL` followed by a local path
for a Simulator fixture, or select a file inside the harness. Actual uplink
still requires normal authentication and microphone permission; file audio is
not injected. Do not use preview success as evidence of server processing.

The following paths remain unverified in the current environment:

- physical camera and microphone capture;
- face registration from a physical camera frame;
- server signaling and processed remote video;
- TURN traversal and an end-to-end YouTube broadcast.

Bluetooth, cellular/Wi-Fi handoff, background/lock behavior, sustained memory
use, and thermal performance also require a physical-device session. Camera
switch/quality rollback was reviewed for cancellation and recovery behavior;
the tests cannot execute the physical camera driver. The existing local face
registration flow remains camera-specific. File input reaches the server's
detection/anonymization path through the normal uplink, not a new local model.

The available signed iOS device build completed, but no usable physical device
remained available for installation and runtime verification. Simulator builds
used for the focused test run were unsigned.

HTTP payloads, signaling fields, track IDs, VP8/Opus negotiation, and the
explicit YouTube prepare/go-live sequence are unchanged. Deprecated AVAsset
synchronous metadata APIs still emit warnings; reads run on the file decoder
queue. No new concurrency warnings remain in the final Simulator build.
