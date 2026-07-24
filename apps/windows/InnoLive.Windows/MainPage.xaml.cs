using System.Text.Json;
using InnoLive_Windows.Models;
using InnoLive_Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.Web.WebView2.Core;

namespace InnoLive_Windows;

public sealed partial class MainPage : Page
{
    private readonly TaskCompletionSource _webViewReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private bool _webViewInitialized;

    public StudioViewModel ViewModel { get; } = new();

    public MainPage()
    {
        InitializeComponent();
        Loaded += MainPage_Loaded;
        Unloaded += MainPage_Unloaded;
    }

    private async void MainPage_Loaded(object sender, RoutedEventArgs e)
    {
        if (_webViewInitialized) return;
        _webViewInitialized = true;

        try
        {
            await WebRtcView.EnsureCoreWebView2Async();
            WebRtcView.CoreWebView2.PermissionRequested += CoreWebView2_PermissionRequested;
            WebRtcView.CoreWebView2.WebMessageReceived += CoreWebView2_WebMessageReceived;
            WebRtcView.NavigationCompleted += WebRtcView_NavigationCompleted;
            WebRtcView.Source = ServerEnvironment.HttpBaseUri;
        }
        catch (Exception exception)
        {
            _webViewReady.TrySetException(exception);
            ViewModel.FailBroadcast($"WebView2 초기화 실패: {exception.Message}");
        }
    }

    private async void MainPage_Unloaded(object sender, RoutedEventArgs e)
    {
        await StopWebRtcAsync();
    }

    private void CoreWebView2_PermissionRequested(object? sender, CoreWebView2PermissionRequestedEventArgs args)
    {
        if (args.PermissionKind is CoreWebView2PermissionKind.Camera or CoreWebView2PermissionKind.Microphone)
            args.State = CoreWebView2PermissionState.Allow;
    }

    private async void WebRtcView_NavigationCompleted(WebView2 sender, CoreWebView2NavigationCompletedEventArgs args)
    {
        if (args.IsSuccess)
        {
            try
            {
                await WebRtcView.CoreWebView2.ExecuteScriptAsync(IdlePreviewScript);
                _webViewReady.TrySetResult();
                ViewModel.ReportWebRtcStatus($"WebRTC 준비됨: {ServerEnvironment.SignalingUri}");
            }
            catch (Exception exception)
            {
                _webViewReady.TrySetException(exception);
                ViewModel.FailBroadcast($"미리보기 초기화 실패: {exception.Message}");
            }
        }
        else
        {
            var error = new InvalidOperationException($"서버 origin 로드 실패: {args.WebErrorStatus}");
            _webViewReady.TrySetException(error);
            ViewModel.FailBroadcast(error.Message);
        }
    }

