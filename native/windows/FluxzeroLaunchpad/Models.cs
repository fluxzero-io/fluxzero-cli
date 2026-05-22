namespace Fluxzero.Launchpad;

public enum BuildSystem
{
    Maven,
    Gradle
}

public enum AgentChoice
{
    None,
    Codex,
    Claude,
    Both
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
    public string Template { get; set; } = "";
    public string BuildSystem { get; set; } = "";
    public string PackageName { get; set; } = "";
    public string GeneratedAt { get; set; } = "";
    public string? CliVersion { get; set; }
    public string? SdkVersion { get; set; }
    public string? PromptPath { get; set; }
}

public sealed class RegistryState
{
    public List<GeneratedProject> Projects { get; set; } = [];
}

public sealed class CliStatus
{
    public string ExecutablePath { get; init; } = "";
    public string? Version { get; init; }
    public string? LatestVersion { get; init; }
    public bool Updated { get; init; }
    public string Message { get; init; } = "";
}

public sealed class CommandResult
{
    public int ExitCode { get; init; }
    public string Output { get; init; } = "";
    public bool Successful => ExitCode == 0;
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
}

public sealed class FluxzeroDeepLink
{
    public FluxzeroNewProjectLink? NewProject { get; init; }
    public FluxzeroDirectLink? Direct { get; init; }
}
