using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace Fluxzero.Launchpad;

public sealed partial class AboutWindow : Window
{
    public AboutWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
        ConfigureWindow();
    }

    public void ShowAbout(string? cliVersion)
    {
        VersionText.Text = $"Version {AppVersion} (Fluxzero CLI {cliVersion ?? "not ready"})";
        Activate();
    }

    private void ConfigureWindow()
    {
        var icon = Path.Combine(AppContext.BaseDirectory, "Assets", "fluxzero.ico");
        if (File.Exists(icon))
        {
            AppWindow.SetIcon(icon);
        }
        AppWindow.Resize(new SizeInt32(420, 280));
        AppWindow.Title = "About Fluxzero Launchpad";
    }

    private static string AppVersion =>
        typeof(AboutWindow).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
}
