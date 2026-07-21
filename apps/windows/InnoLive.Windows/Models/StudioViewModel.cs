using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Microsoft.UI.Xaml;
using InnoLive_Windows.Services;

namespace InnoLive_Windows.Models;

public sealed class StudioViewModel : INotifyPropertyChanged
{
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private DateTimeOffset? _liveStartedAt;
    private DateTimeOffset? _recordingStartedAt;
    private BroadcastState _broadcastState = BroadcastState.Idle;
    private bool _isRecording;
    private bool _isAnonymizationEnabled;
    private SourceItem? _selectedSource;
    private string _statusMessage = "방송을 시작할 준비가 되었습니다.";
    private int _meterTick;
    private readonly ServerDiagnosticsClient _serverDiagnosticsClient = new();
    private bool _isServerTestInProgress;
    private string _serverStatusText = "서버 진단을 실행할 수 있습니다.";
    private string _lastServerSessionId = "-";

    public StudioViewModel()
    {
        Scenes = new ObservableCollection<StudioSceneItem>
        {
            new("장면 1", "ms-appx:///Assets/Figma/scene-mail.png", true),
            new("장면 2", "ms-appx:///Assets/Figma/scene-security.png")
        };
        Sources = new ObservableCollection<SourceItem> { new("카메라 1", SourceKind.Camera) };
        SelectedSource = Sources[0];
        AudioChannels = new ObservableCollection<AudioChannelItem>
        {
            new("기본 마이크", .82),
            new("시스템 스피커", .62),
            new("Scarlett Solo 4th Generation", .48)
        };
        CameraDevices = new ObservableCollection<string> { "기본 카메라", "통합 카메라" };
        ScreenDevices = new ObservableCollection<string> { "주 모니터", "보조 모니터" };
        _clock.Tick += (_, _) => RefreshStudioState();
        _clock.Start();
    }

    public ObservableCollection<StudioSceneItem> Scenes { get; }
    public ObservableCollection<SourceItem> Sources { get; }
    public ObservableCollection<AudioChannelItem> AudioChannels { get; }
    public ObservableCollection<string> CameraDevices { get; }
    public ObservableCollection<string> ScreenDevices { get; }

    public SourceItem? SelectedSource
    {
        get => _selectedSource;
        set
        {
            if (ReferenceEquals(_selectedSource, value)) return;
            _selectedSource = value;
            OnChanged();
            OnChanged(nameof(SelectedSourceText));
            OnChanged(nameof(SelectedSourceColor));
            OnChanged(nameof(PreviewOverlayText));
            OnChanged(nameof(PreviewOverlayVisibility));
        }
    }

    public string StatusMessage { get => _statusMessage; private set => Set(ref _statusMessage, value); }
    public string LiveDuration => DurationFor(_liveStartedAt);
    public string RecordingDuration => DurationFor(_recordingStartedAt);
    public string BroadcastStatusText => _broadcastState switch
    {
        BroadcastState.Connecting => "CONNECTING",
        BroadcastState.Live => "ONAIR",
        BroadcastState.Failed => "FAILED",
        _ => "READY"
    };
    public string RecordingButtonText => _isRecording ? "녹화 중지" : "녹화 시작";
    public string BroadcastButtonText => _broadcastState switch
    {
        BroadcastState.Connecting => "연결 취소",
        BroadcastState.Live => "방송 종료",
        _ => "방송 시작"
    };
    public bool IsBroadcastLive => _broadcastState == BroadcastState.Live;
    public bool IsBroadcastActive => _broadcastState is BroadcastState.Connecting or BroadcastState.Live or BroadcastState.Stopping;
    public string SelectedSourceText => SelectedSource?.Text ?? string.Empty;
    public string SelectedSourceColor => SelectedSource?.ColorHex ?? "#3478F6";
    public string PreviewOverlayText => SelectedSource is { IsVisible: true, Kind: SourceKind.Text } ? SelectedSource.Text : string.Empty;
    public Visibility PreviewOverlayVisibility => string.IsNullOrWhiteSpace(PreviewOverlayText) ? Visibility.Collapsed : Visibility.Visible;
    public string ServerEndpointText => ServerEnvironment.HttpBaseUri.AbsoluteUri.TrimEnd('/');
    public string SignalingEndpointText => ServerEnvironment.SignalingUri.AbsoluteUri;
    public string ServerStatusText { get => _serverStatusText; private set => Set(ref _serverStatusText, value); }
    public string LastServerSessionId { get => _lastServerSessionId; private set => Set(ref _lastServerSessionId, value); }
    public bool IsServerTestInProgress { get => _isServerTestInProgress; private set => Set(ref _isServerTestInProgress, value); }

