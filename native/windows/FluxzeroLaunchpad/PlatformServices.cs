using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Fluxzero.Launchpad;

public sealed class ProjectCreationDefaultsStore(AppPaths paths)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) }
    };

    private string SettingsFile => Path.Combine(paths.AppDataDir, "project-defaults.json");

    public ProjectCreationDefaults Load()
    {
        try
        {
            if (!File.Exists(SettingsFile))
            {
                return ProjectCreationDefaults.Fallback;
            }

            var settings = JsonSerializer.Deserialize<ProjectCreationDefaults>(File.ReadAllText(SettingsFile), JsonOptions);
            return settings ?? ProjectCreationDefaults.Fallback;
        }
        catch
        {
            return ProjectCreationDefaults.Fallback;
        }
    }

    public void Save(ProjectCreationDefaults settings)
    {
        Directory.CreateDirectory(paths.AppDataDir);
        File.WriteAllText(SettingsFile, JsonSerializer.Serialize(settings, JsonOptions));
    }
}

public sealed class DevelopmentDependencyService(AppPaths paths, CommandRunner? runner = null)
{
    private static readonly SemaphoreSlim JavaInstallGate = new(1, 1);
    private readonly CommandRunner runner = runner ?? new CommandRunner();
    private readonly HttpClient http = new();

    public bool IsGitAvailable()
    {
        if (EnvFlag("FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_GIT"))
        {
            return false;
        }

        return GitCandidates().Any(File.Exists);
    }

    public JavaRuntimeStatus DetectJava25()
    {
        if (!EnvFlag("FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA")
            && DetectSystemJava25() is { } system)
        {
            return system;
        }

        return DetectManagedJava25() ?? JavaRuntimeStatus.Missing;
    }

    public async Task<JavaRuntimeStatus> EnsureJava25Async()
    {
        return await Task.Run(async () =>
        {
            await JavaInstallGate.WaitAsync();
            try
            {
                var ready = DetectJava25();
                if (ready.IsReady)
                {
                    return ready;
                }

                if (ConfiguredJavaSource() is { } source)
                {
                    await InstallJdkFromSourceAsync(source);
                }
                else
                {
                    await DownloadAndInstallJava25Async();
                }

                return DetectManagedJava25()
                    ?? throw new InvalidOperationException("Installed Java could not be verified.");
            }
            catch (Exception ex) when (ex is not InvalidOperationException)
            {
                throw new InvalidOperationException($"Could not prepare Java 25.{Environment.NewLine}{ex.Message}", ex);
            }
            finally
            {
                JavaInstallGate.Release();
            }
        });
    }

