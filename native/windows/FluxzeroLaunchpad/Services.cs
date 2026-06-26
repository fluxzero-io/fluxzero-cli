using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;
using Windows.ApplicationModel.DataTransfer;

namespace Fluxzero.Launchpad;

public sealed class AppPaths
{
    public required string AppDataDir { get; init; }
    public required string BinDir { get; init; }
    public required string CliExecutable { get; init; }
    public required string CliJar { get; init; }
    public required string RegistryFile { get; init; }

    public static AppPaths Detect()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Fluxzero",
            "Launchpad");
        var bin = Path.Combine(root, "bin");
        return new AppPaths
        {
            AppDataDir = root,
            BinDir = bin,
            CliExecutable = Path.Combine(bin, "fz.exe"),
            CliJar = Path.Combine(bin, "fluxzero-cli.jar"),
            RegistryFile = Path.Combine(root, "projects.json")
        };
    }
}

public sealed class CommandRunner
{
    public async Task<CommandResult> RunAsync(IReadOnlyList<string> command, TimeSpan? timeout = null)
    {
        if (command.Count == 0)
        {
            return new CommandResult { ExitCode = -2, Output = "No command was provided." };
        }

        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = command[0],
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            CreateNoWindow = true
        };
        foreach (var argument in command.Skip(1))
        {
            process.StartInfo.ArgumentList.Add(argument);
        }

        var output = new StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) output.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) output.AppendLine(e.Data); };

        try
        {
            process.Start();
        }
        catch (Exception ex)
        {
            return new CommandResult
            {
                Command = command.ToList(),
                ExitCode = -2,
                Output = $"Could not start command: {FormatCommand(command)}{Environment.NewLine}{ex.Message}"
            };
        }

        process.StandardInput.Close();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        using var cancellation = new CancellationTokenSource(timeout ?? TimeSpan.FromMinutes(5));
        try
        {
            await process.WaitForExitAsync(cancellation.Token);
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            return new CommandResult
            {
                Command = command.ToList(),
                ExitCode = -1,
                Output = $"Command timed out: {FormatCommand(command)}"
            };
        }

        process.WaitForExit();
        return new CommandResult { Command = command.ToList(), ExitCode = process.ExitCode, Output = output.ToString() };
    }

    public static string FormatCommand(IReadOnlyList<string> command) =>
        string.Join(" ", command.Select(QuoteArgument));

    private static string QuoteArgument(string argument) =>
        argument.Any(char.IsWhiteSpace) ? $"\"{argument.Replace("\"", "\\\"", StringComparison.Ordinal)}\"" : argument;
}

public sealed class GitHubRelease
{
    [JsonPropertyName("tag_name")]
    public string TagName { get; set; } = "";

    [JsonPropertyName("assets")]
    public List<ReleaseAsset> Assets { get; set; } = [];

    public Uri? NativeDownloadUri()
    {
        var assetNames = RuntimeInformation.ProcessArchitecture == Architecture.Arm64
            ? new[] { "flux-windows-arm64.exe", "flux-windows-aarch64.exe" }
            : new[] { "flux-windows-amd64.exe" };
        var asset = assetNames
            .Select(name => Assets.FirstOrDefault(candidate => candidate.Name == name))
            .FirstOrDefault(candidate => candidate is not null);
        return asset?.BrowserDownloadUrl;
    }

    public Uri JarDownloadUri()
    {
        const string assetName = "fluxzero-cli.jar";
        var asset = Assets.FirstOrDefault(candidate => candidate.Name == assetName);
        return asset?.BrowserDownloadUrl ?? new Uri($"https://github.com/fluxzero-io/fluxzero-cli/releases/download/{TagName}/{assetName}");
    }
}

public sealed class ReleaseAsset
{
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("browser_download_url")]
    public Uri? BrowserDownloadUrl { get; set; }
}

public sealed class CliRuntimeService(AppPaths paths, DevelopmentDependencyService dependencies, CommandRunner? runner = null)
{
    private static readonly Uri LatestReleaseUri = new("https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases/latest");
    private readonly CommandRunner runner = runner ?? new CommandRunner();
    private readonly HttpClient http = new();
    private IReadOnlyList<string>? commandPrefix;

