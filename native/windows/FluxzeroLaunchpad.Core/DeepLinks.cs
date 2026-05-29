namespace Fluxzero.Launchpad;

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

        if (command is "open" or "codex" or "claude" or "claude-code" or "cursor" or "finder" or "folder" or "explorer")
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
                        "cursor" => AgentChoice.Cursor,
                        "finder" or "folder" or "explorer" => AgentChoice.Explorer,
                        _ => ParseAgent(Get(parameters, "agent")) ?? AgentChoice.Explorer
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
                    AgentChoice = ParseAgent(Get(parameters, "agent")) ?? AgentChoice.Explorer
                }
            };
        }

        return null;
    }

    public static FluxzeroDirectLink AsDirectCreate(this FluxzeroNewProjectLink link, ProjectCreationDefaults defaults) =>
        new()
        {
            Command = "create",
            Name = link.Name,
            Template = string.IsNullOrWhiteSpace(link.Template) ? defaults.Template : link.Template,
            Location = string.IsNullOrWhiteSpace(link.Location) ? defaults.Location : link.Location,
            GroupId = defaults.GroupId,
            Description = "A Fluxzero application",
            BuildSystem = defaults.BuildSystem,
            InitGit = defaults.InitGit,
            Prompt = link.Prompt,
            AgentChoice = link.AgentChoice ?? defaults.AgentChoice
        };

    public static AgentChoice? ParseAgent(string? value) =>
        value?.ToLowerInvariant().Replace("_", "-") switch
        {
            "codex" => AgentChoice.Codex,
            "claude" or "claude-code" => AgentChoice.Claude,
            "cursor" => AgentChoice.Cursor,
            "finder" or "folder" or "open-folder" or "reveal" or "explorer" or "file-explorer" => AgentChoice.Explorer,
            "none" or "generate" or "dont-open" or "don't-open" or "no-open" => AgentChoice.None,
            _ => null
        };

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
