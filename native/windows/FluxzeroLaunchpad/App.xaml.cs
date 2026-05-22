using Microsoft.UI.Xaml;

namespace Fluxzero.Launchpad;

public partial class App : Application
{
    private MainWindow? window;

    public App()
    {
        InitializeComponent();
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        window = new MainWindow();
        window.Activate();
        await window.PrepareAsync();

        var protocolUrl = Environment.GetCommandLineArgs()
            .FirstOrDefault(argument => argument.StartsWith("fluxzero://", StringComparison.OrdinalIgnoreCase));
        if (Uri.TryCreate(protocolUrl, UriKind.Absolute, out var uri))
        {
            await window.HandleDeepLinkAsync(uri);
        }
    }
}
