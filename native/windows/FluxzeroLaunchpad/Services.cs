using System.Diagnostics;
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
            RegistryFile = Path.Combine(root, "projects.json")
        };
    }
}

public sealed class CommandRunner
{
    public async Task<CommandResult> RunAsync(IReadOnlyList<string> command, TimeSpan? timeout = null)
    {
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

        process.Start();
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
            return new CommandResult { ExitCode = -1, Output = $"Command timed out: {string.Join(" ", command)}" };
        }

        return new CommandResult { ExitCode = process.ExitCode, Output = output.ToString() };
    }
}

public sealed class GitHubRelease
{
    [JsonPropertyName("tag_name")]
    public string TagName { get; set; } = "";

    [JsonPropertyName("assets")]
    public List<ReleaseAsset> Assets { get; set; } = [];

    public Uri DownloadUri()
    {
        const string assetName = "flux-windows-amd64.exe";
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

public sealed class CliRuntimeService(AppPaths paths, CommandRunner? runner = null)
{
    private static readonly Uri LatestReleaseUri = new("https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases/latest");
    private readonly CommandRunner runner = runner ?? new CommandRunner();
    private readonly HttpClient http = new();

    public async Task<CliStatus> EnsureLatestCliAsync()
    {
        Directory.CreateDirectory(paths.BinDir);
        var installed = await InstalledVersionAsync();

        try
        {
            var release = await FetchLatestReleaseAsync();
            if (File.Exists(paths.CliExecutable) && VersionsEqual(installed, release.TagName))
            {
                return new CliStatus
                {
                    ExecutablePath = paths.CliExecutable,
                    Version = installed,
                    LatestVersion = release.TagName,
                    Message = "Fluxzero CLI is up to date."
                };
            }

            await DownloadAsync(release.DownloadUri(), paths.CliExecutable);
            return new CliStatus
            {
                ExecutablePath = paths.CliExecutable,
                Version = await InstalledVersionAsync() ?? release.TagName,
                LatestVersion = release.TagName,
                Updated = true,
                Message = $"Downloaded Fluxzero CLI {release.TagName}."
            };
        }
        catch
        {
            if (File.Exists(paths.CliExecutable))
            {
                return new CliStatus
                {
                    ExecutablePath = paths.CliExecutable,
                    Version = installed,
                    Message = "Using installed CLI; latest version check failed."
                };
            }
            throw;
        }
    }

    public async Task<List<string>> ListTemplatesAsync()
    {
        if (!File.Exists(paths.CliExecutable))
        {
            return [];
        }

        var result = await runner.RunAsync([paths.CliExecutable, "templates", "list"], TimeSpan.FromSeconds(20));
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

    private async Task<string?> InstalledVersionAsync()
    {
        if (!File.Exists(paths.CliExecutable))
        {
            return null;
        }

        var result = await runner.RunAsync([paths.CliExecutable, "version"], TimeSpan.FromSeconds(15));
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

public sealed class ProjectGenerator(string cliExecutable, ProjectRegistry registry, CommandRunner? runner = null, PromptWriter? promptWriter = null)
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
            throw new InvalidOperationException($"Fluxzero CLI failed.{Environment.NewLine}{result.Output}");
        }
        if (!Directory.Exists(projectDir) || result.Output.Split(Environment.NewLine).Any(line => line.TrimStart().StartsWith("Error:", StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException($"Fluxzero CLI did not generate the expected project directory.{Environment.NewLine}{result.Output}");
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
        var command = new List<string>
        {
            cliExecutable,
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

    private static string StableProjectId(string path)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(path)));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }
}

public static class ProjectNameNormalizer
{
    public static string Normalize(string name)
    {
        var cleaned = System.Text.RegularExpressions.Regex.Replace(name.ToLowerInvariant(), @"[^a-z0-9\s_-]", "");
        var dashed = System.Text.RegularExpressions.Regex.Replace(cleaned, @"[\s_]+", "-");
        return System.Text.RegularExpressions.Regex.Replace(dashed, "-+", "-").Trim('-');
    }

    public static string PackageSuffix(string artifact)
    {
        var suffix = System.Text.RegularExpressions.Regex.Replace(artifact.ToLowerInvariant(), @"[^a-z0-9]", "");
        return string.IsNullOrWhiteSpace(suffix) ? "app" : suffix;
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
    public void Launch(AgentChoice choice, string projectPath, string prompt)
    {
        switch (choice)
        {
            case AgentChoice.None:
                return;
            case AgentChoice.Codex:
                LaunchCodex(projectPath, prompt);
                return;
            case AgentChoice.Claude:
                OpenUri(ClaudeDeepLink(projectPath, prompt));
                return;
            case AgentChoice.Both:
                LaunchCodex(projectPath, prompt);
                OpenUri(ClaudeDeepLink(projectPath, prompt));
                return;
        }
    }

    public void OpenFolder(string path) => Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });

    private void LaunchCodex(string projectPath, string prompt)
    {
        if (!IsCodexInstalled())
        {
            OpenUri(new Uri("https://openai.com/codex/"));
            return;
        }
        OpenUri(CodexDeepLink(projectPath, prompt));
    }

    private static Uri CodexDeepLink(string projectPath, string prompt) =>
        new($"codex://new?path={Uri.EscapeDataString(projectPath)}&prompt={Uri.EscapeDataString(BlankFallback(prompt))}");

    private static Uri ClaudeDeepLink(string projectPath, string prompt) =>
        new($"claude-cli://open?cwd={Uri.EscapeDataString(projectPath)}&q={Uri.EscapeDataString(BlankFallback(prompt))}");

    private static void OpenUri(Uri uri) => Process.Start(new ProcessStartInfo(uri.ToString()) { UseShellExecute = true });

    private static string BlankFallback(string prompt) =>
        string.IsNullOrWhiteSpace(prompt) ? "Open START_PROMPT.md and help me continue from there." : prompt;

    private static bool IsCodexInstalled() =>
        FindExecutable("codex.exe") is not null
        || File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Codex", "Codex.exe"))
        || File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Codex", "Codex.exe"));

    private static string? FindExecutable(string name)
    {
        return (Environment.GetEnvironmentVariable("PATH") ?? "")
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(path => Path.Combine(path, name))
            .FirstOrDefault(File.Exists);
    }
}

public sealed class DeepLinkActionRunner(AppPaths paths, CliRuntimeService cliRuntime, ProjectRegistry registry, AgentLauncher agentLauncher)
{
    public async Task RunAsync(FluxzeroDirectLink link)
    {
        if (link.Command == "open" && !string.IsNullOrWhiteSpace(link.Path))
        {
            agentLauncher.Launch(link.AgentChoice, link.Path, link.Prompt ?? "");
            return;
        }

        if (link.Command == "create")
        {
            var name = string.IsNullOrWhiteSpace(link.Name) ? throw new InvalidOperationException("Project name is required.") : link.Name;
            var location = string.IsNullOrWhiteSpace(link.Location) ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "FluxzeroProjects") : link.Location;
            var normalized = ProjectNameNormalizer.Normalize(name);
            var projectDir = Path.Combine(location, normalized);
            if (Directory.Exists(projectDir) && Directory.EnumerateFileSystemEntries(projectDir).Any())
            {
                var prompt = link.Prompt ?? TryReadStartPrompt(projectDir) ?? "";
                agentLauncher.Launch(link.AgentChoice, projectDir, prompt);
                return;
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
                InitGit = link.InitGit,
                FirstPrompt = link.Prompt ?? "",
                AgentChoice = link.AgentChoice
            };
            var status = await cliRuntime.EnsureLatestCliAsync();
            var generator = new ProjectGenerator(status.ExecutablePath, registry);
            var project = await generator.GenerateAsync(request, status.Version);
            var projectPrompt = project.PromptPath is null ? request.FirstPrompt : await File.ReadAllTextAsync(project.PromptPath);
            agentLauncher.Launch(link.AgentChoice, project.Path, projectPrompt);
        }
    }

    private static string? TryReadStartPrompt(string projectDir)
    {
        var path = Path.Combine(projectDir, PromptWriter.FileName);
        return File.Exists(path) ? File.ReadAllText(path) : null;
    }
}

public static class DeepLinkParser
{
    public static FluxzeroDeepLink? Parse(Uri uri)
    {
        if (!uri.Scheme.Equals("fluxzero", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        var command = uri.Host.ToLowerInvariant();
        var parameters = ParseQuery(uri.Query);
        if (command == "new")
        {
            return new FluxzeroDeepLink
            {
                NewProject = new FluxzeroNewProjectLink
                {
                    Name = Get(parameters, "name"),
                    Prompt = Get(parameters, "prompt"),
                    Template = Get(parameters, "template"),
                    Location = Get(parameters, "location") ?? Get(parameters, "path"),
                    AgentChoice = ParseAgent(Get(parameters, "agent"))
                }
            };
        }
        if (command is "open" or "codex" or "claude" or "claude-code")
        {
            return new FluxzeroDeepLink
            {
                Direct = new FluxzeroDirectLink
                {
                    Command = "open",
                    Path = Get(parameters, "path") ?? Get(parameters, "location"),
                    Prompt = Get(parameters, "prompt"),
                    AgentChoice = command switch
                    {
                        "codex" => AgentChoice.Codex,
                        "claude" or "claude-code" => AgentChoice.Claude,
                        _ => ParseAgent(Get(parameters, "agent")) ?? AgentChoice.Codex
                    }
                }
            };
        }
        if (command == "create")
        {
            return new FluxzeroDeepLink
            {
                Direct = new FluxzeroDirectLink
                {
                    Command = "create",
                    Name = Get(parameters, "name"),
                    Template = Get(parameters, "template"),
                    Location = Get(parameters, "location") ?? Get(parameters, "path"),
                    GroupId = Get(parameters, "groupId") ?? Get(parameters, "group"),
                    ArtifactId = Get(parameters, "artifactId") ?? Get(parameters, "artifact"),
                    PackageName = Get(parameters, "packageName") ?? Get(parameters, "package"),
                    Description = Get(parameters, "description"),
                    BuildSystem = ParseBuild(Get(parameters, "build") ?? Get(parameters, "buildSystem")),
                    InitGit = ParseBool(Get(parameters, "git")) ?? true,
                    Prompt = Get(parameters, "prompt"),
                    AgentChoice = ParseAgent(Get(parameters, "agent")) ?? AgentChoice.Codex
                }
            };
        }
        return null;
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        return query.TrimStart('?')
            .Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .ToDictionary(
                pair => Uri.UnescapeDataString(pair[0]),
                pair => pair.Length > 1 ? Uri.UnescapeDataString(pair[1].Replace("+", " ")) : "",
                StringComparer.OrdinalIgnoreCase);
    }

    private static string? Get(Dictionary<string, string> parameters, string key) =>
        parameters.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value : null;

    private static AgentChoice? ParseAgent(string? value) => value?.ToLowerInvariant().Replace("_", "-") switch
    {
        "codex" => AgentChoice.Codex,
        "claude" or "claude-code" => AgentChoice.Claude,
        "both" or "all" => AgentChoice.Both,
        "none" or "generate" => AgentChoice.None,
        _ => null
    };

    private static BuildSystem? ParseBuild(string? value) => value?.ToLowerInvariant() switch
    {
        "maven" or "mvn" => BuildSystem.Maven,
        "gradle" => BuildSystem.Gradle,
        _ => null
    };

    private static bool? ParseBool(string? value) => value?.ToLowerInvariant() switch
    {
        "1" or "true" or "yes" or "y" => true,
        "0" or "false" or "no" or "n" => false,
        _ => null
    };
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
