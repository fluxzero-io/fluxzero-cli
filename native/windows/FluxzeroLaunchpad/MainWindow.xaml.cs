using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.VisualBasic.FileIO;
using Windows.Graphics;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Fluxzero.Launchpad;

public sealed partial class MainWindow : Window
{
    private const int MinWindowWidth = 820;
    private const int MinWindowHeight = 620;
    private readonly AppPaths paths = AppPaths.Detect();
    private readonly AgentLauncher agentLauncher = new();
    private readonly ProjectRegistry registry;
    private readonly CliRuntimeService cliRuntime;
    private readonly ProjectCreationDefaultsStore defaultsStore;
    private readonly DevelopmentDependencyService dependencies;
    private CliStatus? cliStatus;
    private ProjectCreationDefaults creationDefaults = ProjectCreationDefaults.Fallback;
    private SettingsWindow? settingsWindow;
    private AboutWindow? aboutWindow;
    private bool isGitAvailable = true;
    private bool isWindowVisible;
    private bool allowClose;
    private bool isBusy;

    public event Action<bool>? BusyChanged;
    public event Action<string>? StatusChanged;
    public event Action<string>? ErrorOccurred;

    public MainWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
        registry = new ProjectRegistry(paths.RegistryFile);
        defaultsStore = new ProjectCreationDefaultsStore(paths);
        dependencies = new DevelopmentDependencyService(paths);
        cliRuntime = new CliRuntimeService(paths, dependencies);
        ProtocolRegistrationService.Register();
        ConfigureWindow();