    public bool IsAnonymizationEnabled
    {
        get => _isAnonymizationEnabled;
        set
        {
            if (_isAnonymizationEnabled == value) return;
            _isAnonymizationEnabled = value;
            OnChanged();
            StatusMessage = value ? "비식별화 처리를 켰습니다." : "비식별화 처리를 껐습니다.";
        }
    }

    public void AddScene()
    {
        var scene = new StudioSceneItem($"장면 {Scenes.Count + 1}", "ms-appx:///Assets/Figma/scene-mail.png");
        Scenes.Add(scene);
        SelectScene(scene);
        StatusMessage = $"{scene.Name}을 추가했습니다.";
    }

    public void DuplicateSelectedScene()
    {
        var selected = Scenes.FirstOrDefault(scene => scene.IsSelected);
        if (selected is null) return;
        var copy = new StudioSceneItem($"{selected.Name} 복사", selected.IconPath);
        Scenes.Add(copy);
        SelectScene(copy);
        StatusMessage = $"{selected.Name}을 복제했습니다.";
    }

    public void RemoveSelectedScene()
    {
        if (Scenes.Count == 1)
        {
            StatusMessage = "최소 한 개의 장면이 필요합니다.";
            return;
        }

        var selected = Scenes.FirstOrDefault(scene => scene.IsSelected);
        if (selected is null) return;
        var index = Scenes.IndexOf(selected);
        Scenes.Remove(selected);
        SelectScene(Scenes[Math.Min(index, Scenes.Count - 1)]);
        StatusMessage = $"{selected.Name}을 삭제했습니다.";
    }

    public void SelectScene(StudioSceneItem scene)
    {
        foreach (var item in Scenes) item.IsSelected = ReferenceEquals(item, scene);
        StatusMessage = $"{scene.Name}을 미리보기로 선택했습니다.";
    }

    public void AddSource(SourceKind kind)
    {
        var source = new SourceItem(DefaultSourceName(kind), kind)
        {
            Text = kind == SourceKind.Text ? "라이브 텍스트" : string.Empty,
            ColorHex = kind == SourceKind.Color ? "#3478F6" : "#FFFFFF"
        };
        Sources.Add(source);
        SelectedSource = source;
        StatusMessage = $"{source.Name} 소스를 추가했습니다.";
    }

    public void SelectSource(SourceItem? source)
    {
        SelectedSource = source;
        if (source is not null) StatusMessage = $"{source.Name} 소스를 선택했습니다.";
    }

    public void RemoveSelectedSource()
    {
        if (SelectedSource is null) return;
        if (SelectedSource.IsLocked)
        {
            StatusMessage = "잠긴 소스는 삭제할 수 없습니다.";
            return;
        }

        var index = Sources.IndexOf(SelectedSource);
        var removed = SelectedSource;
        Sources.Remove(removed);
        SelectedSource = Sources.Count == 0 ? null : Sources[Math.Min(index, Sources.Count - 1)];
        StatusMessage = $"{removed.Name} 소스를 삭제했습니다.";
    }

    public void ToggleSelectedSourceLock()
    {
        if (SelectedSource is null) return;
        SelectedSource.IsLocked = !SelectedSource.IsLocked;
        StatusMessage = SelectedSource.IsLocked ? $"{SelectedSource.Name} 소스를 잠갔습니다." : $"{SelectedSource.Name} 소스 잠금을 해제했습니다.";
    }

    public void ToggleSourceVisibility(SourceItem source)
    {
        source.IsVisible = !source.IsVisible;
        OnChanged(nameof(PreviewOverlayText));
        OnChanged(nameof(PreviewOverlayVisibility));
        StatusMessage = source.IsVisible ? $"{source.Name} 소스를 표시합니다." : $"{source.Name} 소스를 숨겼습니다.";
    }

