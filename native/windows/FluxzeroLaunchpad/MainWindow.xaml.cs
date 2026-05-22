using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Fluxzero.Launchpad;

public sealed partial class MainWindow : Window
{
    private readonly AppPaths paths = AppPaths.Detect();
    private readonly AgentLauncher agentLauncher = new();
    private readonly ProjectRegistry registry;
    private readonly CliRuntimeService cliRuntime;
    private CliStatus? cliStatus;

    public MainWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
        registry = new ProjectRegistry(paths.RegistryFile);
        cliRuntime = new CliRuntimeService(paths);
        ProtocolRegistrationService.Register();

        LocationBox.Text = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "FluxzeroProjects");
        TemplateBox.ItemsSource = new[] { "flux-basic-java", "flux-basic-kotlin", "gamerental" };
        TemplateBox.SelectedIndex = 0;
        RefreshProjects();
    }

    public async Task PrepareAsync()
    {
        await SetBusyAsync(true);
        try
        {
            cliStatus = await cliRuntime.EnsureLatestCliAsync();
            var templates = await cliRuntime.ListTemplatesAsync();
            if (templates.Count > 0)
            {
                TemplateBox.ItemsSource = templates;
                TemplateBox.SelectedIndex = 0;
            }
            CliFootnote.Text = $"Using {cliStatus.ExecutablePath} ({cliStatus.Version ?? "unknown version"})";
            StatusText.Text = cliStatus.Message;
            RefreshProjects();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Using local project history.";
            await ShowErrorAsync(ex.Message);
            RefreshProjects();
        }
        finally
        {
            await SetBusyAsync(false);
        }
    }

    public async Task HandleDeepLinkAsync(Uri uri)
    {
        var link = DeepLinkParser.Parse(uri);
        if (link is null)
        {
            return;
        }

        if (link.NewProject is not null)
        {
            Apply(link.NewProject);
            return;
        }

        if (link.Direct is not null)
        {
            try
            {
                var runner = new DeepLinkActionRunner(paths, cliRuntime, registry, agentLauncher);
                await runner.RunAsync(link.Direct);
                RefreshProjects();
            }
            catch (Exception ex)
            {
                await ShowErrorAsync(ex.Message);
            }
        }
    }

    private async void RefreshButton_Click(object sender, RoutedEventArgs e) => await PrepareAsync();

    private async void OpenCodexButton_Click(object sender, RoutedEventArgs e) => await CreateAndOpenAsync(AgentChoice.Codex);

    private async void OpenClaudeButton_Click(object sender, RoutedEventArgs e) => await CreateAndOpenAsync(AgentChoice.Claude);

    private async void CreateOnlyButton_Click(object sender, RoutedEventArgs e) => await CreateAndOpenAsync(AgentChoice.None);

    private async void ChooseLocationButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        if (folder is not null)
        {
            LocationBox.Text = folder.Path;
        }
    }

    private void ProjectNameBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        var artifact = ProjectNameNormalizer.Normalize(ProjectNameBox.Text);
        ArtifactBox.Text = artifact;
        var suffix = ProjectNameNormalizer.PackageSuffix(artifact);
        PackageBox.Text = $"{(string.IsNullOrWhiteSpace(GroupBox.Text) ? "com.example" : GroupBox.Text)}.{suffix}";
    }

    private async void OpenProjectCodex_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is GeneratedProject project)
        {
            await OpenProjectAsync(project, AgentChoice.Codex);
        }
    }

    private async void OpenProjectClaude_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is GeneratedProject project)
        {
            await OpenProjectAsync(project, AgentChoice.Claude);
        }
    }

    private void OpenProjectFolder_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is GeneratedProject project)
        {
            agentLauncher.OpenFolder(project.Path);
        }
    }

    private void CopyPrompt_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is GeneratedProject project)
        {
            var prompt = project.PromptPath is null ? "" : File.ReadAllText(project.PromptPath);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(ClipboardHelper.Text(prompt));
            StatusText.Text = "Prompt copied.";
        }
    }

    private async Task CreateAndOpenAsync(AgentChoice agent)
    {
        if (cliStatus is null)
        {
            await ShowErrorAsync("Fluxzero CLI is not ready yet.");
            return;
        }

        await SetBusyAsync(true);
        try
        {
            var request = MakeRequest(agent);
            var generator = new ProjectGenerator(cliStatus.ExecutablePath, registry);
            var project = await generator.GenerateAsync(request, cliStatus.Version);
            RefreshProjects();
            StatusText.Text = $"Created {project.Name}.";
            if (agent != AgentChoice.None)
            {
                var prompt = project.PromptPath is null ? request.FirstPrompt : await File.ReadAllTextAsync(project.PromptPath);
                agentLauncher.Launch(agent, project.Path, prompt);
            }
        }
        catch (Exception ex)
        {
            await ShowErrorAsync(ex.Message);
        }
        finally
        {
            await SetBusyAsync(false);
        }
    }

    private async Task OpenProjectAsync(GeneratedProject project, AgentChoice agent)
    {
        try
        {
            var prompt = project.PromptPath is null ? "" : await File.ReadAllTextAsync(project.PromptPath);
            agentLauncher.Launch(agent, project.Path, prompt);
        }
        catch (Exception ex)
        {
            await ShowErrorAsync(ex.Message);
        }
    }

    private GenerateProjectRequest MakeRequest(AgentChoice agent)
    {
        var artifact = string.IsNullOrWhiteSpace(ArtifactBox.Text)
            ? ProjectNameNormalizer.Normalize(ProjectNameBox.Text)
            : ArtifactBox.Text;
        var group = string.IsNullOrWhiteSpace(GroupBox.Text) ? "com.example" : GroupBox.Text;
        var packageName = string.IsNullOrWhiteSpace(PackageBox.Text)
            ? $"{group}.{ProjectNameNormalizer.PackageSuffix(artifact)}"
            : PackageBox.Text;
        var build = (BuildBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() == "gradle"
            ? BuildSystem.Gradle
            : BuildSystem.Maven;

        return new GenerateProjectRequest
        {
            Template = TemplateBox.SelectedItem?.ToString() ?? "flux-basic-java",
            Name = ProjectNameBox.Text,
            OutputBaseDir = LocationBox.Text,
            PackageName = packageName,
            GroupId = group,
            ArtifactId = string.IsNullOrWhiteSpace(artifact) ? "app" : artifact,
            Description = "A Fluxzero application",
            BuildSystem = build,
            InitGit = GitBox.IsChecked != false,
            FirstPrompt = PromptBox.Text,
            AgentChoice = agent
        };
    }

    private void Apply(FluxzeroNewProjectLink link)
    {
        if (!string.IsNullOrWhiteSpace(link.Name))
        {
            ProjectNameBox.Text = link.Name;
        }
        if (!string.IsNullOrWhiteSpace(link.Prompt))
        {
            PromptBox.Text = link.Prompt;
        }
        if (!string.IsNullOrWhiteSpace(link.Template))
        {
            TemplateBox.SelectedItem = link.Template;
        }
        if (!string.IsNullOrWhiteSpace(link.Location))
        {
            LocationBox.Text = link.Location;
        }
    }

    private void RefreshProjects()
    {
        ProjectsList.ItemsSource = registry.ListProjects();
    }

    private async Task SetBusyAsync(bool busy)
    {
        BusyRing.IsActive = busy;
        OpenCodexButton.IsEnabled = !busy;
        OpenClaudeButton.IsEnabled = !busy;
        await Task.CompletedTask;
    }

    private async Task ShowErrorAsync(string message)
    {
        var dialog = new ContentDialog
        {
            Title = "Fluxzero Launchpad",
            Content = message,
            CloseButtonText = "OK",
            XamlRoot = ((FrameworkElement)Content).XamlRoot
        };
        await dialog.ShowAsync();
    }
}