        AppWindow.Closing += Window_Closing;
        AppWindow.Changed += Window_Changed;
        TemplateBox.ItemsSource = new[] { "flux-basic-java", "flux-basic-kotlin", "gamerental" };
        BuildBox.SelectedIndex = 0;
        AgentComboBoxItems.Populate(OpenDestinationBox);
        LoadDefaults();
        RefreshProjects();
        UpdatePrimaryAction();
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
                SelectComboValue(TemplateBox, creationDefaults.Template);
            }

            isGitAvailable = dependencies.IsGitAvailable();
            UpdateGitVisibility();
            SetStatus(cliStatus.Message);
            RefreshProjects();
        }
        catch (Exception ex)
        {
            SetStatus("Using local project history.");
            await ShowErrorAsync(ex.Message);
            RefreshProjects();
        }
        finally
        {
            await SetBusyAsync(false);
        }
    }

    public void ShowCreateWindow()
    {
        isWindowVisible = true;
        Activate();
    }

    public void ShowSettingsWindow()
    {
        settingsWindow ??= new SettingsWindow(defaultsStore, LoadDefaults, Templates, isGitAvailable);
        settingsWindow.ShowSettings(creationDefaults, Templates, isGitAvailable);
    }

    public void ShowAboutWindow()
    {
        aboutWindow ??= CreateAboutWindow();
        aboutWindow.ShowAbout(cliStatus?.Version);
    }

    private AboutWindow CreateAboutWindow()
    {
        var window = new AboutWindow();
        window.Closed += (_, _) =>
        {
            if (aboutWindow == window)
            {
                aboutWindow = null;
            }
        };
        return window;
    }

    public void AllowCloseForQuit()
    {
        allowClose = true;
        settingsWindow?.Close();
        aboutWindow?.Close();
    }

    public async Task HandleDeepLinkAsync(Uri uri, DeepLinkPresentationMode presentationMode = DeepLinkPresentationMode.Background)
    {
        var link = DeepLinkParser.Parse(uri);
        if (link is null)
        {
            return;
        }

        if (link.NewProject is not null)
        {
            if (presentationMode == DeepLinkPresentationMode.Background)
            {
                await RunDirectLinkAsync(link.NewProject.AsDirectCreate(creationDefaults));
                return;
            }

            Apply(link.NewProject);
            ShowCreateWindow();
            return;
        }

        if (link.Direct is not null)
        {
            await RunDirectLinkAsync(link.Direct);
        }
    }

    private async void RefreshButton_Click(object sender, RoutedEventArgs e) => await PrepareAsync();

    private async void PrimaryActionButton_Click(object sender, RoutedEventArgs e) => await CreateAndOpenAsync(SelectedAgent());

    private void OpenDestinationBox_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdatePrimaryAction();

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
        UpdateDerivedIdentifiers();
        UpdatePrimaryAction();
    }

    private void UpdateDerivedIdentifiers()
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

    private async void OpenProjectCursor_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is GeneratedProject project)
        {
            await OpenProjectAsync(project, AgentChoice.Cursor);
        }
    }

    private async void DeleteProject_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not GeneratedProject project)
        {
            return;
        }

        var dialog = new ContentDialog
        {
            Title = "Delete project?",
            Content = $"This moves \"{project.Name}\" to the Recycle Bin and removes it from recent projects.",
            PrimaryButtonText = "Move to Recycle Bin",
            SecondaryButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = ((FrameworkElement)Content).XamlRoot
        };
        var result = await dialog.ShowAsync();
        if (result != ContentDialogResult.Primary)
        {
            return;
        }

        try
        {
            if (Directory.Exists(project.Path))
            {
                FileSystem.DeleteDirectory(project.Path, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin);
            }
            registry.RemoveProject(project);
            RefreshProjects();
            SetStatus($"Moved {project.Name} to Recycle Bin.");
        }
        catch (Exception ex)
        {
            await ShowErrorAsync(ex.Message);
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
            SetStatus("Prompt copied.");
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
            SetStatus("Preparing Java 25...");
            await dependencies.EnsureJava25Async();
            SetStatus("Creating project...");
            var request = MakeRequest(agent);
            var generator = new ProjectGenerator(cliStatus.CommandPrefix, registry);
            var project = await generator.GenerateAsync(request, cliStatus.Version);
            RefreshProjects();
            var message = $"Created {project.Name}.";
            if (agent != AgentChoice.None)
            {
                var prompt = project.PromptPath is null ? request.FirstPrompt : await File.ReadAllTextAsync(project.PromptPath);
                message = agentLauncher.Launch(agent, project.Path, prompt).StatusMessage;
            }
            SetStatus(message);
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
            SetStatus(agentLauncher.Launch(agent, project.Path, prompt).StatusMessage);
        }
        catch (Exception ex)
        {
            await ShowErrorAsync(ex.Message);
        }
    }

    private async Task RunDirectLinkAsync(FluxzeroDirectLink direct)
    {
        await SetBusyAsync(true);
        try
        {
            SetStatus(direct.IsCreateRequest ? "Creating project..." : "Opening project...");
            var runner = new DeepLinkActionRunner(cliRuntime, registry, agentLauncher, dependencies);
            var message = await runner.RunAsync(direct);
            RefreshProjects();
            SetStatus(message);
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
            InitGit = GitBox.IsChecked == true && isGitAvailable,
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
            SelectComboValue(TemplateBox, link.Template);
        }
        if (!string.IsNullOrWhiteSpace(link.Location))
        {
            LocationBox.Text = link.Location;
        }
        if (link.AgentChoice is { } agent)
        {
            SelectAgent(agent);
        }
    }

    private void LoadDefaults()
    {
        creationDefaults = defaultsStore.Load();
        LocationBox.Text = creationDefaults.Location;
        SelectComboValue(TemplateBox, creationDefaults.Template);
        SelectBuild(creationDefaults.BuildSystem);
        GroupBox.Text = creationDefaults.GroupId;
        GitBox.IsChecked = creationDefaults.InitGit && isGitAvailable;
        SelectAgent(creationDefaults.AgentChoice);
        UpdateDerivedIdentifiers();
    }

    private IReadOnlyList<string> Templates =>
        TemplateBox.Items.Cast<object>().Select(item => item.ToString() ?? "").Where(item => item.Length > 0).ToList();

    private void SelectBuild(BuildSystem buildSystem)
    {
        BuildBox.SelectedIndex = buildSystem == BuildSystem.Gradle ? 1 : 0;
    }

    private AgentChoice SelectedAgent() =>
        AgentComboBoxItems.SelectedChoice(OpenDestinationBox, creationDefaults.AgentChoice);

    private void SelectAgent(AgentChoice agent)
    {
        AgentComboBoxItems.Select(OpenDestinationBox, agent);
        UpdatePrimaryAction();
    }

    private static void SelectComboValue(ComboBox comboBox, string value)
    {
        var match = comboBox.Items.Cast<object>().FirstOrDefault(item => item.ToString()?.Equals(value, StringComparison.OrdinalIgnoreCase) == true);
        if (match is not null)
        {
            comboBox.SelectedItem = match;
        }
        else if (comboBox.Items.Count > 0 && comboBox.SelectedIndex < 0)
        {
            comboBox.SelectedIndex = 0;
        }
    }

    private void UpdateGitVisibility()
    {
        var visibility = isGitAvailable ? Visibility.Visible : Visibility.Collapsed;
        GitLabel.Visibility = visibility;
        GitBox.Visibility = visibility;
        GitBox.IsChecked = isGitAvailable && GitBox.IsChecked == true;
    }

    private void RefreshProjects()
    {
        ProjectsList.ItemsSource = registry.ListProjects();
    }

    private async Task SetBusyAsync(bool busy)
    {
        isBusy = busy;
        BusyRing.IsActive = busy;
        BusyRing.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        BusyChanged?.Invoke(busy);
        UpdatePrimaryAction();
        await Task.CompletedTask;
    }

    private void SetStatus(string message)
    {
        StatusText.Text = message;
        StatusChanged?.Invoke(message);
    }

    private void UpdatePrimaryAction()
    {
        var agent = SelectedAgent();
        PrimaryActionButton.Content = agent.ActionTitle();
        PrimaryActionButton.IsEnabled = !isBusy && !string.IsNullOrWhiteSpace(ProjectNameBox.Text);
    }

    private async Task ShowErrorAsync(string message)
    {
        ErrorOccurred?.Invoke(message);
        if (!isWindowVisible)
        {
            SetStatus("Could not complete the Fluxzero action.");
            return;
        }

        var dialog = new ContentDialog
        {
            Title = "Fluxzero Launchpad",
            Content = message,
            CloseButtonText = "OK",
            XamlRoot = ((FrameworkElement)Content).XamlRoot
        };
        await dialog.ShowAsync();
    }

    private void Window_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (allowClose)
        {
            return;
        }

        args.Cancel = true;
        isWindowVisible = false;
        NativeWindow.ShowWindow(WindowNative.GetWindowHandle(this), NativeWindow.SwHide);
    }

    private void Window_Changed(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (!args.DidSizeChange)
        {
            return;
        }

        var width = Math.Max(sender.Size.Width, MinWindowWidth);
        var height = Math.Max(sender.Size.Height, MinWindowHeight);
        if (width != sender.Size.Width || height != sender.Size.Height)
        {
            sender.Resize(new SizeInt32(width, height));
        }
    }

    private void ConfigureWindow()
    {
        var icon = Path.Combine(AppContext.BaseDirectory, "Assets", "fluxzero.ico");
        if (File.Exists(icon))
        {
            AppWindow.SetIcon(icon);
        }
        AppWindow.Resize(new SizeInt32(900, 720));
    }
}

