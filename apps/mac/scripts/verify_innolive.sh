#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WORK_DIR="$(mktemp -d /tmp/innolive-verify.XXXXXX)"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

section() {
  printf '\n== %s ==\n' "$1"
}

random_port() {
  echo $((18000 + RANDOM % 2000))
}

start_server() {
  local scenario="$1"
  local port="$2"
  SERVER_PID=""
  python3 "$WORK_DIR/ws_server.py" "$scenario" "$port" >"$WORK_DIR/server.log" 2>&1 &
  SERVER_PID=$!
  sleep 0.35
}

stop_server() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
  fi
}

section "Build"
xcodebuild -project InnoLive.xcodeproj -scheme InnoLive -configuration Debug build >/tmp/innolive-build.log
if rg -n "error:" /tmp/innolive-build.log; then
  exit 1
fi
rg -n "warning:" /tmp/innolive-build.log || true

BUILT_PRODUCTS_DIR="$(
  xcodebuild -project InnoLive.xcodeproj -scheme InnoLive -configuration Debug -showBuildSettings 2>/dev/null |
  awk -F'= ' '/BUILT_PRODUCTS_DIR = / { print $2; exit }'
)"
APP_PATH="$BUILT_PRODUCTS_DIR/InnoLive.app"
APP_EXEC="$APP_PATH/Contents/MacOS/InnoLive"
APP_PLIST="$APP_PATH/Contents/Info.plist"

section "Bundle permissions"
plutil -extract NSCameraUsageDescription raw "$APP_PLIST" >/dev/null
plutil -extract NSMicrophoneUsageDescription raw "$APP_PLIST" >/dev/null
plutil -extract CFBundleIdentifier raw "$APP_PLIST"

cat >"$WORK_DIR/ws_server.py" <<'PY'
import base64
import hashlib
import json
import socket
import struct
import sys
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
scenario = sys.argv[1]
port = int(sys.argv[2])

