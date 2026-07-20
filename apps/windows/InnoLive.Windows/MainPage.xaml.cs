using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using InnoLive_Windows.Models;

namespace InnoLive_Windows;

public sealed partial class MainPage : Page
{
    public StudioViewModel ViewModel { get; } = new();

    public MainPage()
    {
        InitializeComponent();
    }

    private void AddScene_Click(object sender, RoutedEventArgs e) => ViewModel.AddScene();

    private void Scene_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: StudioSceneItem scene })
        {
            ViewModel.SelectScene(scene);
        }
    }

    private void SourceAdd_Click(object sender, RoutedEventArgs e) { }

    private void SourceType_Click(object sender, RoutedEventArgs e)
    {
        if (sender is MenuFlyoutItem { Tag: string kind })
        {
            ViewModel.AddSource(kind);
        }
    }

    private void Record_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleRecording();

    private void Broadcast_Click(object sender, RoutedEventArgs e) => ViewModel.ToggleBroadcast();
}
