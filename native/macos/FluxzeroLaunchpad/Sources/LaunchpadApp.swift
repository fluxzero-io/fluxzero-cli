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
            .background(LaunchpadBackdrop())
        }
        .navigationSplitViewStyle(.balanced)
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
            Section("Fluxzero") {
                ForEach(LaunchpadSection.allCases) { section in
                    Label(section.label, systemImage: section.systemImage)
                        .tag(section)
                }
            }
        }
        .listStyle(.sidebar)
    }
}

struct CreateScreen: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        DetailScroll {
            HeaderView(
                title: "Fluxzero Launchpad",
                subtitle: "Local projects for Codex and Claude Code."
            )
            if model.isBusy || model.cliStatus == nil {
                CliStatusBanner()
            }
            GeneratorPanel()
            RecentProjectsPanel(limit: 5)
        }
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
            VStack(alignment: .leading, spacing: 14) {
                Label("Upgrade support is coming", systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)
                Text("The native app already tracks generated projects. The actual SDK upgrade flow stays disabled until the CLI upgrade contract is ready.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(18)
            .nativePanel()
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
        }
    }
}

struct HeaderView: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.largeTitle.weight(.semibold))
            Text(subtitle)
                .foregroundStyle(.secondary)
        }
    }
}

struct CliStatusBanner: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
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
            .buttonStyle(.borderless)
            .help("Retry")
        }
        .font(.caption)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color(nsColor: .separatorColor).opacity(0.28), lineWidth: 1)
        )
    }
}

struct GeneratorPanel: View {
    @EnvironmentObject private var model: LaunchpadModel

    private var actionsDisabled: Bool {
        model.isBusy || model.projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Project")
                .font(.headline)
                .padding(.bottom, 14)

            SettingsRow(label: "Name") {
                TextField("Project name", text: Binding(
                    get: { model.projectName },
                    set: { model.setProjectName($0) }
                ))
                .textFieldStyle(.roundedBorder)
            }

            SettingsDivider()

            VStack(alignment: .leading, spacing: 8) {
                FieldLabel("Description")
                NativePromptEditor(
                    text: $model.prompt,
                    placeholder: "Describe what you want to build"
                )
                .frame(minHeight: 118)
            }
            .padding(.vertical, 12)

            SettingsDivider()

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
            .disabled(actionsDisabled)
            .padding(.vertical, 12)

            SettingsDivider()

            AdvancedDisclosure()
        }
        .padding(18)
        .nativePanel()
    }
}

struct AdvancedDisclosure: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.16)) {
                    model.advancedExpanded.toggle()
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .rotationEffect(.degrees(model.advancedExpanded ? 90 : 0))
                        .animation(.easeInOut(duration: 0.16), value: model.advancedExpanded)
                    Image(systemName: "slider.horizontal.3")
                    Text("Advanced options")
                        .font(.headline)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.vertical, 12)

            if model.advancedExpanded {
                SettingsDivider()
                AdvancedOptions()
                    .padding(.top, 12)
                    .transition(.opacity)
            }
        }
        .clipped()
        .animation(.easeInOut(duration: 0.16), value: model.advancedExpanded)
    }
}

struct AdvancedOptions: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 12) {
            GridRow {
                FieldLabel("Location")
                HStack {
                    TextField("Location", text: $model.location)
                    Button("Choose") {
                        model.chooseLocation()
                    }
                }
            }
            GridRow {
                FieldLabel("Template")
                Picker("Template", selection: $model.template) {
                    ForEach(model.templates, id: \.self) {
                        Text($0).tag($0)
                    }
                }
                .labelsHidden()
            }
            GridRow {
                FieldLabel("Build")
                Picker("Build", selection: $model.buildSystem) {
                    ForEach(DesktopBuildSystem.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .labelsHidden()
                .pickerStyle(.segmented)
            }
            GridRow {
                FieldLabel("Group ID")
                TextField("Group ID", text: $model.groupId)
            }
            GridRow {
                FieldLabel("Artifact ID")
                TextField("Artifact ID", text: $model.artifactId)
            }
            GridRow {
                FieldLabel("Package")
                TextField("Package", text: $model.packageName)
            }
            GridRow {
                FieldLabel("Git")
                Toggle("Initialize repository", isOn: $model.initGit)
            }
            GridRow {
                FieldLabel("CLI")
                Text(cliFootnote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .textFieldStyle(.roundedBorder)
        .controlSize(.regular)
        .padding(.bottom, 2)
    }

    private var cliFootnote: String {
        guard let status = model.cliStatus else {
            return model.statusMessage
        }
        return "\(status.executablePath) - \(status.version ?? "unknown version")"
    }
}

struct FieldLabel: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .foregroundStyle(.secondary)
            .frame(width: 86, alignment: .leading)
    }
}

struct SettingsRow<Content: View>: View {
    let label: String
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            FieldLabel(label)
            content
        }
        .padding(.vertical, 10)
    }
}