    public void MoveSelectedSource(int direction)
    {
        if (SelectedSource is null) return;
        var index = Sources.IndexOf(SelectedSource);
        var target = Math.Clamp(index + direction, 0, Sources.Count - 1);
        if (index == target) return;
        Sources.Move(index, target);
        StatusMessage = direction > 0 ? $"{SelectedSource.Name} 소스를 앞으로 이동했습니다." : $"{SelectedSource.Name} 소스를 뒤로 이동했습니다.";
    }

    public void UpdateSelectedSourceText(string text)
    {
        if (SelectedSource is not { Kind: SourceKind.Text } source) return;
        source.Text = text;
        source.Name = string.IsNullOrWhiteSpace(text) ? "텍스트" : text;
        OnChanged(nameof(SelectedSourceText));
        OnChanged(nameof(PreviewOverlayText));
        OnChanged(nameof(PreviewOverlayVisibility));
    }

    public void UpdateSelectedSourceColor(string color)
    {
        if (SelectedSource is null) return;
        SelectedSource.ColorHex = color;
        OnChanged(nameof(SelectedSourceColor));
        StatusMessage = $"{SelectedSource.Name} 색상을 변경했습니다.";
    }

    public void SetAudioEnabled(AudioChannelItem channel, bool isEnabled)
    {
        channel.IsEnabled = isEnabled;
        if (!isEnabled) channel.Level = 0;
        StatusMessage = isEnabled ? $"{channel.Name} 오디오를 켰습니다." : $"{channel.Name} 오디오를 껐습니다.";
    }

    public void ToggleRecording()
    {
        _isRecording = !_isRecording;
        _recordingStartedAt = _isRecording ? DateTimeOffset.Now : null;
        StatusMessage = _isRecording ? "로컬 녹화를 시작했습니다." : "로컬 녹화를 중지했습니다.";
        OnChanged(nameof(RecordingButtonText));
        OnChanged(nameof(RecordingDuration));
    }

    public void ToggleBroadcast()
    {
        _broadcastState = _broadcastState == BroadcastState.Live ? BroadcastState.Idle : BroadcastState.Live;
        _liveStartedAt = _broadcastState == BroadcastState.Live ? DateTimeOffset.Now : null;
        StatusMessage = _broadcastState == BroadcastState.Live ? "방송을 시작했습니다." : "방송을 종료했습니다.";
        OnChanged(nameof(BroadcastButtonText));
        OnChanged(nameof(IsBroadcastLive));
        OnChanged(nameof(BroadcastStatusText));
        OnChanged(nameof(LiveDuration));
    }

    public void BeginBroadcastConnection()
    {
        _broadcastState = BroadcastState.Connecting;
        _liveStartedAt = null;
        StatusMessage = "카메라와 WebRTC 연결을 준비하고 있습니다.";
        NotifyBroadcastState();
    }

    public void MarkBroadcastLive()
    {
        if (_broadcastState == BroadcastState.Live) return;
        _broadcastState = BroadcastState.Live;
        _liveStartedAt = DateTimeOffset.Now;
        StatusMessage = "WebRTC 영상 송수신이 연결되었습니다.";
        NotifyBroadcastState();
    }

    public void FailBroadcast(string message)
    {
        _broadcastState = BroadcastState.Failed;
        _liveStartedAt = null;
        ServerStatusText = message;
        StatusMessage = message;
        NotifyBroadcastState();
    }

    public void MarkBroadcastStopped()
    {
        _broadcastState = BroadcastState.Idle;
        _liveStartedAt = null;
        StatusMessage = "방송을 종료하고 서버 세션을 정리했습니다.";
        NotifyBroadcastState();
    }

    private void NotifyBroadcastState()
    {
        OnChanged(nameof(BroadcastButtonText));
        OnChanged(nameof(IsBroadcastLive));
        OnChanged(nameof(IsBroadcastActive));
        OnChanged(nameof(BroadcastStatusText));
        OnChanged(nameof(LiveDuration));
    }

