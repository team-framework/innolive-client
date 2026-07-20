using InnoLive_Windows.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace InnoLive_Windows;

public sealed partial class MainPage : Page
{
    public StudioViewModel ViewModel { get; } = new();

    public MainPage() => InitializeComponent();

    private void AddScene_Click(object sender, RoutedEventArgs e) => ViewModel.AddScene();
    private void DuplicateScene_Click(object sender, RoutedEventArgs e) => ViewModel.DuplicateSelectedScene();
    private void RemoveScene_Click(object sender, RoutedEventArgs e) => ViewModel.RemoveSelectedScene();
    private void Scene_Click(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleButton { Tag: StudioSceneItem scene }) ViewModel.SelectScene(scene);
    }
    private void SourceType_Click(object sender, RoutedEventArgs e)
    {
        if (sender is MenuFlyoutItem { Tag: string kind } && Enum.TryParse<SourceKind>(kind, out var sourceKind)) ViewModel.AddSource(sourceKind);
    }
    private void SourceSelectionChanged(object sender, SelectionChangedEventArgs e) => ViewModel.SelectSource((sender as ListView)?.SelectedItem as SourceItem);
    private void SourceVisibility_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: SourceItem source }) ViewModel.ToggleSourceVisibility(source);
    }
    private void SourceLock_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleSelectedSourceLock();
    private void SourceForward_Click(object sender, RoutedEventArgs e) => ViewModel.MoveSelectedSource(1);
    private void SourceBackward_Click(object sender, RoutedEventArgs e) => ViewModel.MoveSelectedSource(-1);
    private void SourceRemove_Click(object sender, RoutedEventArgs e) => ViewModel.RemoveSelectedSource();
    private void SourceText_TextChanged(object sender, TextChangedEventArgs e) => ViewModel.UpdateSelectedSourceText((sender as TextBox)?.Text ?? string.Empty);
    private void SourceColor_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if ((sender as ComboBox)?.SelectedItem is ComboBoxItem { Tag: string color }) ViewModel.UpdateSelectedSourceColor(color);
    }
    private void AudioEnabled_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch { Tag: AudioChannelItem channel } toggle) ViewModel.SetAudioEnabled(channel, toggle.IsOn);
    }
    private void Record_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleRecording();
    private void Broadcast_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleBroadcast();
}
