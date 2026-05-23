import AppKit
import SwiftUI

@main
struct FluxzeroLaunchpadApp: App {
    @StateObject private var model = LaunchpadModel()

    var body: some Scene {
        WindowGroup("Fluxzero Launchpad") {
            LaunchpadView()
                .environmentObject(model)
                .frame(minWidth: 980, minHeight: 680)
                .task {
                    await model.prepare()
                }
                .onOpenURL { url in
                    model.handle(url: url)
                }
        }
        .commands {
            CommandGroup(replacing: .appInfo) {
                Button("About Fluxzero Launchpad") {
                    showAboutPanel()
                }
            }
            CommandGroup(after: .newItem) {
                Button("Refresh Fluxzero CLI") {
                    model.refresh()
                }
                .keyboardShortcut("r", modifiers: [.command])
            }
        }
    }

    private func showAboutPanel() {
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        let cliVersion = model.cliStatus?.version ?? "not ready"
        NSApp.orderFrontStandardAboutPanel(options: [
            .applicationName: "Fluxzero Launchpad",
            .applicationVersion: appVersion,
            .version: "Fluxzero CLI \(cliVersion)"
        ])
    }
}

struct LaunchpadView: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 220, ideal: 260, max: 300)
        } detail: {
            Group {
                switch model.selectedSection {
                case .create:
                    CreateScreen()
                case .projects:
                    ProjectsScreen()
                case .upgrades:
                    UpgradesScreen()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LaunchpadBackdrop())
        }
        .navigationSplitViewStyle(.balanced)
        .background(Color(nsColor: .textBackgroundColor))
        .alert("Fluxzero Launchpad", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK") {
                model.errorMessage = nil
            }
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

struct SidebarView: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        List(selection: $model.selectedSection) {
            Section {
                ForEach(LaunchpadSection.allCases) { section in
                    Label(section.label, systemImage: section.systemImage)
                        .tag(section)
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

struct CreateScreen: View {
    var body: some View {
        ScrollView {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 28) {
                    CreateMainContent()
                        .frame(width: 700, alignment: .topLeading)
                    RecentProjectsPanel(limit: 5)
                        .frame(width: 340)
                        .padding(.top, 8)
                }
                .frame(maxWidth: 1080, alignment: .center)

                VStack(alignment: .leading, spacing: 24) {
                    CreateMainContent()
                        .frame(maxWidth: 700, alignment: .topLeading)
                    RecentProjectsPanel(limit: 5)
                        .frame(maxWidth: 700)
                }
                .frame(maxWidth: 700, alignment: .center)
            }
            .padding(.horizontal, 34)
            .padding(.vertical, 34)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(nsColor: .textBackgroundColor))
    }
}

struct CreateMainContent: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if model.isBusy || model.cliStatus == nil {
                CliStatusBanner()
            }
            GeneratorPanel()
        }
        .frame(maxWidth: 700, alignment: .topLeading)
    }
}

struct ProjectsScreen: View {
    var body: some View {
        DetailScroll {
            HeaderView(
                title: "Projects",
                subtitle: "Generated Fluxzero projects."
            )
            RecentProjectsPanel(limit: nil)
        }
    }
}

struct UpgradesScreen: View {
    var body: some View {
        DetailScroll {
            HeaderView(
                title: "Upgrades",
                subtitle: "SDK and agent manual upgrades."
            )
            GroupBox {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Upgrade support is coming", systemImage: "arrow.triangle.2.circlepath")
                        .font(.headline)
                    Text("The native app already tracks generated projects. The actual SDK upgrade flow stays disabled until the CLI upgrade contract is ready.")
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

struct DetailScroll<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                content
            }
            .frame(maxWidth: 980, alignment: .leading)
            .padding(.horizontal, 30)
            .padding(.vertical, 28)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(nsColor: .textBackgroundColor))
    }
}

struct HeaderView: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.title.weight(.semibold))
            Text(subtitle)
                .foregroundStyle(.secondary)
        }
    }
}

struct CliStatusBanner: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        GroupBox {
            HStack(spacing: 10) {
                if model.isBusy {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "exclamationmark.circle")
                        .foregroundStyle(.secondary)
                }
                Text(model.isBusy ? "Preparing Fluxzero CLI..." : model.statusMessage)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Spacer()
                Button {
                    model.refresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("Retry")
            }
            .font(.caption)
        }
    }
}

struct GeneratorPanel: View {
    @EnvironmentObject private var model: LaunchpadModel

