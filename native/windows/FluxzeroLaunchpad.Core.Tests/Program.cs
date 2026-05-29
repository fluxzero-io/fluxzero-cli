using Fluxzero.Launchpad;

var tests = new (string Name, Action Run)[]
{
    ("create defaults to File Explorer", CreateDefaultsToExplorer),
    ("new link becomes direct create with defaults", NewLinkUsesDefaults),
    ("agent aliases map to destinations", AgentAliases),
    ("project names normalize for folders and packages", ProjectNameNormalization)
};

foreach (var test in tests)
{
    test.Run();
    Console.WriteLine($"PASS {test.Name}");
}

static void CreateDefaultsToExplorer()
{
    var link = DeepLinkParser.Parse(new Uri("fluxzero://create?name=Hello%20World&prompt=build+this"));
    Equal("create", link?.Direct?.Command);
    Equal("Hello World", link?.Direct?.Name);
    Equal(AgentChoice.Explorer, link?.Direct?.AgentChoice);
    Equal("build this", link?.Direct?.Prompt);
}

static void NewLinkUsesDefaults()
{
    var defaults = new ProjectCreationDefaults
    {
        Location = @"C:\FluxzeroProjects",
        Template = "flux-basic-kotlin",
        GroupId = "io.fluxzero.demo",
        BuildSystem = BuildSystem.Gradle,
        InitGit = false,
        AgentChoice = AgentChoice.Codex
    };
    var link = DeepLinkParser.Parse(new Uri("fluxzero://new?name=Demo&prompt=go"))?.NewProject;
    var direct = link?.AsDirectCreate(defaults);
    Equal("create", direct?.Command);
    Equal(@"C:\FluxzeroProjects", direct?.Location);
    Equal("flux-basic-kotlin", direct?.Template);
    Equal(AgentChoice.Codex, direct?.AgentChoice);
    Equal(false, direct?.InitGit);
}

static void AgentAliases()
{
    Equal(AgentChoice.Codex, DeepLinkParser.ParseAgent("codex"));
    Equal(AgentChoice.Claude, DeepLinkParser.ParseAgent("claude-code"));
    Equal(AgentChoice.Cursor, DeepLinkParser.ParseAgent("cursor"));
    Equal(AgentChoice.Explorer, DeepLinkParser.ParseAgent("finder"));
    Equal(AgentChoice.Explorer, DeepLinkParser.ParseAgent("file-explorer"));
    Equal(AgentChoice.None, DeepLinkParser.ParseAgent("dont-open"));
}

static void ProjectNameNormalization()
{
    Equal("hello-world", ProjectNameNormalizer.Normalize("Hello World!"));
    Equal("helloworld", ProjectNameNormalizer.PackageSuffix("hello-world"));
    Equal("app", ProjectNameNormalizer.PackageSuffix("---"));
}

static void Equal<T>(T expected, T actual)
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
    {
        throw new InvalidOperationException($"Expected {expected}, got {actual}.");
    }
}
