using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
using Windows.ApplicationModel.Activation;

namespace Fluxzero.Launchpad;

public partial class App : Application
{
    private MainWindow? window;
    private TrayIconController? trayIcon;
    private SingleInstanceService? singleInstance;
    private Mutex? instanceMutex;

    public App()
    {
        InitializeComponent();
    }

    protected override async void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        var protocolUri = ActivatedProtocolUri();
        instanceMutex = new Mutex(initiallyOwned: true, SingleInstanceService.MutexName, out var isFirstInstance);
        if (!isFirstInstance)
        {
            if (protocolUri is not null)
            {
                try
                {
                    await SingleInstanceService.SendAsync(protocolUri);
                }
                catch
                {
                    // If the existing app is shutting down, Windows can relaunch the protocol.
                }
            }
            instanceMutex.Dispose();
            instanceMutex = null;
            Current.Exit();
            return;
        }

        window = new MainWindow();
        trayIcon = new TrayIconController(
            showCreate: () => Enqueue(() => window!.ShowCreateWindow()),
            refresh: () => _ = EnqueueAsync(async () => await window!.PrepareAsync()),
            showSettings: () => Enqueue(() => window!.ShowSettingsWindow()),
            showAbout: () => Enqueue(() => window!.ShowAboutWindow()),
            quit: Quit);
        window.BusyChanged += trayIcon.SetBusy;
        window.StatusChanged += trayIcon.SetStatus;
        window.ErrorOccurred += trayIcon.ShowError;
        singleInstance = new SingleInstanceService(uri => EnqueueAsync(async () => await window!.HandleDeepLinkAsync(uri, DeepLinkPresentationMode.Background)));

        await window.PrepareAsync();
        if (protocolUri is not null)
        {
            await window.HandleDeepLinkAsync(protocolUri, DeepLinkPresentationMode.Background);
        }
    }

    private void Quit()
    {
        if (window is not null)
        {
            window.AllowCloseForQuit();
        }
        singleInstance?.Dispose();
        trayIcon?.Dispose();
        instanceMutex?.ReleaseMutex();
        instanceMutex?.Dispose();
        Current.Exit();
    }

    private void Enqueue(Action action)
    {
        if (window is null)
        {
            return;
        }
        window.DispatcherQueue.TryEnqueue(() => action());
    }

    private Task EnqueueAsync(Func<Task> action)
    {
        if (window is null)
        {
            return Task.CompletedTask;
        }

        var completion = new TaskCompletionSource();
        if (!window.DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Normal, async () =>
            {
                try
                {
                    await action();
                    completion.SetResult();
                }
                catch (Exception ex)
                {
                    completion.SetException(ex);
                }
            }))
        {
            completion.SetResult();
        }
        return completion.Task;
    }

    private static Uri? CommandLineProtocolUri()
    {
        var protocolUrl = Environment.GetCommandLineArgs()
            .FirstOrDefault(argument => argument.StartsWith("fluxzero://", StringComparison.OrdinalIgnoreCase));
        return Uri.TryCreate(protocolUrl, UriKind.Absolute, out var uri) ? uri : null;
    }

    private static Uri? ActivatedProtocolUri()
    {
        try
        {
            var activatedArgs = AppInstance.GetCurrent().GetActivatedEventArgs();
            if (activatedArgs.Kind == ExtendedActivationKind.Protocol
                && activatedArgs.Data is IProtocolActivatedEventArgs protocolArgs)
            {
                return protocolArgs.Uri;
            }
        }
        catch
        {
            // Unpackaged development builds still receive the URL through argv.
        }

        return CommandLineProtocolUri();
    }
}
