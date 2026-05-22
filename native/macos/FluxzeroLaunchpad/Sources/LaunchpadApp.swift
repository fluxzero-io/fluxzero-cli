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
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Refresh Fluxzero CLI") {
                    model.refresh()
                }
                .keyboardShortcut("r", modifiers: [.command])
            }
        }
    }
}

struct LaunchpadView: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 240, ideal: 280, max: 320)
        } detail: {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    HeaderView()
                    GeneratorPanel()
                    RecentProjectsPanel()
                }
                .padding(28)
            }
            .background(LaunchpadBackdrop())
        }
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
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Fluxzero")
                        .font(.headline)
                    Text("Launchpad")
                        .foregroundStyle(.secondary)
                }
            }

            Divider()

            Label("Create", systemImage: "wand.and.stars")
                .font(.headline)
            Label("Projects", systemImage: "folder")
                .foregroundStyle(.secondary)
            Label("Upgrades", systemImage: "arrow.triangle.2.circlepath")
                .foregroundStyle(.secondary)

            Spacer()

            VStack(alignment: .leading, spacing: 6) {
                Text(model.statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if model.isBusy {
                    ProgressView()
                        .controlSize(.small)
                }
            }
        }
        .padding(20)
        .background(.regularMaterial)
    }
}

struct HeaderView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Fluxzero Launchpad")
                .font(.largeTitle.weight(.semibold))
            Text("Generate local Fluxzero projects and move straight into your coding agent.")
                .foregroundStyle(.secondary)
        }
    }
}

struct GeneratorPanel: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Project")
                .font(.title2.weight(.semibold))

            TextField("Project name", text: Binding(
                get: { model.projectName },
                set: { model.setProjectName($0) }
            ))
            .textFieldStyle(.roundedBorder)

            TextEditor(text: $model.prompt)
                .font(.body)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 130)
                .padding(10)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if model.prompt.isEmpty {
                        Text("Describe what you want to build")
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 18)
                            .allowsHitTesting(false)
                    }
                }

            HStack(spacing: 12) {
                Button {
                    model.createAndOpen(agent: .codex)
                } label: {
                    Label("Open in Codex", systemImage: "sparkles")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button {
                    model.createAndOpen(agent: .claude)
                } label: {
                    Label("Open in Claude Code", systemImage: "terminal")
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Spacer()

                Button("Create only") {
                    model.createOnly()
                }
                .buttonStyle(.link)
            }
            .disabled(model.isBusy || model.projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            DisclosureGroup(isExpanded: $model.advancedExpanded) {
                AdvancedOptions()
                    .padding(.top, 10)
            } label: {
                Label("Advanced options", systemImage: "slider.horizontal.3")
                    .font(.headline)
            }
        }
        .padding(22)
        .launchpadGlass(cornerRadius: 24)
    }
}

struct AdvancedOptions: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 12) {
            GridRow {
                Text("Location")
                    .foregroundStyle(.secondary)
                HStack {
                    TextField("Location", text: $model.location)
                    Button("Choose") {
                        model.chooseLocation()
                    }
                }
            }
            GridRow {
                Text("Template")
                    .foregroundStyle(.secondary)
                Picker("Template", selection: $model.template) {
                    ForEach(model.templates, id: \.self) {
                        Text($0).tag($0)
                    }
                }
            }
            GridRow {
                Text("Build")
                    .foregroundStyle(.secondary)
                Picker("Build", selection: $model.buildSystem) {
                    ForEach(DesktopBuildSystem.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .pickerStyle(.segmented)
            }
            GridRow {
                Text("Group ID")
                    .foregroundStyle(.secondary)
                TextField("Group ID", text: $model.groupId)
            }
            GridRow {
                Text("Artifact ID")
                    .foregroundStyle(.secondary)
                TextField("Artifact ID", text: $model.artifactId)
            }
            GridRow {
                Text("Package")
                    .foregroundStyle(.secondary)
                TextField("Package", text: $model.packageName)
            }
            GridRow {
                Text("Git")
                    .foregroundStyle(.secondary)
                Toggle("Initialize repository", isOn: $model.initGit)
            }
        }
        .textFieldStyle(.roundedBorder)
    }
}

struct RecentProjectsPanel: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Recent projects")
                    .font(.title2.weight(.semibold))
                Spacer()
                Button {
                    model.refresh()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }

            if model.projects.isEmpty {
                ContentUnavailableView("No projects yet", systemImage: "folder.badge.plus")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            } else {
                ForEach(model.projects.prefix(8)) { project in
                    ProjectRow(project: project)
                }
            }
        }
        .padding(22)
        .launchpadGlass(cornerRadius: 24)
    }
}

struct ProjectRow: View {
    @EnvironmentObject private var model: LaunchpadModel
    let project: GeneratedProject

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder.fill")
                .foregroundStyle(.blue)
                .font(.title2)
            VStack(alignment: .leading, spacing: 3) {
                Text(project.name)
                    .font(.headline)
                Text(project.path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                model.openProject(project, agent: .codex)
            } label: {
                Image(systemName: "sparkles")
            }
            .help("Open in Codex")
            Button {
                model.openProject(project, agent: .claude)
            } label: {
                Image(systemName: "terminal")
            }
            .help("Open in Claude Code")
            Button {
                model.openFolder(project)
            } label: {
                Image(systemName: "folder")
            }
            .help("Open folder")
            Button {
                model.copyPrompt(project)
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .help("Copy prompt")
        }
        .padding(12)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct LaunchpadBackdrop: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(nsColor: .windowBackgroundColor),
                    Color.accentColor.opacity(0.12),
                    Color(nsColor: .controlBackgroundColor)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
        }
    }
}

extension View {
    @ViewBuilder
    func launchpadGlass(cornerRadius: CGFloat) -> some View {
        if #available(macOS 26.0, *) {
            self
                .glassEffect()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            self
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}
