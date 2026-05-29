using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Fluxzero.Launchpad;

public sealed partial class SettingsWindow : Window
{
    private readonly ProjectCreationDefaultsStore store;
    private readonly Action saved;
    private bool loading;

    public SettingsWindow(ProjectCreationDefaultsStore store, Action saved, IReadOnlyList<string> templates, bool isGitAvailable)
    {
        this.store = store;
        this.saved = saved;
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
        ConfigureWindow();
        AgentComboBoxItems.Populate(AgentBox);
        ShowSettings(store.Load(), templates, isGitAvailable);
    }

    public void ShowSettings(ProjectCreationDefaults settings, IReadOnlyList<string> templates, bool isGitAvailable)
    {
        loading = true;
        try
        {
            TemplateBox.ItemsSource = templates.Count > 0 ? templates : new[] { "flux-basic-java" };
            LocationBox.Text = settings.Location;
            SelectComboValue(TemplateBox, settings.Template);
            BuildBox.SelectedIndex = settings.BuildSystem == BuildSystem.Gradle ? 1 : 0;
            GroupBox.Text = settings.GroupId;
            GitBox.Visibility = isGitAvailable ? Visibility.Visible : Visibility.Collapsed;
            GitBox.IsChecked = settings.InitGit && isGitAvailable;
            SelectAgent(settings.AgentChoice);
        }
        finally
        {
            loading = false;
        }

        Activate();
    }

    private async void ChooseLocationButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        if (folder is not null)
        {
            LocationBox.Text = folder.Path;
            Save();
        }
    }

    private void Setting_Changed(object sender, RoutedEventArgs e) => Save();

    private void Setting_Changed(object sender, SelectionChangedEventArgs e) => Save();

    private void Setting_Changed(object sender, TextChangedEventArgs e) => Save();

    private void Save()
    {
        if (loading)
        {
            return;
        }

        var build = (BuildBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() == "gradle"
            ? BuildSystem.Gradle
            : BuildSystem.Maven;
        var settings = new ProjectCreationDefaults
        {
            Location = string.IsNullOrWhiteSpace(LocationBox.Text) ? ProjectCreationDefaults.Fallback.Location : LocationBox.Text,
            Template = TemplateBox.SelectedItem?.ToString() ?? "flux-basic-java",
            GroupId = string.IsNullOrWhiteSpace(GroupBox.Text) ? "com.example" : GroupBox.Text,
            BuildSystem = build,
            InitGit = GitBox.IsChecked == true,
            AgentChoice = AgentComboBoxItems.SelectedChoice(AgentBox, AgentChoice.Codex)
        };
        store.Save(settings);
        saved();
    }

    private void SelectAgent(AgentChoice agent)
    {
        AgentComboBoxItems.Select(AgentBox, agent);
    }

    private static void SelectComboValue(ComboBox comboBox, string value)
    {
        var match = comboBox.Items.Cast<object>().FirstOrDefault(item => item.ToString()?.Equals(value, StringComparison.OrdinalIgnoreCase) == true);
        if (match is not null)
        {
            comboBox.SelectedItem = match;
        }
        else if (comboBox.Items.Count > 0)
        {
            comboBox.SelectedIndex = 0;
        }
    }

    private void ConfigureWindow()
    {
        var icon = Path.Combine(AppContext.BaseDirectory, "Assets", "fluxzero.ico");
        if (File.Exists(icon))
        {
            AppWindow.SetIcon(icon);
        }
    }
}