internal static class AgentComboBoxItems
{
    public static void Populate(ComboBox comboBox)
    {
        comboBox.Items.Clear();
        foreach (var choice in AgentChoiceExtensions.OpenDestinations)
        {
            comboBox.Items.Add(Create(choice));
        }
    }

    public static AgentChoice SelectedChoice(ComboBox comboBox, AgentChoice fallback) =>
        (comboBox.SelectedItem as ComboBoxItem)?.Tag is AgentChoice choice ? choice : fallback;

    public static void Select(ComboBox comboBox, AgentChoice choice)
    {
        var selected = comboBox.Items
            .Cast<ComboBoxItem>()
            .FirstOrDefault(item => item.Tag is AgentChoice itemChoice && itemChoice == choice)
            ?? comboBox.Items
                .Cast<ComboBoxItem>()
                .FirstOrDefault(item => item.Tag is AgentChoice itemChoice && itemChoice == AgentChoice.Codex);
        comboBox.SelectedItem = selected;
    }

    private static ComboBoxItem Create(AgentChoice choice)
    {
        var row = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8
        };
        row.Children.Add(CreateIcon(choice));
        row.Children.Add(new TextBlock { Text = choice.Label(), VerticalAlignment = VerticalAlignment.Center });
        return new ComboBoxItem
        {
            Tag = choice,
            Content = row
        };
    }

    private static UIElement CreateIcon(AgentChoice choice)
    {
        var asset = choice switch
        {
            AgentChoice.Codex => "ms-appx:///Assets/CodexIcon.svg",
            AgentChoice.Claude => "ms-appx:///Assets/ClaudeCodeMark.svg",
            AgentChoice.Cursor => "ms-appx:///Assets/CursorCube.svg",
            _ => null
        };
        if (asset is not null)
        {
            var image = new Image
            {
                Width = 16,
                Height = 16,
                Stretch = Stretch.Uniform
            };
            if (asset.EndsWith(".svg", StringComparison.OrdinalIgnoreCase))
            {
                image.Source = new SvgImageSource(new Uri(asset));
            }
            else
            {
                image.Source = new BitmapImage(new Uri(asset));
            }
            return image;
        }

        return new FontIcon
        {
            Glyph = choice == AgentChoice.Explorer ? "\uE8B7" : "\uE711",
            Width = 16,
            Height = 16,
            FontSize = 14
        };
    }
}

internal static class NativeWindow
{
    public const int SwHide = 0;

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
