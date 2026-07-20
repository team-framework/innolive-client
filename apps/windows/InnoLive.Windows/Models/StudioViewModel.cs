using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Microsoft.UI.Xaml;

namespace InnoLive_Windows.Models;

public sealed class StudioViewModel : INotifyPropertyChanged
{
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromSeconds(1) };
    private DateTimeOffset? _liveStartedAt;
    private DateTimeOffset? _recordingStartedAt;
    private BroadcastState _broadcastState = BroadcastState.Idle;
    private bool _isRecording;
    private bool _isAnonymizationEnabled;
    private string _statusMessage = "방송을 시작할 준비가 되었습니다.";

    public StudioViewModel()
    {
        Scenes = new ObservableCollection<StudioSceneItem>
        {
            new("장면 1", "ms-appx:///Assets/Figma/scene-mail.png", true),
            new("장면 2", "ms-appx:///Assets/Figma/scene-security.png")
        };
        Sources = new ObservableCollection<SourceItem> { new("카메라") };
        AudioChannels = new ObservableCollection<AudioChannelItem>
        {
            new("Scarlett Solo 4th Generation", .62),
            new("시스템 스피커", .62),
            new("기본 마이크", .48)
        };
        CameraDevices = new ObservableCollection<string> { "기본 카메라", "통합 카메라" };
        ScreenDevices = new ObservableCollection<string> { "Scarlett Solo 4th Generation", "시스템 스피커" };
        _clock.Tick += (_, _) => RefreshDuration();
        _clock.Start();
    }

    public ObservableCollection<StudioSceneItem> Scenes { get; }
    public ObservableCollection<SourceItem> Sources { get; }
    public ObservableCollection<AudioChannelItem> AudioChannels { get; }
    public ObservableCollection<string> CameraDevices { get; }
    public ObservableCollection<string> ScreenDevices { get; }

    public string StatusMessage { get => _statusMessage; private set => Set(ref _statusMessage, value); }
    public string LiveDuration => DurationFor(_liveStartedAt);
    public string RecordingDuration => DurationFor(_recordingStartedAt);
    public string BroadcastStatusText => _broadcastState == BroadcastState.Live ? "ONAIR" : "READY";
    public string RecordingButtonText => _isRecording ? "녹화 중지" : "녹화 시작";
    public string BroadcastButtonText => _broadcastState == BroadcastState.Live ? "방송 종료" : "방송 시작";
    public bool IsAnonymizationEnabled
    {
        get => _isAnonymizationEnabled;
        set
        {
            if (_isAnonymizationEnabled == value) return;
            _isAnonymizationEnabled = value;
            OnChanged();
            StatusMessage = value ? "비식별화 처리가 켜졌습니다." : "비식별화 처리가 꺼졌습니다.";
        }
    }

    public void AddScene()
    {
        var scene = new StudioSceneItem($"장면 {Scenes.Count + 1}", "ms-appx:///Assets/Figma/scene-mail.png");
        Scenes.Add(scene);
        SelectScene(scene);
        StatusMessage = $"{scene.Name}을 추가했습니다.";
    }

    public void SelectScene(StudioSceneItem scene)
    {
        foreach (var item in Scenes) item.IsSelected = item == scene;
        StatusMessage = $"{scene.Name}을(를) 미리보기로 선택했습니다.";
    }

    public void AddSource(string kind)
    {
        var name = kind switch { "camera" => "카메라", "screen" => "화면 캡처", _ => "텍스트" };
        Sources.Add(new SourceItem(name));
        StatusMessage = $"{name} 소스를 추가했습니다.";
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
        OnChanged(nameof(BroadcastStatusText));
        OnChanged(nameof(LiveDuration));
    }

    private void RefreshDuration()
    {
        OnChanged(nameof(LiveDuration));
        OnChanged(nameof(RecordingDuration));
    }

    private static string DurationFor(DateTimeOffset? start) => start is null ? "00:00:00" : (DateTimeOffset.Now - start.Value).ToString(@"hh\:mm\:ss");
    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null) { field = value; OnChanged(name); }
}

public enum BroadcastState { Idle, Connecting, Live, Stopping, Failed }

public sealed class StudioSceneItem : INotifyPropertyChanged
{
    private bool _isSelected;
    public StudioSceneItem(string name, string iconPath, bool isSelected = false) { Name = name; IconPath = iconPath; _isSelected = isSelected; }
    public string Name { get; }
    public string IconPath { get; }
    public bool IsSelected { get => _isSelected; set { if (_isSelected == value) return; _isSelected = value; PropertyChanged?.Invoke(this, new(nameof(IsSelected))); } }
    public event PropertyChangedEventHandler? PropertyChanged;
}

public sealed record SourceItem(string Name);

public sealed class AudioChannelItem : INotifyPropertyChanged
{
    private double _volume;
    public AudioChannelItem(string name, double volume) { Name = name; _volume = volume; }
    public string Name { get; }
    public double Volume { get => _volume; set { _volume = value; PropertyChanged?.Invoke(this, new(nameof(Volume))); } }
    public event PropertyChangedEventHandler? PropertyChanged;
}