    public async Task<CliStatus> EnsureLatestCliAsync()
    {
        Directory.CreateDirectory(paths.BinDir);

        try
        {
            var release = await FetchLatestReleaseAsync();
            var nativeUri = release.NativeDownloadUri();
            if (nativeUri is null)
            {
                return await EnsureJarCliAsync(release);
            }

            return await EnsureNativeCliAsync(release, nativeUri);
        }
        catch
        {
            if (await ExistingCliStatusAsync() is { } existing)
            {
                return existing;
            }
            throw;
        }
    }

    public async Task<List<string>> ListTemplatesAsync()
    {
        if (commandPrefix is null)
        {
            return [];
        }

        var result = await runner.RunAsync([.. commandPrefix, "templates", "list"], TimeSpan.FromSeconds(20));
        if (!result.Successful)
        {
            return [];
        }

        return result.Output
            .Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(line => line.TrimStart('-').Trim())
            .Select(line => line.Split(':', 2)[0].Trim())
            .Where(line => line.Length > 0 && !line.StartsWith("A new version", StringComparison.OrdinalIgnoreCase) && !line.StartsWith("WARNING", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private async Task<CliStatus> EnsureNativeCliAsync(GitHubRelease release, Uri nativeUri)
    {
        var prefix = new[] { paths.CliExecutable };
        var installed = File.Exists(paths.CliExecutable) ? await InstalledVersionAsync(prefix) : null;
        if (File.Exists(paths.CliExecutable) && VersionsEqual(installed, release.TagName))
        {
            commandPrefix = prefix;
            return new CliStatus
            {
                ExecutablePath = paths.CliExecutable,
                CommandPrefix = prefix,
                Version = installed,
                LatestVersion = release.TagName,
                Message = "Fluxzero Launchpad is up to date"
            };
        }

        await DownloadAsync(nativeUri, paths.CliExecutable);
        commandPrefix = prefix;
        return new CliStatus
        {
            ExecutablePath = paths.CliExecutable,
            CommandPrefix = prefix,
            Version = await InstalledVersionAsync(prefix) ?? release.TagName,
            LatestVersion = release.TagName,
            Updated = true,
            Message = $"Downloaded Fluxzero CLI {release.TagName}."
        };
    }

    private async Task<CliStatus> EnsureJarCliAsync(GitHubRelease release)
    {
        var java = await dependencies.EnsureJava25Async();
        var javaExecutable = Path.Combine(java.HomePath!, "bin", "java.exe");
        var prefix = new[] { javaExecutable, "-jar", paths.CliJar };
        var installed = File.Exists(paths.CliJar) ? await InstalledVersionAsync(prefix) : null;
        if (File.Exists(paths.CliJar) && VersionsEqual(installed, release.TagName))
        {
            commandPrefix = prefix;
            return new CliStatus
            {
                ExecutablePath = paths.CliJar,
                CommandPrefix = prefix,
                Version = installed,
                LatestVersion = release.TagName,
                Message = "Fluxzero Launchpad is up to date"
            };
        }

        await DownloadAsync(release.JarDownloadUri(), paths.CliJar);
        commandPrefix = prefix;
        return new CliStatus
        {
            ExecutablePath = paths.CliJar,
            CommandPrefix = prefix,
            Version = await InstalledVersionAsync(prefix) ?? release.TagName,
            LatestVersion = release.TagName,
            Updated = true,
            Message = $"Downloaded Fluxzero CLI {release.TagName}."
        };
    }

    private async Task<CliStatus?> ExistingCliStatusAsync()
    {
        if (File.Exists(paths.CliJar))
        {
            var java = await dependencies.EnsureJava25Async();
            var prefix = new[] { Path.Combine(java.HomePath!, "bin", "java.exe"), "-jar", paths.CliJar };
            commandPrefix = prefix;
            return new CliStatus
            {
                ExecutablePath = paths.CliJar,
                CommandPrefix = prefix,
                Version = await InstalledVersionAsync(prefix),
                Message = "Using installed CLI; latest version check failed."
            };
        }

        if (RuntimeInformation.ProcessArchitecture == Architecture.Arm64)
        {
            return null;
        }

        if (File.Exists(paths.CliExecutable))
        {
            var prefix = new[] { paths.CliExecutable };
            commandPrefix = prefix;
            return new CliStatus
            {
                ExecutablePath = paths.CliExecutable,
                CommandPrefix = prefix,
                Version = await InstalledVersionAsync(prefix),
                Message = "Using installed CLI; latest version check failed."
            };
        }

        return null;
    }

    private async Task<string?> InstalledVersionAsync(IReadOnlyList<string> prefix)
    {
        var result = await runner.RunAsync([.. prefix, "version"], TimeSpan.FromSeconds(15));
        if (!result.Successful)
        {
            return null;
        }

        return result.Output
            .Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .LastOrDefault(line => System.Text.RegularExpressions.Regex.IsMatch(line, @"^v?\d+\.\d+\.\d+(?:[-.A-Za-z0-9]+)?$"));
    }

    private async Task<GitHubRelease> FetchLatestReleaseAsync()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, LatestReleaseUri);
        request.Headers.UserAgent.ParseAdd("FluxzeroLaunchpad/0.1");
        var response = await http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        return await JsonSerializer.DeserializeAsync<GitHubRelease>(await response.Content.ReadAsStreamAsync())
            ?? throw new InvalidOperationException("Could not parse the latest Fluxzero CLI release.");
    }

    private async Task DownloadAsync(Uri uri, string target)
    {
        var temp = $"{target}.tmp";
        if (File.Exists(temp))
        {
            File.Delete(temp);
        }
        await using (var stream = await http.GetStreamAsync(uri))
        await using (var file = File.Create(temp))
        {
            await stream.CopyToAsync(file);
        }
        if (File.Exists(target))
        {
            File.Delete(target);
        }
        File.Move(temp, target);
    }

    private static bool VersionsEqual(string? left, string? right) =>
        !string.IsNullOrWhiteSpace(left)
        && !string.IsNullOrWhiteSpace(right)
        && left.TrimStart('v').Equals(right.TrimStart('v'), StringComparison.OrdinalIgnoreCase);
}

public sealed class ProjectRegistry(string registryFile)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public IReadOnlyList<GeneratedProject> ListProjects()
    {
        if (!File.Exists(registryFile))
        {
            return [];
        }
        var state = JsonSerializer.Deserialize<RegistryState>(File.ReadAllText(registryFile), JsonOptions);
        return (state?.Projects ?? [])
            .OrderByDescending(project => project.GeneratedAt)
            .ToList();
    }

