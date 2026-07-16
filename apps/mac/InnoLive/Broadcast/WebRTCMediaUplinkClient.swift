//
//  WebRTCMediaUplinkClient.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Combine
import Foundation
import AppKit
import AVFoundation
import WebKit

final class WebRTCMediaUplinkClient: NSObject, ObservableObject {
    @Published private(set) var state: WebRTCMediaUplinkState = .idle
    @Published private(set) var statusText = "WebRTC 업링크 대기"
    @Published private(set) var lastErrorMessage: String?
    @Published private(set) var hasRemoteVideo = false

    var onOffer: ((String) -> Void)?
    var onIceCandidate: ((MediaUplinkSignal) -> Void)?
    var onStateChanged: ((WebRTCMediaUplinkState, String) -> Void)?

    private let webView: WKWebView
    private let contentController: WKUserContentController
    private weak var previewContainerView: NSView?
    private var hostWindow: NSWindow?
    private var pendingBootstrapJavaScript: String?
    private var offerTimeoutTimer: Timer?
    private var preferredVideoDeviceName: String?

    override init() {
        let contentController = WKUserContentController()
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsAirPlayForMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = []

        self.contentController = contentController
        self.webView = ResponsePreviewWebView(frame: NSRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)

        super.init()

        contentController.add(self, name: "innoliveUplink")
        webView.uiDelegate = self
        webView.navigationDelegate = self
    }

    deinit {
        contentController.removeScriptMessageHandler(forName: "innoliveUplink")
    }

