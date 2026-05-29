using System.IO.Pipes;
using System.Text;

namespace Fluxzero.Launchpad;

public sealed class SingleInstanceService : IDisposable
{
    public const string MutexName = "Local\\FluxzeroLaunchpad.Windows";
    private const string PipeName = "FluxzeroLaunchpad.Protocol";
    private readonly CancellationTokenSource cancellation = new();
    private readonly Task serverTask;
    private readonly Func<Uri, Task> handleUri;

    public SingleInstanceService(Func<Uri, Task> handleUri)
    {
        this.handleUri = handleUri;
        serverTask = Task.Run(ServerLoopAsync);
    }

    public static async Task SendAsync(Uri uri)
    {
        using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.Out, PipeOptions.Asynchronous);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        await pipe.ConnectAsync(timeout.Token);
        var bytes = Encoding.UTF8.GetBytes(uri.ToString());
        await pipe.WriteAsync(bytes, timeout.Token);
    }

    public void Dispose()
    {
        cancellation.Cancel();
        try
        {
            serverTask.Wait(TimeSpan.FromSeconds(1));
        }
        catch
        {
            // App shutdown path; there is nothing useful to recover here.
        }
        cancellation.Dispose();
    }

    private async Task ServerLoopAsync()
    {
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                await using var pipe = new NamedPipeServerStream(
                    PipeName,
                    PipeDirection.In,
                    maxNumberOfServerInstances: 1,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous);
                await pipe.WaitForConnectionAsync(cancellation.Token);
                using var reader = new StreamReader(pipe, Encoding.UTF8);
                var text = await reader.ReadToEndAsync(cancellation.Token);
                if (Uri.TryCreate(text, UriKind.Absolute, out var uri))
                {
                    await handleUri(uri);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                await Task.Delay(250, cancellation.Token);
            }
        }
    }
}