    public void SaveProject(GeneratedProject project)
    {
        var projects = new[] { project }
            .Concat(ListProjects().Where(existing => existing.Path != project.Path))
            .OrderByDescending(existing => existing.GeneratedAt)
            .ToList();
        Directory.CreateDirectory(Path.GetDirectoryName(registryFile)!);
        File.WriteAllText(registryFile, JsonSerializer.Serialize(new RegistryState { Projects = projects }, JsonOptions));
    }

    public void RemoveProject(GeneratedProject project)
    {
        var projects = ListProjects()
            .Where(existing => existing.Path != project.Path)
            .ToList();
        Directory.CreateDirectory(Path.GetDirectoryName(registryFile)!);
        File.WriteAllText(registryFile, JsonSerializer.Serialize(new RegistryState { Projects = projects }, JsonOptions));
    }
}

public sealed class PromptWriter
{
    public const string FileName = "START_PROMPT.md";

    public string Write(string projectDir, GenerateProjectRequest request, string? sdkVersion)
    {
        var prompt = string.IsNullOrWhiteSpace(request.FirstPrompt)
            ? DefaultPrompt(request, sdkVersion)
            : request.FirstPrompt;
        var path = Path.Combine(projectDir, FileName);
        File.WriteAllText(path, prompt);
        return path;
    }

    private static string DefaultPrompt(GenerateProjectRequest request, string? sdkVersion)
    {
        var lines = new List<string>
        {
            "You are working in a freshly generated Fluxzero project.",
            "",
            "Project context:",
            $"- Name: {request.Name}",
            $"- Template: {request.Template}",
            $"- Build system: {request.BuildSystem}",
            $"- Package: {request.PackageName}"
        };
        if (!string.IsNullOrWhiteSpace(sdkVersion))
        {
            lines.Add($"- Detected Fluxzero SDK: {sdkVersion}");
        }
        lines.Add("");
        lines.Add("Please inspect the project and continue building from here.");
        return string.Join(Environment.NewLine, lines);
    }
}

public sealed class ProjectGenerator(IReadOnlyList<string> cliCommandPrefix, ProjectRegistry registry, CommandRunner? runner = null, PromptWriter? promptWriter = null)
{
    private readonly CommandRunner runner = runner ?? new CommandRunner();
    private readonly PromptWriter promptWriter = promptWriter ?? new PromptWriter();