def recv_http(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(1024)
        if not chunk:
            break
        data += chunk
    return data.decode("utf-8", errors="replace")

def header_value(request, name):
    prefix = name.lower() + ":"
    for line in request.split("\r\n"):
        if line.lower().startswith(prefix):
            return line.split(":", 1)[1].strip()
    return ""

def handshake(conn):
    request = recv_http(conn)
    key = header_value(request, "Sec-WebSocket-Key")
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    conn.sendall((
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    ).encode())

def read_frame(conn):
    first = conn.recv(2)
    if len(first) < 2:
        return ""
    b1, b2 = first
    opcode = b1 & 0x0F
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack("!H", conn.recv(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", conn.recv(8))[0]
    mask = conn.recv(4) if (b2 & 0x80) else b""
    payload = b""
    while len(payload) < length:
        chunk = conn.recv(length - len(payload))
        if not chunk:
            break
        payload += chunk
    if mask:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    if opcode == 8:
        return "<close>"
    return payload.decode("utf-8", errors="replace")

def send_text(conn, text):
    payload = text.encode("utf-8")
    header = bytearray([0x81])
    if len(payload) < 126:
        header.append(len(payload))
    elif len(payload) < 65536:
        header.append(126)
        header.extend(struct.pack("!H", len(payload)))
    else:
        header.append(127)
        header.extend(struct.pack("!Q", len(payload)))
    conn.sendall(bytes(header) + payload)

def live_message(url, bitrate=4500, text="server-live"):
    return json.dumps({
        "type": "metrics",
        "status": "live",
        "processedVideoURL": url,
        "bitrateKbps": bitrate,
        "latencyMilliseconds": 90,
        "framesPerSecond": 30,
        "droppedFrames": 0,
        "viewerCount": 3,
        "message": text,
    })

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(4)

    if scenario == "connection_ok":
        conn, _ = server.accept()
        with conn:
            handshake(conn)
            print(read_frame(conn), flush=True)
            send_text(conn, live_message("https://example.com/test.m3u8", text="pong-ok"))
            time.sleep(0.5)

    elif scenario == "broadcast_flow":
        conn, _ = server.accept()
        with conn:
            handshake(conn)
            print("FIRST " + read_frame(conn), flush=True)
            send_text(conn, live_message("https://example.com/live/output.m3u8", bitrate=4700))
            print("SECOND " + read_frame(conn), flush=True)
            send_text(conn, json.dumps({"type": "status", "status": "stopped", "message": "server-stopped"}))
            time.sleep(0.5)

    elif scenario == "reconnect":
        first, _ = server.accept()
        with first:
            handshake(first)
            print("FIRST " + read_frame(first), flush=True)
        second, _ = server.accept()
        with second:
            handshake(second)
            print("SECOND " + read_frame(second), flush=True)
            send_text(second, live_message("https://example.com/reconnected.m3u8", bitrate=5100, text="reconnected-live"))
            time.sleep(6.0)
PY

section "Settings persistence"
cat >"$WORK_DIR/TestSettings.swift" <<'SWIFT'
import Combine
import Foundation

final class CameraManager {
    var isRunning = false
    var selectedCameraName: String?
    func start() { isRunning = true }
}

@main
struct Runner {
    static func main() async {
        let suite = "InnoLive.Verify.Settings.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        await MainActor.run {
            let first = BroadcastManager(userDefaults: defaults)
            first.mode = .serverBridge
            first.streamTitle = "Persisted Broadcast"
            first.signalingURLString = "wss://server.example/live"
            first.processedVideoURLString = "https://server.example/output.m3u8"
            first.automaticallyReconnect = false
            first.enableWebRTCMediaUplink = true
            first.destinations[1].isEnabled = true
            first.destinations[1].streamKey = "k2"

            let second = BroadcastManager(userDefaults: defaults)
            precondition(second.mode == .serverBridge)
            precondition(second.streamTitle == "Persisted Broadcast")
            precondition(second.signalingURLString == "wss://server.example/live")
            precondition(second.processedVideoURLString == "https://server.example/output.m3u8")
            precondition(second.automaticallyReconnect == false)
            precondition(second.enableWebRTCMediaUplink == true)
            precondition(second.destinations[1].isEnabled)
            precondition(second.destinations[1].streamKey == "k2")
        }

        print("settings-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Broadcast/WebRTCSignalingClient.swift \
  InnoLive/Broadcast/WebRTCMediaUplinkClient.swift \
  InnoLive/Config/ServerEnvironment.swift \
  InnoLive/Broadcast/BroadcastManager.swift \
  "$WORK_DIR/TestSettings.swift" \
  -framework WebKit \
  -o "$WORK_DIR/test_settings"
"$WORK_DIR/test_settings"

section "WebRTC signaling payloads"
cat >"$WORK_DIR/TestWebRTCSignalPayload.swift" <<'SWIFT'
import Foundation

@main
struct Runner {
    static func main() throws {
        let message = BroadcastControlMessage(
            type: "mediaUplink",
            sessionID: "session-1",
            title: "Test",
            broadcasterID: "host",
            mosaicPolicy: "mosaic_all_faces_except_broadcaster",
            destinations: [],
            timestamp: Date(timeIntervalSince1970: 0),
            mediaUplink: .offer("v=0\r\n")
        )

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(message)
        let json = String(data: data, encoding: .utf8)!
        precondition(json.contains("\"mediaUplink\""))
        precondition(json.contains("\"type\":\"offer\""))
        precondition(json.contains("v=0"))

        let serverJSON = """
        {
          "type": "status",
          "status": "live",
          "processedVideoURL": "https://example.com/out.m3u8",
          "bitrateKbps": 4500,
          "latencyMilliseconds": 90,
          "framesPerSecond": 30,
          "droppedFrames": 0,
          "viewerCount": 2,
          "message": "ok",
          "mediaUplink": { "type": "answer", "sdp": "v=0\\r\\n" }
        }
        """
        let decoded = try JSONDecoder().decode(BroadcastServerMessage.self, from: Data(serverJSON.utf8))
        precondition(decoded.mediaUplink?.type == "answer")
        precondition(decoded.mediaUplink?.sdp == "v=0\r\n")

        let nativeOffer = ServerSignalingMessage.offer(
            sessionID: "session-1",
            sdp: "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        )
        let nativeOfferJSON = String(data: try encoder.encode(nativeOffer), encoding: .utf8)!
        precondition(nativeOfferJSON.contains("\"type\":\"offer\""))
        precondition(nativeOfferJSON.contains("\"session_id\":\"session-1\""))

        let nativeCandidate = ServerSignalingMessage.iceCandidate(
            sessionID: "session-1",
            signal: .iceCandidate(candidate: "candidate:1 1 udp 1 127.0.0.1 9 typ host", sdpMid: "0", sdpMLineIndex: 0)
        )
        let nativeCandidateJSON = String(data: try encoder.encode(nativeCandidate), encoding: .utf8)!
        precondition(nativeCandidateJSON.contains("\"type\":\"ice_candidate\""))
        precondition(nativeCandidateJSON.contains("\"sdpMid\":\"0\""))
        precondition(nativeCandidateJSON.contains("\"sdpMLineIndex\":0"))

        let errorJSON = """
        {
          "type": "error",
          "error": {
            "code": "bad_request",
            "message": "Unsupported signaling message type."
          }
        }
        """
        let errorMessage = try JSONDecoder().decode(BroadcastServerMessage.self, from: Data(errorJSON.utf8))
        precondition(errorMessage.type == "error")
        precondition(errorMessage.error?.message == "Unsupported signaling message type.")
        print("webrtc-payload-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  "$WORK_DIR/TestWebRTCSignalPayload.swift" \
  -o "$WORK_DIR/test_webrtc_signal_payload"
"$WORK_DIR/test_webrtc_signal_payload"

section "Connection test isolation"
PORT="$(random_port)"
start_server connection_ok "$PORT"
cat >"$WORK_DIR/TestConnection.swift" <<SWIFT
import Combine
import Foundation

final class CameraManager {
    var isRunning = false
    var selectedCameraName: String?
    func start() { isRunning = true }
}

@main
struct Runner {
    static func main() async throws {
        let suite = "InnoLive.Verify.Connection.\\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let manager = await MainActor.run { BroadcastManager(userDefaults: defaults) }
        await MainActor.run {
            manager.mode = .serverBridge
            manager.signalingURLString = "ws://127.0.0.1:$PORT"
            manager.processedVideoURLString = ""
            manager.testServerConnection()
        }
        try await Task.sleep(nanoseconds: 1_500_000_000)
        await MainActor.run {
            precondition(manager.state == .idle)
            precondition(manager.statusText == "pong-ok")
            precondition(manager.lastErrorMessage == nil)
            precondition(manager.metrics.bitrateKbps == 0)
            precondition(manager.processedVideoURLString.isEmpty)
        }
        print("connection-test-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Broadcast/WebRTCSignalingClient.swift \
  InnoLive/Broadcast/WebRTCMediaUplinkClient.swift \
  InnoLive/Config/ServerEnvironment.swift \
  InnoLive/Broadcast/BroadcastManager.swift \
  "$WORK_DIR/TestConnection.swift" \
  -framework WebKit \
  -o "$WORK_DIR/test_connection"
"$WORK_DIR/test_connection"
stop_server

section "Broadcast start and stop"
PORT="$(random_port)"
start_server broadcast_flow "$PORT"
cat >"$WORK_DIR/TestBroadcast.swift" <<SWIFT
import Combine
import Foundation

final class CameraManager {
    var isRunning = false
    var selectedCameraName: String?
    func start() { isRunning = true }
}

@main
struct Runner {
    static func main() async throws {
        let suite = "InnoLive.Verify.Broadcast.\\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let manager = await MainActor.run { BroadcastManager(userDefaults: defaults) }
        let camera = CameraManager()
        await MainActor.run {
            manager.mode = .serverBridge
            manager.automaticallyReconnect = false
            manager.signalingURLString = "ws://127.0.0.1:$PORT"
            manager.destinations[0].ingestURL = "rtmp://example/live"
            manager.destinations[0].streamKey = "secret"
            manager.start(cameraManager: camera)
        }
        try await Task.sleep(nanoseconds: 1_200_000_000)
        await MainActor.run {
            precondition(camera.isRunning)
            precondition(manager.state == .live)
            precondition(manager.metrics.bitrateKbps == 4700)
            precondition(manager.processedVideoURLString == "https://example.com/live/output.m3u8")
        }
        await MainActor.run { manager.stop() }
        try await Task.sleep(nanoseconds: 900_000_000)
        await MainActor.run {
            precondition(manager.state == .idle)
            precondition(manager.metrics.bitrateKbps == 0)
        }
        print("broadcast-flow-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Broadcast/WebRTCSignalingClient.swift \
  InnoLive/Broadcast/WebRTCMediaUplinkClient.swift \
  InnoLive/Config/ServerEnvironment.swift \
  InnoLive/Broadcast/BroadcastManager.swift \
  "$WORK_DIR/TestBroadcast.swift" \
  -framework WebKit \
  -o "$WORK_DIR/test_broadcast"
"$WORK_DIR/test_broadcast"
stop_server

section "Automatic reconnect"
PORT="$(random_port)"
start_server reconnect "$PORT"
cat >"$WORK_DIR/TestReconnect.swift" <<SWIFT
import Combine
import Foundation

final class CameraManager {
    var isRunning = false
    var selectedCameraName: String?
    func start() { isRunning = true }
}

@main
struct Runner {
    static func main() async throws {
        let suite = "InnoLive.Verify.Reconnect.\\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let manager = await MainActor.run { BroadcastManager(userDefaults: defaults) }
        let camera = CameraManager()
        await MainActor.run {
            manager.mode = .serverBridge
            manager.automaticallyReconnect = true
            manager.signalingURLString = "ws://127.0.0.1:$PORT"
            manager.start(cameraManager: camera)
        }
        try await Task.sleep(nanoseconds: 4_500_000_000)
        await MainActor.run {
            precondition(manager.state == .live)
            precondition(manager.statusText == "reconnected-live")
            precondition(manager.lastErrorMessage == nil)
            precondition(manager.metrics.bitrateKbps == 5100)
            precondition(manager.processedVideoURLString == "https://example.com/reconnected.m3u8")
        }
        await MainActor.run { manager.stop() }
        print("reconnect-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Broadcast/WebRTCSignalingClient.swift \
  InnoLive/Broadcast/WebRTCMediaUplinkClient.swift \
  InnoLive/Config/ServerEnvironment.swift \
  InnoLive/Broadcast/BroadcastManager.swift \
  "$WORK_DIR/TestReconnect.swift" \
  -framework WebKit \
  -o "$WORK_DIR/test_reconnect"
"$WORK_DIR/test_reconnect"
stop_server

section "Recording status model"
cat >"$WORK_DIR/TestRecordingStatus.swift" <<'SWIFT'
import Combine
import Foundation

@main
struct Runner {
    static func main() async {
        await MainActor.run {
            let studio = StudioViewModel()
            studio.syncRecordingState(true, lastRecordingURL: nil)
            precondition(studio.statusMessage == "녹화를 시작했습니다.")
            studio.syncRecordingState(false, lastRecordingURL: nil)
            precondition(studio.statusMessage == "녹화를 중지했습니다.")
            studio.syncRecordingState(false, lastRecordingURL: URL(fileURLWithPath: "/tmp/InnoLive-test.mov"))
            precondition(studio.statusMessage == "녹화를 저장했습니다: InnoLive-test.mov")
        }
        print("recording-status-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Models/StudioModels.swift \
  InnoLive/ViewModels/StudioViewModel.swift \
  "$WORK_DIR/TestRecordingStatus.swift" \
  -o "$WORK_DIR/test_recording_status"
"$WORK_DIR/test_recording_status"

section "Audio meter model"
cat >"$WORK_DIR/TestAudioMeterModel.swift" <<'SWIFT'
import Combine
import Foundation

@main
struct Runner {
    static func main() async {
        await MainActor.run {
            let studio = StudioViewModel()

            let firstTimestamp = Date(timeIntervalSince1970: 1_000)
            studio.updateAudioMeter(
                AudioMeterSample(level: 0.5, peakLevel: 0.8),
                timestamp: firstTimestamp
            )
            guard let microphone = studio.audioChannels.first(where: { $0.kind == .microphone }) else {
                preconditionFailure("microphone channel should exist")
            }

            precondition(abs(microphone.level - 0.41) < 0.0001)
            precondition(abs(microphone.peakLevel - 0.656) < 0.0001)

            studio.updateAudioMeter(
                .zero,
                timestamp: firstTimestamp.addingTimeInterval(0.01)
            )
            let decayedPeak = studio.audioChannels.first { $0.kind == .microphone }?.peakLevel ?? 0
            precondition(decayedPeak < microphone.peakLevel)
            precondition(decayedPeak > 0)

            studio.setAudioEnabled(false, for: .microphone)
            let muted = studio.audioChannels.first { $0.kind == .microphone }
            precondition(muted?.level == 0)
            precondition(muted?.peakLevel == 0)
        }

        print("audio-meter-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Models/StudioModels.swift \
  InnoLive/ViewModels/StudioViewModel.swift \
  "$WORK_DIR/TestAudioMeterModel.swift" \
  -o "$WORK_DIR/test_audio_meter_model"
"$WORK_DIR/test_audio_meter_model"

section "Studio controls model"
cat >"$WORK_DIR/TestStudioControls.swift" <<'SWIFT'
import Combine
import Foundation

@main
struct Runner {
    static func main() async {
        await MainActor.run {
            let studio = StudioViewModel()

            precondition(studio.scenes.count == 1)
            studio.addScene()
            precondition(studio.scenes.count == 2)
            let addedSceneID = studio.selectedSceneID
            studio.duplicateSelectedScene()
            precondition(studio.scenes.count == 3)
            precondition(studio.selectedSceneID != addedSceneID)
            studio.removeSelectedScene()
            precondition(studio.scenes.count == 2)

            let initialSourceCount = studio.sources.count
            studio.addMediaSource()
            precondition(studio.sources.count == initialSourceCount + 1)
            guard let mediaSourceID = studio.selectedSourceID else {
                preconditionFailure("media source should be selected")
            }
            studio.setSourceVisible(mediaSourceID, isVisible: false)
            precondition(studio.sources.first { $0.id == mediaSourceID }?.isVisible == false)
            studio.toggleSelectedSourceLock()
            precondition(studio.sources.first { $0.id == mediaSourceID }?.isLocked == true)
            studio.removeSelectedSource()
            precondition(studio.sources.count == initialSourceCount + 1)
            studio.toggleSelectedSourceLock()
            studio.removeSelectedSource()
            precondition(studio.sources.count == initialSourceCount)

            studio.addSource(kind: .screen)
            guard let screenSourceID = studio.selectedSourceID else {
                preconditionFailure("screen source should be selected")
            }
            studio.updateSelectedScreenDisplayID(1234)
            studio.updateSelectedScreenResolution(.max1280)
            let screenSource = studio.sources.first { $0.id == screenSourceID }
            precondition(screenSource?.screenCapture.displayID == 1234)
            precondition(screenSource?.screenCapture.resolution == .max1280)
            studio.removeSelectedSource()
            precondition(studio.sources.count == initialSourceCount)

            studio.setAudioEnabled(false, for: .microphone)
            precondition(studio.audioChannels.first { $0.kind == .microphone }?.isEnabled == false)
            studio.setAudioVolume(2, for: .microphone)
            precondition(studio.audioChannels.first { $0.kind == .microphone }?.volume == 1)
            studio.setAudioVolume(-1, for: .microphone)
            precondition(studio.audioChannels.first { $0.kind == .microphone }?.volume == 0)

            studio.openDestinations()
            precondition(studio.activeSheet == .destinations)
            studio.openServerDiagnostics()
            precondition(studio.activeSheet == .serverDiagnostics)
            studio.openCameraProperties()
            precondition(studio.activeSheet == .cameraProperties)
            studio.openFilters()
            precondition(studio.activeSheet == .filters)
            studio.openResponseSettings()
            precondition(studio.activeSheet == .responseSettings)
            studio.openAppSettings()
            precondition(studio.activeSheet == .appSettings)
        }
        print("studio-controls-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Models/StudioModels.swift \
  InnoLive/ViewModels/StudioViewModel.swift \
  "$WORK_DIR/TestStudioControls.swift" \
  -o "$WORK_DIR/test_studio_controls"
"$WORK_DIR/test_studio_controls"

section "Expanded preview window state"
cat >"$WORK_DIR/TestExpandedPreviewState.swift" <<'SWIFT'
import Combine
import Foundation

@main
struct Runner {
    static func main() async {
        await MainActor.run {
            precondition(ExpandedPreviewKind.beforeFilter.windowID == "expandedPreview-beforeFilter")
            precondition(ExpandedPreviewKind.afterFilter.windowID == "expandedPreview-afterFilter")

            let studio = StudioViewModel()
            precondition(studio.isBeforeFilterWindowOpen == false)
            precondition(studio.isAfterFilterWindowOpen == false)

            studio.isBeforeFilterWindowOpen = true
            studio.isAfterFilterWindowOpen = true
            precondition(studio.isBeforeFilterWindowOpen && studio.isAfterFilterWindowOpen)

            studio.isBeforeFilterWindowOpen = false
            studio.isAfterFilterWindowOpen = false
            precondition(!studio.isBeforeFilterWindowOpen && !studio.isAfterFilterWindowOpen)
        }
        print("expanded-preview-state-ok")
    }
}
SWIFT
swiftc -parse-as-library \
  InnoLive/Broadcast/BroadcastModels.swift \
  InnoLive/Models/StudioModels.swift \
  InnoLive/ViewModels/StudioViewModel.swift \
  "$WORK_DIR/TestExpandedPreviewState.swift" \
  -o "$WORK_DIR/test_expanded_preview_state"
"$WORK_DIR/test_expanded_preview_state"

section "App launch smoke"
"$APP_EXEC" >"$WORK_DIR/app.log" 2>&1 &
APP_PID=$!
sleep 5
if ps -p "$APP_PID" >/dev/null; then
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  echo "app-launch-ok"
else
  echo "app exited early"
  cat "$WORK_DIR/app.log"
  exit 1
fi

section "Done"
echo "verify-ok"
