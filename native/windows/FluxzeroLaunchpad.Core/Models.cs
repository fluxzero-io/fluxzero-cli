using System.Text.Json.Serialization;

namespace Fluxzero.Launchpad;

public enum BuildSystem
{
    Maven,
    Gradle
}

public enum AgentChoice
{
    None,
    Explorer,
    Codex,
    Claude,
    Cursor
}

public static class AgentChoiceExtensions
{
    public static IReadOnlyList<AgentChoice> OpenDestinations { get; } =
    [
        AgentChoice.Codex,
        AgentChoice.Claude,
        AgentChoice.Cursor,
        AgentChoice.Explorer,
        AgentChoice.None
    ];

    public static string Label(this AgentChoice choice) => choice switch
    {
        AgentChoice.None => "Don't open",
        AgentChoice.Explorer => "File Explorer",
        AgentChoice.Codex => "Codex",
        AgentChoice.Claude => "Claude Code",
        AgentChoice.Cursor => "Cursor",
        _ => choice.ToString()
    };

    public static string ActionTitle(this AgentChoice choice) =>
        choice == AgentChoice.None ? "Generate" : "Open";
}

public sealed class GenerateProjectRequest
{
    public string Template { get; init; } = "flux-basic-java";
    public string Name { get; init; } = "";
    public string OutputBaseDir { get; init; } = "";
    public string PackageName { get; init; } = "com.example.app";
    public string GroupId { get; init; } = "com.example";
    public string ArtifactId { get; init; } = "app";
    public string Description { get; init; } = "A Fluxzero application";
    public BuildSystem BuildSystem { get; init; } = BuildSystem.Maven;
    public bool InitGit { get; init; } = true;
    public string FirstPrompt { get; init; } = "";
    public AgentChoice AgentChoice { get; init; } = AgentChoice.Codex;
}

public sealed class GeneratedProject
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Path { get; set; } = "";
    [JsonIgnore]
    public string DisplayPath => MiddleEllipsize(Path, 38);
    public string Template { get; set; } = "";
    public string BuildSystem { get; set; } = "";
    public string PackageName { get; set; } = "";
    public string GeneratedAt { get; set; } = "";
    public string? CliVersion { get; set; }
    public string? SdkVersion { get; set; }
    public string? PromptPath { get; set; }

    private static string MiddleEllipsize(string value, int maxLength)
    {
        if (value.Length <= maxLength)
        {
            return value;
        }

        const string ellipsis = "...";
        var availableLength = maxLength - ellipsis.Length;
        var leftLength = Math.Max(1, availableLength / 2);
        var rightLength = Math.Max(1, availableLength - leftLength);
        return string.Concat(value.AsSpan(0, leftLength), ellipsis, value.AsSpan(value.Length - rightLength));
    }
}

public sealed class RegistryState
{
    public List<GeneratedProject> Projects { get; set; } = [];
}

public sealed class CliStatus
{
    public string ExecutablePath { get; init; } = "";
    public IReadOnlyList<string> CommandPrefix { get; init; } = [];
    public string? Version { get; init; }
    public string? LatestVersion { get; init; }
    public bool Updated { get; init; }
    public string Message { get; init; } = "";
}

public sealed class CommandResult
{
    public IReadOnlyList<string> Command { get; init; } = [];
    public int ExitCode { get; init; }
    public string Output { get; init; } = "";
    public bool Successful => ExitCode == 0;
}

public sealed class JavaRuntimeStatus
{
    public string? HomePath { get; init; }
    public JavaRuntimeSource Source { get; init; }
    public string? Version { get; init; }
    public bool IsReady => !string.IsNullOrWhiteSpace(HomePath);

    public static JavaRuntimeStatus Missing { get; } = new()
    {
        Source = JavaRuntimeSource.Missing
    };
}

public enum JavaRuntimeSource
{
    System,
    Managed,
    Missing
}

public sealed class ProjectCreationDefaults
{
    public string Location { get; init; } = DefaultLocation();
    public string Template { get; init; } = "flux-basic-java";
    public string GroupId { get; init; } = "com.example";
    public BuildSystem BuildSystem { get; init; } = BuildSystem.Maven;
    public bool InitGit { get; init; } = true;
    public AgentChoice AgentChoice { get; init; } = AgentChoice.Codex;

    public static ProjectCreationDefaults Fallback { get; } = new();

    public ProjectCreationDefaults With(
        string? location = null,
        string? template = null,
        string? groupId = null,
        BuildSystem? buildSystem = null,
        bool? initGit = null,
        AgentChoice? agentChoice = null) =>
        new()
        {
            Location = string.IsNullOrWhiteSpace(location) ? Location : location,
            Template = string.IsNullOrWhiteSpace(template) ? Template : template,
            GroupId = string.IsNullOrWhiteSpace(groupId) ? GroupId : groupId,
            BuildSystem = buildSystem ?? BuildSystem,
            InitGit = initGit ?? InitGit,
            AgentChoice = agentChoice ?? this.AgentChoice
        };

    private static string DefaultLocation() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "FluxzeroProjects");
}

public sealed class FluxzeroNewProjectLink
{
    public string? Name { get; init; }
    public string? Prompt { get; init; }
    public string? Template { get; init; }
    public string? Location { get; init; }
    public AgentChoice? AgentChoice { get; init; }
}

public sealed class FluxzeroDirectLink
{
    public string Command { get; init; } = "";
    public string? Path { get; init; }
    public string? Name { get; init; }
    public string? Prompt { get; init; }
    public string? Template { get; init; }
    public string? Location { get; init; }
    public string? GroupId { get; init; }
    public string? ArtifactId { get; init; }
    public string? PackageName { get; init; }
    public string? Description { get; init; }
    public BuildSystem? BuildSystem { get; init; }
    public bool InitGit { get; init; } = true;
    public AgentChoice AgentChoice { get; init; } = AgentChoice.Codex;
    public bool IsCreateRequest => Command.Equals("create", StringComparison.OrdinalIgnoreCase);
}

public sealed class FluxzeroDeepLink
{
    public FluxzeroNewProjectLink? NewProject { get; init; }
    public FluxzeroDirectLink? Direct { get; init; }
}

public enum DeepLinkPresentationMode
{
    Interactive,
    Background
}