    public async Task<GeneratedProject> GenerateAsync(GenerateProjectRequest request, string? cliVersion)
    {
        var normalizedName = ProjectNameNormalizer.Normalize(request.Name);
        if (string.IsNullOrWhiteSpace(normalizedName))
        {
            throw new InvalidOperationException("Project name must contain at least one letter or number.");
        }

        Directory.CreateDirectory(request.OutputBaseDir);
        var projectDir = Path.Combine(request.OutputBaseDir, normalizedName);
        var result = await runner.RunAsync(BuildCommand(request), TimeSpan.FromMinutes(10));
        if (!result.Successful)
        {
            throw new InvalidOperationException(
                $"Fluxzero CLI failed with exit code {result.ExitCode}."
                + $"{Environment.NewLine}{Environment.NewLine}Command:{Environment.NewLine}{CommandRunner.FormatCommand(result.Command)}"
                + $"{Environment.NewLine}{Environment.NewLine}Output:{Environment.NewLine}{OutputOrFallback(result.Output)}");
        }
        if (!Directory.Exists(projectDir) || result.Output.Split(Environment.NewLine).Any(line => line.TrimStart().StartsWith("Error:", StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException(
                $"Fluxzero CLI did not generate the expected project directory."
                + $"{Environment.NewLine}{Environment.NewLine}Expected:{Environment.NewLine}{projectDir}"
                + $"{Environment.NewLine}{Environment.NewLine}Command:{Environment.NewLine}{CommandRunner.FormatCommand(result.Command)}"
                + $"{Environment.NewLine}{Environment.NewLine}Output:{Environment.NewLine}{OutputOrFallback(result.Output)}");
        }

        var sdkVersion = SdkVersionDetector.Detect(projectDir);
        var promptPath = promptWriter.Write(projectDir, request, sdkVersion);
        var project = new GeneratedProject
        {
            Id = StableProjectId(projectDir),
            Name = normalizedName,
            Path = projectDir,
            Template = request.Template,
            BuildSystem = request.BuildSystem.ToString().ToLowerInvariant(),
            PackageName = request.PackageName,
            GeneratedAt = DateTimeOffset.UtcNow.ToString("O"),
            CliVersion = cliVersion,
            SdkVersion = sdkVersion,
            PromptPath = promptPath
        };
        registry.SaveProject(project);
        return project;
    }

    private IReadOnlyList<string> BuildCommand(GenerateProjectRequest request)
    {
        var command = new List<string>(cliCommandPrefix)
        {
            "init",
            "--template", request.Template,
            "--name", request.Name,
            "--dir", request.OutputBaseDir,
            "--package", request.PackageName,
            "--build", request.BuildSystem == BuildSystem.Gradle ? "gradle" : "maven"
        };
        if (!string.IsNullOrWhiteSpace(request.GroupId))
        {
            command.AddRange(["--group-id", request.GroupId]);
        }
        if (!string.IsNullOrWhiteSpace(request.ArtifactId))
        {
            command.AddRange(["--artifact-id", request.ArtifactId]);
        }
        if (!string.IsNullOrWhiteSpace(request.Description))
        {
            command.AddRange(["--description", request.Description]);
        }
        if (request.InitGit)
        {
            command.Add("--git");
        }
        return command;
    }

    private static string OutputOrFallback(string output) =>
        string.IsNullOrWhiteSpace(output) ? "(no output captured)" : output.Trim();

    private static string StableProjectId(string path)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(path)));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }
}

public static class SdkVersionDetector
{
    public static string? Detect(string projectDir)
    {
        foreach (var candidate in new[] { "pom.xml", "build.gradle.kts", "build.gradle" })
        {
            var path = Path.Combine(projectDir, candidate);
            if (!File.Exists(path))
            {
                continue;
            }
            var text = File.ReadAllText(path);
            foreach (var pattern in new[]
                     {
                         @"<fluxzero.version>([^<]+)</fluxzero.version>",
                         @"fluxzeroVersion\s*=\s*[""']([^""']+)[""']",
                         @"io\.fluxzero[^:""']*[:""]([0-9][^""'\s)]*)"
                     })
            {
                var match = System.Text.RegularExpressions.Regex.Match(text, pattern);
                if (match.Success)
                {
                    return match.Groups[1].Value;
                }
            }
        }
        return null;
    }
}