    public async Task VerifyServerConnectionAsync()
    {
        if (IsServerTestInProgress) return;
        IsServerTestInProgress = true;
        ServerStatusText = "서버 연결 및 세션 정리를 확인하는 중입니다.";
        try
        {
            var result = await _serverDiagnosticsClient.VerifyAsync();
            LastServerSessionId = result.SessionId;
            ServerStatusText = $"연결 성공: health {(int)result.HealthStatus}, 세션 정리 {(int)result.DeleteStatus}";
            StatusMessage = $"{ServerEndpointText} 서버 연결을 확인했습니다.";
        }
        catch (Exception exception)
        {
            ServerStatusText = $"연결 실패: {exception.Message}";
            StatusMessage = "서버 연결에 실패했습니다. 주소와 네트워크를 확인하세요.";
        }
        finally
        {
            IsServerTestInProgress = false;
        }
    }

    public void ReportWebRtcStatus(string message)
    {
        ServerStatusText = message;
        StatusMessage = message;
    }

    private void RefreshStudioState()
    {
        _meterTick++;
        foreach (var channel in AudioChannels)
        {
            channel.Level = channel.IsEnabled ? Math.Round((Math.Sin(_meterTick * .55 + channel.Volume * 4) + 1) * .3 * channel.Volume, 2) : 0;
        }
        OnChanged(nameof(LiveDuration));
        OnChanged(nameof(RecordingDuration));
    }

    private string DefaultSourceName(SourceKind kind) => kind switch
    {
        SourceKind.Camera => $"카메라 {Sources.Count(source => source.Kind == kind) + 1}",
        SourceKind.Screen => $"화면 캡처 {Sources.Count(source => source.Kind == kind) + 1}",
        SourceKind.Text => "라이브 텍스트",
        SourceKind.Image => $"이미지 {Sources.Count(source => source.Kind == kind) + 1}",
        _ => $"색상 소스 {Sources.Count(source => source.Kind == kind) + 1}"
    };

    private static string DurationFor(DateTimeOffset? start) => start is null ? "00:00:00" : (DateTimeOffset.Now - start.Value).ToString(@"hh\:mm\:ss");
    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null) { field = value; OnChanged(name); }
}

public enum BroadcastState { Idle, Connecting, Live, Stopping, Failed }
public enum SourceKind { Camera, Screen, Text, Image, Color }

public sealed class StudioSceneItem : INotifyPropertyChanged
{
    private bool _isSelected;
    public StudioSceneItem(string name, string iconPath, bool isSelected = false) { Name = name; IconPath = iconPath; _isSelected = isSelected; }
    public string Name { get; }
    public string IconPath { get; }
    public bool IsSelected { get => _isSelected; set { if (_isSelected == value) return; _isSelected = value; PropertyChanged?.Invoke(this, new(nameof(IsSelected))); } }
    public event PropertyChangedEventHandler? PropertyChanged;
}

public sealed class SourceItem : INotifyPropertyChanged
{
    private string _name;
    private bool _isVisible = true;
    private bool _isLocked;
    private string _text = string.Empty;
    private string _colorHex = "#FFFFFF";
    public SourceItem(string name, SourceKind kind) { _name = name; Kind = kind; }
    public SourceKind Kind { get; }
    public string Name { get => _name; set => Set(ref _name, value); }
    public bool IsVisible { get => _isVisible; set => Set(ref _isVisible, value); }
    public bool IsLocked { get => _isLocked; set => Set(ref _isLocked, value); }
    public string Text { get => _text; set => Set(ref _text, value); }
    public string ColorHex { get => _colorHex; set => Set(ref _colorHex, value); }
    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null) { if (EqualityComparer<T>.Default.Equals(field, value)) return; field = value; PropertyChanged?.Invoke(this, new(name)); }
}

public sealed class AudioChannelItem : INotifyPropertyChanged
{
    private double _volume;
    private bool _isEnabled = true;
    private double _level;
    public AudioChannelItem(string name, double volume) { Name = name; _volume = volume; }
    public string Name { get; }
    public double Volume { get => _volume; set => Set(ref _volume, value); }
    public bool IsEnabled { get => _isEnabled; set => Set(ref _isEnabled, value); }
    public double Level { get => _level; set => Set(ref _level, value); }
    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null) { if (EqualityComparer<T>.Default.Equals(field, value)) return; field = value; PropertyChanged?.Invoke(this, new(name)); }
}