struct SettingsDivider: View {
    var body: some View {
        Divider()
            .padding(.leading, 100)
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
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Recent projects")
                    .font(.title2.weight(.semibold))
                Spacer()
                Button {
                    model.refresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("Refresh")
            }

            if model.projects.isEmpty {
                EmptyProjectsView()
            } else {
                ForEach(visibleProjects) { project in
                    ProjectRow(project: project)
                }
            }
        }
        .padding(18)
        .nativePanel()
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
        .padding(10)
        .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color(nsColor: .separatorColor).opacity(0.35), lineWidth: 1)
        )
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
        Color(nsColor: .windowBackgroundColor)
            .ignoresSafeArea()
    }
}

struct NativePromptEditor: NSViewRepresentable {
    @Binding var text: String
    let placeholder: String

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> PromptEditorNSView {
        let view = PromptEditorNSView()
        view.placeholderLabel.stringValue = placeholder
        view.textView.delegate = context.coordinator
        context.coordinator.view = view
        return view
    }

    func updateNSView(_ nsView: PromptEditorNSView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.view = nsView
        nsView.placeholderLabel.stringValue = placeholder
        if nsView.textView.string != text {
            nsView.textView.string = text
        }
        nsView.updatePlaceholder()
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: NativePromptEditor
        weak var view: PromptEditorNSView?

        init(_ parent: NativePromptEditor) {
            self.parent = parent
        }

        func textDidBeginEditing(_ notification: Notification) {
            view?.isEditing = true
            view?.updatePlaceholder()
        }

        func textDidEndEditing(_ notification: Notification) {
            view?.isEditing = false
            view?.updatePlaceholder()
        }

        func textDidChange(_ notification: Notification) {
            guard let textView = notification.object as? NSTextView else { return }
            parent.text = textView.string
            view?.updatePlaceholder()
        }
    }
}

final class PromptEditorNSView: NSView {
    let scrollView = NSScrollView()
    let textView = NSTextView()
    let placeholderLabel = NSTextField(labelWithString: "")
    var isEditing = false

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        wantsLayer = true
        layer?.cornerRadius = 8
        layer?.cornerCurve = .continuous
        layer?.borderWidth = 1
        layer?.borderColor = NSColor.separatorColor.withAlphaComponent(0.28).cgColor
        layer?.backgroundColor = NSColor.textBackgroundColor.cgColor

        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.drawsBackground = false
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .noBorder

        textView.drawsBackground = false
        textView.isRichText = false
        textView.importsGraphics = false
        textView.allowsUndo = true
        textView.isEditable = true
        textView.isSelectable = true
        textView.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        textView.textColor = .labelColor
        textView.insertionPointColor = .controlAccentColor
        textView.textContainerInset = NSSize(width: 10, height: 10)
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isHorizontallyResizable = false
        textView.isVerticallyResizable = true
        textView.autoresizingMask = [.width]
        textView.textContainer?.widthTracksTextView = true
        scrollView.documentView = textView

        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        placeholderLabel.textColor = .placeholderTextColor

        addSubview(scrollView)
        addSubview(placeholderLabel)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            placeholderLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 15),
            placeholderLabel.topAnchor.constraint(equalTo: topAnchor, constant: 13)
        ])
    }

    func updatePlaceholder() {
        placeholderLabel.isHidden = isEditing || !textView.string.isEmpty
    }
}

extension View {
    func nativePanel(cornerRadius: CGFloat = 12) -> some View {
        background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(Color(nsColor: .separatorColor).opacity(0.22), lineWidth: 1)
            )
    }
}