public sealed class AgentLauncher
{
    public AgentLaunchResult Launch(AgentChoice choice, string projectPath, string prompt)
    {
        switch (choice)
        {
            case AgentChoice.None:
                return new AgentLaunchResult();
            case AgentChoice.Explorer:
                return OpenFolder(projectPath);
            case AgentChoice.Codex:
                return LaunchCodex(projectPath, prompt);
            case AgentChoice.Claude:
                OpenUri(ClaudeDeepLink(projectPath, prompt));
                return new AgentLaunchResult { OpenedClaude = true };
            case AgentChoice.Cursor:
                return LaunchCursor(projectPath, prompt);
        }

        return new AgentLaunchResult();
    }

    public AgentLaunchResult OpenFolder(string path)
    {
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        return new AgentLaunchResult { OpenedExplorer = true };
    }

    private AgentLaunchResult LaunchCodex(string projectPath, string prompt)
    {
        if (!IsCodexInstalled())
        {
            OpenUri(new Uri("https://openai.com/codex/"));
            return new AgentLaunchResult { OpenedCodexDownload = true };
        }
        OpenUri(CodexDeepLink(projectPath, prompt));
        return new AgentLaunchResult { OpenedCodex = true };
    }

    private AgentLaunchResult LaunchCursor(string projectPath, string _)
    {
        if (TryOpenUri(CursorFileDeepLink(projectPath)))
        {
            return new AgentLaunchResult { OpenedCursor = true };
        }

        if (FindExecutable("cursor.exe") is { } cursor)
        {
            var startInfo = new ProcessStartInfo(cursor)
            {
                UseShellExecute = false,
                CreateNoWindow = true
            };
            startInfo.ArgumentList.Add(projectPath);
            Process.Start(startInfo);
            return new AgentLaunchResult { OpenedCursor = true };
        }

        if (CursorExecutable() is { } appPath)
        {
            var startInfo = new ProcessStartInfo(appPath)
            {
                UseShellExecute = false,
                CreateNoWindow = true
            };
            startInfo.ArgumentList.Add(projectPath);
            Process.Start(startInfo);
            return new AgentLaunchResult { OpenedCursor = true };
        }

        OpenUri(new Uri("https://www.cursor.com/downloads"));
        return new AgentLaunchResult { OpenedCursorDownload = true };
    }

    private static Uri CodexDeepLink(string projectPath, string prompt) =>
        new($"codex://new?path={Uri.EscapeDataString(projectPath)}&prompt={Uri.EscapeDataString(BlankFallback(prompt))}");

    private static Uri ClaudeDeepLink(string projectPath, string prompt) =>
        new($"claude-cli://open?cwd={Uri.EscapeDataString(projectPath)}&q={Uri.EscapeDataString(BlankFallback(prompt))}");

    private static Uri CursorFileDeepLink(string projectPath) =>
        new($"cursor://file{CursorFilePath(projectPath)}");

    private static string CursorFilePath(string projectPath)
    {
        var normalizedPath = Path.GetFullPath(projectPath).Replace('\\', '/');
        if (!normalizedPath.StartsWith("/", StringComparison.Ordinal))
        {
            normalizedPath = $"/{normalizedPath}";
        }

        return string.Join("/", normalizedPath.Split('/').Select(CursorPathSegment));
    }

    private static string CursorPathSegment(string segment) =>
        Uri.EscapeDataString(segment).Replace("%3A", ":", StringComparison.OrdinalIgnoreCase);

    private static bool TryOpenUri(Uri uri)
    {
        try
        {
            OpenUri(uri);
            return true;
        }
        catch (Exception error) when (error is Win32Exception or InvalidOperationException)
        {
            return false;
        }
    }

    private static void OpenUri(Uri uri) => Process.Start(new ProcessStartInfo(uri.ToString()) { UseShellExecute = true });

    private static string BlankFallback(string prompt) =>
        string.IsNullOrWhiteSpace(prompt) ? "Inspect this Fluxzero project and help me continue from here." : prompt;

    private static bool IsCodexInstalled() =>
        FindExecutable("codex.exe") is not null
        || File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Codex", "Codex.exe"))
        || File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Codex", "Codex.exe"));

    private static string? CursorExecutable() =>
        new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Cursor", "Cursor.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Cursor", "Cursor.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Cursor", "Cursor.exe")
        }
        .FirstOrDefault(File.Exists);

    private static string? FindExecutable(string name)
    {
        return (Environment.GetEnvironmentVariable("PATH") ?? "")
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(path => Path.Combine(path, name))
            .FirstOrDefault(File.Exists);
    }
}