    func start(
        originURL: URL? = ServerEnvironment.current.httpBaseURL,
        iceServers: [String] = ["stun:stun.l.google.com:19302"],
        preferredVideoDeviceName: String? = nil
    ) {
        self.preferredVideoDeviceName = preferredVideoDeviceName
        lastErrorMessage = nil
        offerTimeoutTimer?.invalidate()
        updateState(.preparing, "WebRTC 카메라 권한 확인 중")

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startAfterCameraPermission(originURL: originURL, iceServers: iceServers)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] isGranted in
                DispatchQueue.main.async {
                    guard let self else {
                        return
                    }

                    if isGranted {
                        self.startAfterCameraPermission(originURL: originURL, iceServers: iceServers)
                    } else {
                        self.lastErrorMessage = "카메라 권한이 필요합니다."
                        self.updateState(.failed, "카메라 권한이 필요합니다.")
                    }
                }
            }
        case .denied, .restricted:
            lastErrorMessage = "카메라 권한이 차단되어 WebRTC offer를 만들 수 없습니다."
            updateState(.failed, "카메라 권한이 차단되어 WebRTC offer를 만들 수 없습니다.")
        @unknown default:
            startAfterCameraPermission(originURL: originURL, iceServers: iceServers)
        }
    }

    private func startAfterCameraPermission(
        originURL: URL?,
        iceServers: [String]
    ) {
        updateState(.preparing, "WebRTC 카메라 준비 중")
        ensureHostedWebView()
        startOfferTimeout()

        if let clientURL = clientURL(for: originURL) {
            pendingBootstrapJavaScript = bootstrapJavaScript(iceServers: iceServers)
            webView.load(URLRequest(url: clientURL))
        } else {
            webView.loadHTMLString(
                html(iceServers: iceServers),
                baseURL: originURL
            )
        }
    }

    func stop() {
        offerTimeoutTimer?.invalidate()
        offerTimeoutTimer = nil
        pendingBootstrapJavaScript = nil
        preferredVideoDeviceName = nil
        hasRemoteVideo = false
        webView.stopLoading()
        evaluate("window.__innoliveStop && window.__innoliveStop();")
        clearWebViewFirstResponder()
        hostWindow?.orderOut(nil)
        if previewContainerView == nil {
            webView.removeFromSuperview()
        }
        updateState(.idle, "WebRTC 업링크 대기")
    }

    func switchCamera(preferredVideoDeviceName: String?) {
        self.preferredVideoDeviceName = preferredVideoDeviceName

        guard state != .idle else {
            return
        }

        let deviceName = preferredVideoDeviceName.map(javascriptStringLiteral) ?? "null"
        updateState(.preparing, "WebRTC 송출 카메라 전환 중")
        evaluate("window.__innoliveSwitchCamera && window.__innoliveSwitchCamera(\(deviceName));")
    }

    func attachPreview(to containerView: NSView) {
        previewContainerView = containerView
        moveWebView(to: containerView, isPreview: true)
    }

    func detachPreview(from containerView: NSView) {
        guard previewContainerView === containerView else {
            return
        }

        previewContainerView = nil
        if state == .idle {
            webView.removeFromSuperview()
        } else {
            ensureHostedWebView()
        }
    }

    func acceptAnswer(_ sdp: String) {
        evaluate("window.__innoliveAcceptAnswer && window.__innoliveAcceptAnswer(\(javascriptStringLiteral(sdp)));")
        updateState(.connecting, "서버 SDP answer 적용 중")
    }

    func addRemoteCandidate(_ signal: MediaUplinkSignal) {
        guard let candidate = signal.candidate else {
            return
        }

        let sdpMid = signal.sdpMid.map(javascriptStringLiteral) ?? "null"
        let sdpMLineIndex = signal.sdpMLineIndex.map(String.init) ?? "null"
        evaluate(
            "window.__innoliveAddCandidate && window.__innoliveAddCandidate(\(javascriptStringLiteral(candidate)), \(sdpMid), \(sdpMLineIndex));"
        )
    }

    private func html(iceServers: [String]) -> String {
        return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
        </head>
        <body>
        <script>
        \(bootstrapJavaScript(iceServers: iceServers))
        </script>
        </body>
        </html>
        """
    }

    private func bootstrapJavaScript(iceServers: [String]) -> String {
        let encodedIceServers = (try? JSONEncoder().encode(iceServers))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        let preferredDeviceName = preferredVideoDeviceName.map(javascriptStringLiteral) ?? "null"

        return """
        document.body.innerHTML = '<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000;}video{background:#000;}#remotePreview{display:block;width:100vw;height:100vh;object-fit:contain;}#localPreview{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;}</style><video id="remotePreview" autoplay muted playsinline></video><video id="localPreview" autoplay muted playsinline></video>';
        const iceServerURLs = \(encodedIceServers);
        var preferredVideoDeviceName = \(preferredDeviceName);
        var pc = null;
        var localStream = null;
        var remoteStream = null;
        const mediaTimeoutMs = 8000;

        function post(payload) {
          window.webkit.messageHandlers.innoliveUplink.postMessage(payload);
        }

        function postStatus(message) {
          post({ type: "status", message });
        }

        function postError(error) {
          const name = error && error.name ? error.name : "Error";
          const message = error && error.message ? error.message : String(error);
          post({ type: "error", message: name + ": " + message });
        }

        function withTimeout(promise, timeoutMs, message) {
          let timeoutID = null;
          const timeout = new Promise((_, reject) => {
            timeoutID = setTimeout(() => reject(new Error(message)), timeoutMs);
          });

          return Promise.race([promise, timeout]).finally(() => {
            if (timeoutID !== null) {
              clearTimeout(timeoutID);
            }
          });
        }

        async function findVideoDeviceID(preferredName) {
          if (!preferredName || !navigator.mediaDevices.enumerateDevices) {
            return null;
          }

          const devices = await navigator.mediaDevices.enumerateDevices();
          const normalizedPreferredName = preferredName.trim().toLowerCase();
          const videoDevices = devices.filter((device) => device.kind === "videoinput");
          const exactMatch = videoDevices.find((device) => device.label.trim().toLowerCase() === normalizedPreferredName);
          const partialMatch = videoDevices.find((device) => device.label.toLowerCase().includes(normalizedPreferredName));
          const matchedDevice = exactMatch || partialMatch;
          return matchedDevice ? matchedDevice.deviceId : null;
        }

        function videoConstraints(deviceID) {
          const constraints = {
            width: { ideal: 1280 },
            height: { ideal: 720 },
            frameRate: { ideal: 30, max: 30 }
          };

          if (deviceID) {
            constraints.deviceId = { exact: deviceID };
          }

          return constraints;
        }

        async function openVideoStream(preferredName) {
          const deviceID = await findVideoDeviceID(preferredName);
          let stream = await withTimeout(
            navigator.mediaDevices.getUserMedia({
              video: videoConstraints(deviceID),
              audio: false
            }),
            mediaTimeoutMs,
            "getUserMedia timed out before camera access was granted"
          );

          if (!deviceID && preferredName) {
            const retryDeviceID = await findVideoDeviceID(preferredName);
            const currentTrack = stream.getVideoTracks()[0];
            const isCurrentPreferred = currentTrack && currentTrack.label.toLowerCase().includes(preferredName.trim().toLowerCase());
            if (retryDeviceID && !isCurrentPreferred) {
              stream.getTracks().forEach((track) => track.stop());
              stream = await withTimeout(
                navigator.mediaDevices.getUserMedia({
                  video: videoConstraints(retryDeviceID),
                  audio: false
                }),
                mediaTimeoutMs,
                "getUserMedia timed out before selected camera access was granted"
              );
            }
          }

          return stream;
        }

        async function replaceLocalStream(nextStream, shouldRenegotiate) {
          const nextTrack = nextStream.getVideoTracks()[0];
          if (!nextTrack) {
            throw new Error("Selected camera did not provide a video track");
          }

          if (localStream) {
            localStream.getTracks().forEach((track) => track.stop());
          }

          localStream = nextStream;
          document.getElementById("localPreview").srcObject = localStream;

          if (!pc) {
            return;
          }

          const sender = pc.getSenders().find((candidate) => candidate.track && candidate.track.kind === "video");
          if (sender) {
            await sender.replaceTrack(nextTrack);
          } else {
            pc.addTrack(nextTrack, localStream);
          }

          if (shouldRenegotiate) {
            postStatus("camera-switch-offer");
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            post({ type: "offer", sdp: offer.sdp });
          }
        }

        async function start() {
          try {
            postStatus("media-devices-check");
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
              throw new Error("getUserMedia is not available in this WebView origin");
            }

            postStatus("get-user-media");
            localStream = await openVideoStream(preferredVideoDeviceName);
            document.getElementById("localPreview").srcObject = localStream;

            postStatus("peer-connection");
            pc = new RTCPeerConnection({
              iceServers: iceServerURLs.map((url) => ({ urls: url }))
            });
            remoteStream = new MediaStream();
            document.getElementById("remotePreview").srcObject = remoteStream;

            pc.onicecandidate = (event) => {
              if (!event.candidate) { return; }
              post({
                type: "iceCandidate",
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex
              });
            };

            pc.onconnectionstatechange = () => {
              post({ type: "connectionState", connectionState: pc.connectionState });
            };

            pc.ontrack = (event) => {
              remoteStream.addTrack(event.track);
              post({ type: "remoteTrack", kind: event.track.kind });
            };

            localStream.getTracks().forEach((track) => pc.addTrack(track, localStream));

            postStatus("create-offer");
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            post({ type: "offer", sdp: offer.sdp });
          } catch (error) {
            postError(error);
          }
        }

        window.__innoliveAcceptAnswer = async function(sdp) {
          try {
            if (!pc) { throw new Error("PeerConnection is not ready"); }
            await pc.setRemoteDescription({ type: "answer", sdp });
            post({ type: "connectionState", connectionState: "answer-applied" });
          } catch (error) {
            postError(error);
          }
        };

        window.__innoliveAddCandidate = async function(candidate, sdpMid, sdpMLineIndex) {
          try {
            if (!pc) { throw new Error("PeerConnection is not ready"); }
            await pc.addIceCandidate({ candidate, sdpMid, sdpMLineIndex });
          } catch (error) {
            postError(error);
          }
        };

        window.__innoliveSwitchCamera = async function(nextPreferredVideoDeviceName) {
          try {
            preferredVideoDeviceName = nextPreferredVideoDeviceName;
            const nextStream = await openVideoStream(preferredVideoDeviceName);
            await replaceLocalStream(nextStream, true);
          } catch (error) {
            postError(error);
          }
        };

        window.__innoliveStop = function() {
          if (pc) {
            pc.close();
            pc = null;
          }
          if (localStream) {
            localStream.getTracks().forEach((track) => track.stop());
            localStream = null;
          }
          if (remoteStream) {
            remoteStream.getTracks().forEach((track) => track.stop());
          }
          post({ type: "connectionState", connectionState: "closed" });
        };

        void start();
        """
    }

    private func clientURL(for originURL: URL?) -> URL? {
        guard let originURL,
              originURL.scheme == "http" || originURL.scheme == "https",
              var components = URLComponents(url: originURL, resolvingAgainstBaseURL: false) else {
            return nil
        }

        components.path = "/client/"
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private func handleScriptPayload(_ payload: Any) {
        guard let dictionary = payload as? [String: Any],
              let type = dictionary["type"] as? String else {
            return
        }

        switch type {
        case "offer":
            guard let sdp = dictionary["sdp"] as? String else {
                return
            }
            offerTimeoutTimer?.invalidate()
            offerTimeoutTimer = nil
            updateState(.offering, "WebRTC offer 생성됨")
            onOffer?(sdp)
        case "iceCandidate":
            guard let candidate = dictionary["candidate"] as? String else {
                return
            }
            let signal = MediaUplinkSignal.iceCandidate(
                candidate: candidate,
                sdpMid: dictionary["sdpMid"] as? String,
                sdpMLineIndex: dictionary["sdpMLineIndex"] as? Int
            )
            onIceCandidate?(signal)
        case "connectionState":
            let connectionState = dictionary["connectionState"] as? String ?? "unknown"
            handleConnectionState(connectionState)
        case "remoteTrack":
            hasRemoteVideo = true
            updateState(.connected, "서버 처리 영상 수신 중")
        case "status":
            let message = dictionary["message"] as? String ?? "WebRTC 준비 중"
            updateState(state, "WebRTC \(message)")
        case "error":
            let message = dictionary["message"] as? String ?? "WebRTC 업링크 오류"
            offerTimeoutTimer?.invalidate()
            offerTimeoutTimer = nil
            lastErrorMessage = message
            updateState(.failed, message)
        default:
            break
        }
    }

    private func handleConnectionState(_ connectionState: String) {
        switch connectionState {
        case "connected", "completed":
            updateState(.connected, "WebRTC 미디어 송신 중")
        case "connecting", "answer-applied":
            updateState(.connecting, "WebRTC 연결 중")
        case "failed", "disconnected":
            updateState(.failed, "WebRTC 연결이 끊겼습니다.")
        case "closed":
            updateState(.idle, "WebRTC 업링크 대기")
        default:
            updateState(state, "WebRTC 상태: \(connectionState)")
        }
    }

    private func updateState(_ state: WebRTCMediaUplinkState, _ statusText: String) {
        self.state = state
        self.statusText = statusText
        onStateChanged?(state, statusText)
    }

    private func evaluate(_ javaScript: String) {
        webView.evaluateJavaScript(javaScript)
    }

    private func startOfferTimeout() {
        offerTimeoutTimer?.invalidate()
        offerTimeoutTimer = Timer.scheduledTimer(withTimeInterval: 12, repeats: false) { [weak self] _ in
            guard let self, self.state == .preparing else {
                return
            }

            let message = "WebRTC offer 생성 시간이 초과되었습니다. 카메라 권한 또는 WebView media capture를 확인해 주세요."
            self.lastErrorMessage = message
            self.updateState(.failed, message)
        }
    }

    private func ensureHostedWebView() {
        if let previewContainerView {
            moveWebView(to: previewContainerView, isPreview: true)
            hostWindow?.orderOut(nil)
            return
        }

        let window = ensureHiddenHostWindow()
        guard let contentView = window.contentView else {
            return
        }

        moveWebView(to: contentView, isPreview: false)
        window.orderFront(nil)
    }

    private func ensureHiddenHostWindow() -> NSWindow {
        if let hostWindow {
            if hostWindow.contentView == nil || hostWindow.contentView === webView {
                hostWindow.contentView = NSView(frame: NSRect(x: 0, y: 0, width: 16, height: 16))
            }
            return hostWindow
        }

        let visibleFrame = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 320, height: 240)
        let window = NSWindow(
            contentRect: NSRect(x: visibleFrame.minX + 8, y: visibleFrame.minY + 8, width: 16, height: 16),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.ignoresMouseEvents = true
        window.hasShadow = false
        window.backgroundColor = .clear
        window.alphaValue = 0.1
        window.contentView = NSView(frame: NSRect(x: 0, y: 0, width: 16, height: 16))
        hostWindow = window
        return window
    }

    private func moveWebView(to containerView: NSView, isPreview: Bool) {
        if webView.superview !== containerView {
            clearWebViewFirstResponder()
            if hostWindow?.contentView === webView {
                hostWindow?.contentView = NSView(frame: NSRect(x: 0, y: 0, width: 16, height: 16))
            }
            webView.removeFromSuperview()
            containerView.addSubview(webView)
        }

        webView.translatesAutoresizingMaskIntoConstraints = true
        webView.autoresizingMask = [.width, .height]
        if isPreview {
            webView.frame = containerView.bounds
            webView.alphaValue = 1
        } else {
            webView.frame = NSRect(x: 0, y: 0, width: 16, height: 16)
            webView.alphaValue = 0.1
        }
    }

    private func clearWebViewFirstResponder() {
        guard let window = webView.window,
              window.firstResponder === webView else {
            return
        }

        window.makeFirstResponder(nil)
    }

    private func javascriptStringLiteral(_ value: String) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let encoded = String(data: data, encoding: .utf8) else {
            return "\"\""
        }

        return encoded
    }
}

private final class ResponsePreviewWebView: WKWebView {
    override var acceptsFirstResponder: Bool {
        false
    }

    override func becomeFirstResponder() -> Bool {
        false
    }
}

extension WebRTCMediaUplinkClient: WKScriptMessageHandler {
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        handleScriptPayload(message.body)
    }
}

extension WebRTCMediaUplinkClient: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation?) {
        guard let javaScript = pendingBootstrapJavaScript else {
            return
        }

        pendingBootstrapJavaScript = nil
        updateState(.preparing, "WebRTC 서버 origin 준비됨")
        webView.evaluateJavaScript(javaScript) { [weak self] _, error in
            if let error {
                self?.lastErrorMessage = error.localizedDescription
                self?.updateState(.failed, error.localizedDescription)
            }
        }
    }

    func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation?,
        withError error: Error
    ) {
        lastErrorMessage = error.localizedDescription
        updateState(.failed, error.localizedDescription)
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation?,
        withError error: Error
    ) {
        lastErrorMessage = error.localizedDescription
        updateState(.failed, error.localizedDescription)
    }
}

extension WebRTCMediaUplinkClient: WKUIDelegate {
    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        updateState(.preparing, "WebRTC capture 권한 승인됨")
        decisionHandler(.grant)
    }
}