    private var actionsDisabled: Bool {
        model.isBusy || model.projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            Section("Project") {
                TextField("Name", text: Binding(
                    get: { model.projectName },
                    set: { model.setProjectName($0) }
                ))

                LabeledContent("Description") {
                    PromptTextEditor(
                        text: $model.prompt,
                        prompt: "Describe what you want to build"
                    )
                    .frame(minHeight: 130)
                }

                HStack(spacing: 12) {
                    Button("Create only") {
                        model.createOnly()
                    }
                    .buttonStyle(.link)

                    Spacer()

                    Button {
                        model.createAndOpen(agent: .claude)
                    } label: {
                        Label("Open in Claude Code", systemImage: "terminal")
                    }

                    Button {
                        model.createAndOpen(agent: .codex)
                    } label: {
                        Label("Open in Codex", systemImage: "sparkles")
                    }
                    .buttonStyle(.borderedProminent)
                }
                .disabled(actionsDisabled)

                DisclosureGroup("Advanced options", isExpanded: $model.advancedExpanded) {
                    AdvancedOptions()
                        .padding(.top, 6)
                }
            }
        }
        .formStyle(.grouped)
        .scrollDisabled(true)
        .frame(maxWidth: 700, alignment: .topLeading)
    }
}

struct AdvancedOptions: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            LabeledContent("Location") {
                HStack {
                    Text(model.location)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Button("Choose") {
                        model.chooseLocation()
                    }
                }
            }

            Picker("Template", selection: $model.template) {
                ForEach(model.templates, id: \.self) {
                    Text($0).tag($0)
                }
            }

            Picker("Build system", selection: $model.buildSystem) {
                ForEach(DesktopBuildSystem.allCases) { option in
                    Text(option.label).tag(option)
                }
            }
            .pickerStyle(.segmented)

            TextField("Group ID", text: $model.groupId)
            TextField("Artifact ID", text: $model.artifactId)
            TextField("Package", text: $model.packageName)

            Toggle("Initialize Git repository", isOn: $model.initGit)
        }
        .controlSize(.regular)
    }
}

struct RecentProjectsPanel: View {
    @EnvironmentObject private var model: LaunchpadModel
    let limit: Int?

    var visibleProjects: ArraySlice<GeneratedProject> {
        if let limit {
            model.projects.prefix(limit)
        } else {
            model.projects[...]
        }
    }

    var body: some View {
        GroupBox {
            if model.projects.isEmpty {
                EmptyProjectsView()
            } else {
                List {
                    ForEach(visibleProjects) { project in
                        ProjectRow(project: project)
                    }
                }
                .listStyle(.inset)
                .frame(minHeight: 180)
            }
        } label: {
            HStack {
                Text("Recent projects")
                Spacer()
                Button {
                    model.refresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("Refresh")
            }
        }
    }
}

struct EmptyProjectsView: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "folder.badge.plus")
                .font(.largeTitle)
                .foregroundStyle(.tertiary)
            Text("No projects yet")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 170)
    }
}

struct ProjectRow: View {
    @EnvironmentObject private var model: LaunchpadModel
    let project: GeneratedProject

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder.fill")
                .foregroundStyle(.blue)
            VStack(alignment: .leading, spacing: 3) {
                Text(project.name)
                Text(project.path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            ProjectActionButton(systemImage: "sparkles", help: "Open in Codex") {
                model.openProject(project, agent: .codex)
            }
            ProjectActionButton(systemImage: "terminal", help: "Open in Claude Code") {
                model.openProject(project, agent: .claude)
            }
            ProjectActionButton(systemImage: "folder", help: "Open folder") {
                model.openFolder(project)
            }
            ProjectActionButton(systemImage: "doc.on.doc", help: "Copy prompt") {
                model.copyPrompt(project)
            }
        }
    }
}

struct ProjectActionButton: View {
    let systemImage: String
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
        }
        .buttonStyle(.borderless)
        .help(help)
    }
}

struct LaunchpadBackdrop: View {
    var body: some View {
        Color(nsColor: .textBackgroundColor)
            .ignoresSafeArea()
    }
}

struct PromptTextEditor: View {
    @Binding var text: String
    let prompt: String

    var body: some View {
        ZStack(alignment: .topLeading) {
            TextEditor(text: $text)
            if text.isEmpty {
                Text(prompt)
                    .foregroundStyle(.tertiary)
                    .padding(.top, 8)
                    .padding(.leading, 6)
                    .allowsHitTesting(false)
            }
        }
    }
}