public sealed class AgentLaunchResult
{
    public bool OpenedExplorer { get; init; }
    public bool OpenedCodex { get; init; }
    public bool OpenedCodexDownload { get; init; }
    public bool OpenedClaude { get; init; }
    public bool OpenedCursor { get; init; }
    public bool OpenedCursorDownload { get; init; }

    public string StatusMessage => this switch
    {
        { OpenedCodexDownload: true } => "Codex download page opened.",
        { OpenedCursorDownload: true } => "Cursor download page opened.",
        { OpenedCodex: true } => "Opened project in Codex.",
        { OpenedClaude: true } => "Opened project in Claude Code.",
        { OpenedCursor: true } => "Opened project in Cursor.",
        { OpenedExplorer: true } => "Opened project in File Explorer.",
        _ => "Project generated."
    };
}

public sealed class DeepLinkActionRunner(CliRuntimeService cliRuntime, ProjectRegistry registry, AgentLauncher agentLauncher, DevelopmentDependencyService dependencies)
{
    public async Task<string> RunAsync(FluxzeroDirectLink link)
    {
        if (link.Command == "open" && !string.IsNullOrWhiteSpace(link.Path))
        {
            return agentLauncher.Launch(link.AgentChoice, link.Path, link.Prompt ?? "").StatusMessage;
        }

        if (link.Command == "create")
        {
            await dependencies.EnsureJava25Async();
            var name = string.IsNullOrWhiteSpace(link.Name) ? throw new InvalidOperationException("Project name is required.") : link.Name;
            var location = string.IsNullOrWhiteSpace(link.Location) ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "FluxzeroProjects") : link.Location;
            var normalized = ProjectNameNormalizer.Normalize(name);
            var projectDir = Path.Combine(location, normalized);
            if (Directory.Exists(projectDir) && Directory.EnumerateFileSystemEntries(projectDir).Any())
            {
                var prompt = link.Prompt ?? TryReadStartPrompt(projectDir) ?? "";
                return agentLauncher.Launch(link.AgentChoice, projectDir, prompt).StatusMessage;
            }

            var artifact = string.IsNullOrWhiteSpace(link.ArtifactId) ? ProjectNameNormalizer.Normalize(name) : link.ArtifactId;
            var group = string.IsNullOrWhiteSpace(link.GroupId) ? "com.example" : link.GroupId;
            var template = string.IsNullOrWhiteSpace(link.Template) ? "flux-basic-java" : link.Template;
            var request = new GenerateProjectRequest
            {
                Name = name,
                Template = template,
                OutputBaseDir = location,
                GroupId = group,
                ArtifactId = artifact,
                PackageName = string.IsNullOrWhiteSpace(link.PackageName) ? $"{group}.{ProjectNameNormalizer.PackageSuffix(artifact)}" : link.PackageName,
                Description = string.IsNullOrWhiteSpace(link.Description) ? "A Fluxzero application" : link.Description,
                BuildSystem = link.BuildSystem ?? (template.Contains("kotlin", StringComparison.OrdinalIgnoreCase) ? BuildSystem.Gradle : BuildSystem.Maven),
                InitGit = link.InitGit && dependencies.IsGitAvailable(),
                FirstPrompt = link.Prompt ?? "",
                AgentChoice = link.AgentChoice
            };
            var status = await cliRuntime.EnsureLatestCliAsync();
            var generator = new ProjectGenerator(status.CommandPrefix, registry);
            var project = await generator.GenerateAsync(request, status.Version);
            var projectPrompt = project.PromptPath is null ? request.FirstPrompt : await File.ReadAllTextAsync(project.PromptPath);
            return agentLauncher.Launch(link.AgentChoice, project.Path, projectPrompt).StatusMessage;
        }

        return "No Fluxzero action was run.";
    }

    private static string? TryReadStartPrompt(string projectDir)
    {
        var path = Path.Combine(projectDir, PromptWriter.FileName);
        return File.Exists(path) ? File.ReadAllText(path) : null;
    }
}

public static class ProtocolRegistrationService
{
    public static void Register()
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
        {
            return;
        }

        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Classes\fluxzero");
        key?.SetValue("", "URL:Fluxzero Launchpad");
        key?.SetValue("URL Protocol", "");
        using var command = Registry.CurrentUser.CreateSubKey(@"Software\Classes\fluxzero\shell\open\command");
        command?.SetValue("", $"\"{executable}\" \"%1\"");
    }
}

public static class ClipboardHelper
{
    public static DataPackage Text(string value)
    {
        var package = new DataPackage();
        package.SetText(value);
        return package;
    }
}