    private IEnumerable<string> GitCandidates()
    {
        foreach (var directory in PathEntries())
        {
            yield return Path.Combine(directory, "git.exe");
        }

        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Git", "cmd", "git.exe");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Git", "bin", "git.exe");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Git", "cmd", "git.exe");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Git", "cmd", "git.exe");
    }

    private JavaRuntimeStatus? DetectSystemJava25()
    {
        foreach (var javaHome in JavaHomeCandidates())
        {
            if (JavaVersion(javaHome) is { } version)
            {
                return new JavaRuntimeStatus
                {
                    HomePath = javaHome,
                    Source = JavaRuntimeSource.System,
                    Version = version
                };
            }
        }

        return null;
    }

    private JavaRuntimeStatus? DetectManagedJava25()
    {
        var managed = ManagedJavaHome();
        if (JavaVersion(managed) is not { } version)
        {
            return null;
        }

        return new JavaRuntimeStatus
        {
            HomePath = managed,
            Source = JavaRuntimeSource.Managed,
            Version = version
        };
    }

    private IEnumerable<string> JavaHomeCandidates()
    {
        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            yield return javaHome;
        }

        foreach (var directory in PathEntries())
        {
            var java = Path.Combine(directory, "java.exe");
            if (File.Exists(java))
            {
                yield return Path.GetFullPath(Path.Combine(directory, ".."));
            }
        }
    }

    private string? JavaVersion(string javaHome)
    {
        var java = Path.Combine(javaHome, "bin", "java.exe");
        if (!File.Exists(java))
        {
            return null;
        }

        var result = runner.RunAsync([java, "-version"], TimeSpan.FromSeconds(10)).GetAwaiter().GetResult();
        if (!result.Successful || MajorJavaVersion(result.Output) < 25)
        {
            return null;
        }

        var match = System.Text.RegularExpressions.Regex.Match(result.Output, "version\\s+\"([^\"]+)\"");
        return match.Success ? match.Groups[1].Value : "25+";
    }

    private static int MajorJavaVersion(string versionOutput)
    {
        var match = System.Text.RegularExpressions.Regex.Match(versionOutput, "version\\s+\"([^\"]+)\"");
        if (!match.Success)
        {
            return 0;
        }

        var version = match.Groups[1].Value;
        if (version.StartsWith("1.", StringComparison.Ordinal))
        {
            return int.TryParse(version.Split('.')[1], out var legacy) ? legacy : 0;
        }

        return int.TryParse(version.Split('.')[0], out var current) ? current : 0;
    }

    private async Task DownloadAndInstallJava25Async()
    {
        Directory.CreateDirectory(paths.AppDataDir);
        var archive = Path.Combine(paths.AppDataDir, "temurin-25.zip");
        var failures = new List<string>();

        foreach (var uri in TemurinDownloadUris())
        {
            try
            {
                using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead);
                if (!response.IsSuccessStatusCode)
                {
                    failures.Add($"{uri} returned {(int)response.StatusCode} {response.ReasonPhrase}");
                    continue;
                }

                await using (var source = await response.Content.ReadAsStreamAsync())
                await using (var target = File.Create(archive))
                {
                    await source.CopyToAsync(target);
                }

                await InstallJdkArchiveAsync(archive);
                File.Delete(archive);
                return;
            }
            catch (Exception ex)
            {
                failures.Add($"{uri} failed: {ex.Message}");
                if (File.Exists(archive))
                {
                    File.Delete(archive);
                }
            }
        }

        throw new InvalidOperationException(
            "Could not download Temurin Java 25. "
            + "Tried "
            + string.Join("; ", failures));
    }

    private async Task InstallJdkFromSourceAsync(string source)
    {
        if (Directory.Exists(source))
        {
            InstallJdkDirectory(source);
            return;
        }

        if (!File.Exists(source))
        {
            throw new InvalidOperationException($"Java source does not exist: {source}");
        }

        await InstallJdkArchiveAsync(source);
    }

    private Task InstallJdkArchiveAsync(string archive)
    {
        var extractionRoot = Path.Combine(paths.AppDataDir, "tmp", $"jdk-{Guid.NewGuid():N}");
        Directory.CreateDirectory(extractionRoot);
        try
        {
            ZipFile.ExtractToDirectory(archive, extractionRoot);
            var home = FindJavaHome(extractionRoot)
                ?? throw new InvalidOperationException("Downloaded archive did not contain a Windows JDK.");
            InstallJdkDirectory(home);
        }
        finally
        {
            if (Directory.Exists(extractionRoot))
            {
                Directory.Delete(extractionRoot, recursive: true);
            }
        }

        return Task.CompletedTask;
    }

    private void InstallJdkDirectory(string source)
    {
        var home = FindJavaHome(source) ?? source;
        if (!File.Exists(Path.Combine(home, "bin", "java.exe")))
        {
            throw new InvalidOperationException($"Java source does not look like a JDK: {source}");
        }

        var target = ManagedJavaHome();
        var staging = $"{target}.{Guid.NewGuid():N}.staged";
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        CopyDirectory(home, staging);
        if (Directory.Exists(target))
        {
            Directory.Delete(target, recursive: true);
        }
        Directory.Move(staging, target);
    }

    private static string? FindJavaHome(string root)
    {
        if (File.Exists(Path.Combine(root, "bin", "java.exe")))
        {
            return root;
        }

        return Directory
            .EnumerateDirectories(root, "*", SearchOption.AllDirectories)
            .FirstOrDefault(directory => File.Exists(Path.Combine(directory, "bin", "java.exe")));
    }

    private static void CopyDirectory(string source, string target)
    {
        Directory.CreateDirectory(target);
        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(directory.Replace(source, target, StringComparison.Ordinal));
        }
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            File.Copy(file, file.Replace(source, target, StringComparison.Ordinal), overwrite: true);
        }
    }

    private string ManagedJavaHome()
    {
        if (Environment.GetEnvironmentVariable("FLUXZERO_LAUNCHPAD_JAVA_INSTALL_DIR") is { Length: > 0 } overridePath)
        {
            return overridePath;
        }

        return Path.Combine(paths.AppDataDir, "jdks", "temurin-25");
    }

    private static string? ConfiguredJavaSource() =>
        Environment.GetEnvironmentVariable("FLUXZERO_LAUNCHPAD_JAVA_SOURCE");

    private static IEnumerable<Uri> TemurinDownloadUris()
    {
        var architectures = RuntimeInformation.ProcessArchitecture == Architecture.Arm64
            ? new[] { "aarch64", "x64" }
            : new[] { "x64" };

        foreach (var architecture in architectures)
        {
            yield return new Uri($"https://api.adoptium.net/v3/binary/latest/25/ga/windows/{architecture}/jdk/hotspot/normal/eclipse?project=jdk");
        }
    }

    private static IEnumerable<string> PathEntries() =>
        (Environment.GetEnvironmentVariable("PATH") ?? "")
        .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

    private static bool EnvFlag(string name) =>
        Environment.GetEnvironmentVariable(name)?.ToLowerInvariant() switch
        {
            "1" or "true" or "yes" or "y" => true,
            _ => false
        };
}