    private void CoreWebView2_WebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        try
        {
            using var document = JsonDocument.Parse(args.TryGetWebMessageAsString());
            var root = document.RootElement;
            var type = root.TryGetProperty("type", out var typeElement) ? typeElement.GetString() : null;
            var message = root.TryGetProperty("message", out var messageElement) ? messageElement.GetString() : null;

            switch (type)
            {
                case "remoteTrack":
                    ViewModel.ReportWebRtcStatus("서버 처리 영상 track을 수신했습니다. ICE 연결을 확인하는 중입니다.");
                    break;
                case "mediaConnected":
                    ViewModel.ReportWebRtcStatus("서버 처리 영상 송수신이 연결되었습니다.");
                    ViewModel.MarkBroadcastLive();
                    break;
                case "connectionState":
                    ViewModel.ReportWebRtcStatus($"WebRTC 연결 상태: {message}");
                    if (message is "failed" or "closed")
                        ViewModel.FailBroadcast($"WebRTC 연결 실패: {message}");
                    break;
                case "error":
                    ViewModel.FailBroadcast($"WebRTC 오류: {message}");
                    break;
                default:
                    if (!string.IsNullOrWhiteSpace(message)) ViewModel.ReportWebRtcStatus(message);
                    break;
            }
        }
        catch (Exception exception)
        {
            ViewModel.FailBroadcast($"WebRTC 상태 해석 실패: {exception.Message}");
        }
    }

    private async Task StartWebRtcAsync()
    {
        try
        {
            await _webViewReady.Task.WaitAsync(TimeSpan.FromSeconds(15));
            ViewModel.BeginBroadcastConnection();
            await WebRtcView.CoreWebView2.ExecuteScriptAsync(WebRtcBootstrapScript);
        }
        catch (Exception exception)
        {
            ViewModel.FailBroadcast($"WebRTC 시작 실패: {exception.Message}");
        }
    }

    private async Task StopWebRtcAsync()
    {
        if (WebRtcView.CoreWebView2 is not null)
        {
            try
            {
                await WebRtcView.CoreWebView2.ExecuteScriptAsync("window.__innoliveStop && window.__innoliveStop();");
                await WebRtcView.CoreWebView2.ExecuteScriptAsync(IdlePreviewScript);
            }
            catch { }
        }
        ViewModel.MarkBroadcastStopped();
    }

    private async void Broadcast_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.IsBroadcastActive) await StopWebRtcAsync();
        else await StartWebRtcAsync();
    }

    private async void ServerTest_Click(object sender, RoutedEventArgs e) => await ViewModel.VerifyServerConnectionAsync();
    private void Record_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleRecording();
    private void AddScene_Click(object sender, RoutedEventArgs e) => ViewModel.AddScene();
    private void DuplicateScene_Click(object sender, RoutedEventArgs e) => ViewModel.DuplicateSelectedScene();
    private void RemoveScene_Click(object sender, RoutedEventArgs e) => ViewModel.RemoveSelectedScene();
    private void Scene_Click(object sender, RoutedEventArgs e) { if (sender is ToggleButton { Tag: StudioSceneItem scene }) ViewModel.SelectScene(scene); }
    private void SourceType_Click(object sender, RoutedEventArgs e) { if (sender is MenuFlyoutItem { Tag: string kind } && Enum.TryParse<SourceKind>(kind, out var sourceKind)) ViewModel.AddSource(sourceKind); }
    private void SourceSelectionChanged(object sender, SelectionChangedEventArgs e) => ViewModel.SelectSource((sender as ListView)?.SelectedItem as SourceItem);
    private void SourceVisibility_Click(object sender, RoutedEventArgs e) { if (sender is Button { Tag: SourceItem source }) ViewModel.ToggleSourceVisibility(source); }
    private void SourceLock_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleSelectedSourceLock();
    private void SourceForward_Click(object sender, RoutedEventArgs e) => ViewModel.MoveSelectedSource(1);
    private void SourceBackward_Click(object sender, RoutedEventArgs e) => ViewModel.MoveSelectedSource(-1);
    private void SourceRemove_Click(object sender, RoutedEventArgs e) => ViewModel.RemoveSelectedSource();
    private void SourceText_TextChanged(object sender, TextChangedEventArgs e) => ViewModel.UpdateSelectedSourceText((sender as TextBox)?.Text ?? string.Empty);
    private void SourceColor_SelectionChanged(object sender, SelectionChangedEventArgs e) { if ((sender as ComboBox)?.SelectedItem is ComboBoxItem { Tag: string color }) ViewModel.UpdateSelectedSourceColor(color); }
    private void AudioEnabled_Toggled(object sender, RoutedEventArgs e) { if (sender is ToggleSwitch { Tag: AudioChannelItem channel } toggle) ViewModel.SetAudioEnabled(channel, toggle.IsOn); }

    private const string IdlePreviewScript = """
    (() => {
      document.documentElement.style.cssText = 'width:100%;height:100%;background:#000';
      document.body.style.cssText = 'margin:0;width:100%;height:100%;overflow:hidden;background:#000;font-family:Segoe UI,sans-serif';
      document.body.innerHTML = '<div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;width:100%;height:100%;box-sizing:border-box"><section style="position:relative;overflow:hidden;border-radius:4px;background:#0f1720"><span style="position:absolute;z-index:1;left:12px;top:10px;padding:4px 8px;border-radius:4px;background:#99000000;color:white;font-size:12px">내 화면</span></section><section style="position:relative;overflow:hidden;border-radius:4px;background:#000"><span style="position:absolute;z-index:1;left:12px;top:10px;padding:4px 8px;border-radius:4px;background:#99000000;color:white;font-size:12px">서버 수신</span></section></div>';
    })();
    """;

    private const string WebRtcBootstrapScript = """
    (async () => {
      const post = (type, message, extra = {}) => chrome.webview.postMessage(JSON.stringify({ type, message, ...extra }));
      if (window.__innoliveStop) await window.__innoliveStop();

      let sessionId = null;
      let peer = null;
      let socket = null;
      let localStream = null;
      let remoteStream = null;
      let remoteTrackReceived = false;
      let mediaConnectedPosted = false;
      let connectionTimeout = null;
      const localCandidates = [];
      const remoteCandidates = [];
      const summarizeCandidate = candidate => {
        const parts = candidate.replace(/^a=/, '').split(/\s+/);
        const typeIndex = parts.indexOf('typ');
        return (typeIndex >= 0 ? parts[typeIndex + 1] : 'unknown') + '@' + (parts[4] || '?') + ':' + (parts[5] || '?');
      };
      const maybePostMediaConnected = () => {
        if (!mediaConnectedPosted && remoteTrackReceived && peer?.connectionState === 'connected') {
          mediaConnectedPosted = true;
          if (connectionTimeout) clearTimeout(connectionTimeout);
          post('mediaConnected', 'WebRTC 영상 송수신 연결 완료');
        }
      };

      const cleanup = async () => {
        if (connectionTimeout) clearTimeout(connectionTimeout);
        connectionTimeout = null;
        if (peer) {
          const closingPeer = peer;
          peer = null;
          closingPeer.ontrack = null;
          closingPeer.onicecandidate = null;
          closingPeer.onconnectionstatechange = null;
          closingPeer.oniceconnectionstatechange = null;
          closingPeer.close();
        }
        if (socket && socket.readyState < 2) {
          socket.onclose = null;
          socket.close(1000, 'client stop');
        }
        socket = null;
        if (localStream) localStream.getTracks().forEach(track => track.stop());
        localStream = null;
        if (remoteStream) remoteStream.getTracks().forEach(track => track.stop());
        remoteStream = null;
        if (sessionId) {
          try { await fetch('/sessions/' + encodeURIComponent(sessionId), { method: 'DELETE', keepalive: true }); } catch (_) {}
          sessionId = null;
        }
      };
      window.__innoliveStop = cleanup;

      try {
        post('status', '서버 세션을 생성하는 중입니다.');
        const sessionResponse = await fetch('/sessions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ metadata: { title: 'Windows WebRTC', broadcaster_id: 'host', client: 'innolive-windows' } })
        });
        if (!sessionResponse.ok) throw new Error('세션 생성 HTTP ' + sessionResponse.status);
        const session = await sessionResponse.json();
        sessionId = session.session_id;
        if (!sessionId) throw new Error('session_id가 없습니다.');

        document.documentElement.style.cssText = 'width:100%;height:100%;background:#000';
        document.body.style.cssText = 'margin:0;width:100%;height:100%;overflow:hidden;background:#000;font-family:Segoe UI,sans-serif';
        document.body.innerHTML = '<div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;width:100%;height:100%;box-sizing:border-box"><section style="position:relative;overflow:hidden;border-radius:4px;background:#0f1720"><video id="local" autoplay muted playsinline style="display:block;width:100%;height:100%;object-fit:contain;background:#0f1720"></video><span style="position:absolute;z-index:1;left:12px;top:10px;padding:4px 8px;border-radius:4px;background:#99000000;color:white;font-size:12px">내 화면</span></section><section style="position:relative;overflow:hidden;border-radius:4px;background:#000"><video id="remote" autoplay muted playsinline style="display:block;width:100%;height:100%;object-fit:contain;background:#000"></video><span style="position:absolute;z-index:1;left:12px;top:10px;padding:4px 8px;border-radius:4px;background:#99000000;color:white;font-size:12px">서버 수신</span></section></div>';
        const remoteVideo = document.getElementById('remote');
        const localVideo = document.getElementById('local');

        post('status', '카메라 권한과 video track을 확인하는 중입니다.');
        localStream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30, max: 30 } }, audio: false });
        localVideo.srcObject = localStream;

        let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
        try {
          const configResponse = await fetch('/webrtc/config');
          if (configResponse.ok) {
            const config = await configResponse.json();
            if (Array.isArray(config.iceServers) && config.iceServers.length) iceServers = config.iceServers;
          }
        } catch (_) {}
        peer = new RTCPeerConnection({ iceServers });
        remoteStream = new MediaStream();
        remoteVideo.srcObject = remoteStream;
        localStream.getTracks().forEach(track => peer.addTrack(track, localStream));

        peer.ontrack = event => {
          event.streams[0]?.getTracks().forEach(track => {
            if (!remoteStream.getTracks().some(existing => existing.id === track.id)) remoteStream.addTrack(track);
          });
          remoteTrackReceived = true;
          post('remoteTrack', '서버 처리 영상 track 수신', { kind: event.track.kind });
          maybePostMediaConnected();
        };
        peer.onconnectionstatechange = () => {
          if (peer.connectionState === 'failed') {
            const serverCandidates = [...new Set(remoteCandidates.map(summarizeCandidate))];
            post('error', 'ICE 연결 실패 · 서버 후보: ' + (serverCandidates.join(', ') || '없음'));
            void cleanup();
          } else {
            post('connectionState', peer.connectionState);
            maybePostMediaConnected();
          }
        };
        peer.oniceconnectionstatechange = () => post('status', 'ICE 상태: ' + peer.iceConnectionState);

        const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/signaling';
        socket = new WebSocket(wsUrl);
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('WebSocket 연결 시간 초과')), 15000);
          socket.onopen = () => { clearTimeout(timer); resolve(); };
          socket.onerror = () => { clearTimeout(timer); reject(new Error('WebSocket 연결 실패')); };
        });
        post('status', 'signaling 연결 완료, offer를 생성합니다.');

        peer.onicecandidate = event => {
          if (!event.candidate || socket.readyState !== WebSocket.OPEN) return;
          localCandidates.push(event.candidate.type + ':' + event.candidate.protocol + ':' + event.candidate.address);
          socket.send(JSON.stringify({ session_id: sessionId, type: 'ice_candidate', candidate: event.candidate.candidate, sdpMid: event.candidate.sdpMid, sdpMLineIndex: event.candidate.sdpMLineIndex }));
        };
        socket.onmessage = async event => {
          try {
            const message = JSON.parse(event.data);
            if (message.session_id && message.session_id !== sessionId) return;
            if (message.type === 'answer') {
              const answerCandidates = (message.sdp.match(/^a=candidate:.*$/gm) || []);
              answerCandidates.forEach(line => remoteCandidates.push(line.trim()));
              await peer.setRemoteDescription({ type: 'answer', sdp: message.sdp });
              post('status', '서버 SDP answer를 적용했습니다.');
            } else if (message.type === 'ice_candidate' && message.candidate) {
              remoteCandidates.push(message.candidate);
              await peer.addIceCandidate({ candidate: message.candidate, sdpMid: message.sdpMid, sdpMLineIndex: message.sdpMLineIndex });
            } else if (message.type === 'error') {
              throw new Error(message.message || message.error_code || '서버 signaling 오류');
            }
          } catch (error) { post('error', error.message); }
        };
        socket.onclose = event => {
          if (!remoteTrackReceived && event.code !== 1000) post('error', 'signaling 종료: ' + event.code + ' ' + event.reason);
        };

        const offer = await peer.createOffer({ offerToReceiveVideo: true });
        await peer.setLocalDescription(offer);
        socket.send(JSON.stringify({ session_id: sessionId, type: 'offer', sdp: offer.sdp }));
        post('status', 'WebRTC offer 전송 완료, 서버 answer를 기다립니다.');

        connectionTimeout = setTimeout(() => {
          if (!mediaConnectedPosted) {
            post('error', '30초 안에 WebRTC 영상 송수신 연결이 완료되지 않았습니다.');
            void cleanup();
          }
        }, 30000);
      } catch (error) {
        post('error', error && error.message ? error.message : String(error));
        await cleanup();
      }
    })();
    """;
}
