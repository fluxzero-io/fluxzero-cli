import AppKit
import SwiftUI

@main
struct FluxzeroLaunchpadApp: App {
    @StateObject private var model = LaunchpadModel()

    var body: some Scene {
        WindowGroup("Fluxzero Launchpad") {
            LaunchpadView()
                .environmentObject(model)
                .frame(minWidth: 700, minHeight: 440)
                .background(WindowSizeLimiter(minSize: NSSize(width: 700, height: 440), maxSize: NSSize(width: 760, height: 560)))
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
        NavigationStack {
            CreateScreen()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(LaunchpadBackdrop())
                .navigationTitle("")
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        AppTitle()
                    }
                }
        }
        .background(Color(nsColor: .windowBackgroundColor))
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
        .alert("Delete project?", isPresented: Binding(
            get: { model.pendingProjectDeletion != nil },
            set: { if !$0 { model.pendingProjectDeletion = nil } }
        ), presenting: model.pendingProjectDeletion) { project in
            Button("Cancel", role: .cancel) {
                model.pendingProjectDeletion = nil
            }
            Button("Move to Trash", role: .destructive) {
                model.deleteProject(project)
            }
        } message: { project in
            Text("This moves \"\(project.name)\" to the Trash and removes it from recent projects.")
        }
    }
}

struct AppTitle: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 20, height: 20)
                .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            Text("Create Fluxzero Project")
                .font(.headline)
        }
    }
}

struct WindowSizeLimiter: NSViewRepresentable {
    let minSize: NSSize
    let maxSize: NSSize

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            configure(window: view.window)
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async {
            configure(window: nsView.window)
        }
    }

    private func configure(window: NSWindow?) {
        guard let window else { return }
        window.minSize = minSize
        window.maxSize = maxSize
        var frame = window.frame
        var shouldAdjustFrame = false
        if frame.width > maxSize.width {
            frame.origin.x += (frame.width - maxSize.width) / 2
            frame.size.width = maxSize.width
            shouldAdjustFrame = true
        }
        if frame.width < minSize.width {
            frame.origin.x -= (minSize.width - frame.width) / 2
            frame.size.width = minSize.width
            shouldAdjustFrame = true
        }
        if frame.height < minSize.height {
            frame.origin.y -= minSize.height - frame.height
            frame.size.height = minSize.height
            shouldAdjustFrame = true
        }
        if frame.height > maxSize.height {
            frame.origin.y += frame.height - maxSize.height
            frame.size.height = maxSize.height
            shouldAdjustFrame = true
        }
        if shouldAdjustFrame {
            window.setFrame(frame, display: true)
        }
    }
}

struct CreateScreen: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        SettingsForm {
            if model.isBusy || model.cliStatus == nil {
                Section {
                    CliStatusBanner()
                }
            }
            GeneratorPanel()
            RecentProjectsPanel(limit: 5)
        }
    }
}

struct ProjectsScreen: View {
    var body: some View {
        SettingsForm {
            RecentProjectsPanel(limit: nil)
        }
    }
}

struct UpgradesScreen: View {
    var body: some View {
        SettingsForm {
            Section {
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

struct SettingsForm<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        Form {
            content
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 620)
        .padding(.top, 28)
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(nsColor: .windowBackgroundColor))
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
            .help("Retry")
        }
        .font(.caption)
    }
}

struct GeneratorPanel: View {
    @EnvironmentObject private var model: LaunchpadModel

    private var actionsDisabled: Bool {
        model.isBusy || model.projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Section("Fluxzero Project") {
            LabeledContent("Name") {
                AppKitPlaceholderTextField(
                    text: Binding(
                    get: { model.projectName },
                    set: { model.setProjectName($0) }
                    ),
                    placeholder: "Enter project name"
                )
                .frame(maxWidth: .infinity, minHeight: 22)
            }

            LabeledContent("Description") {
                AppKitPlaceholderTextView(
                    text: $model.prompt,
                    placeholder: "Describe what you want to build"
                )
                .frame(maxWidth: .infinity, minHeight: 64)
            }
        }

        Section {
            HStack(spacing: 12) {
                Button("Create only") {
                    model.createOnly()
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.link)

                Spacer()

                Button {
                    model.createAndOpen(agent: .claude)
                } label: {
                    Label("Open in Claude Code", systemImage: "terminal")
                }
                .buttonStyle(.bordered)

                Button {
                    model.createAndOpen(agent: .codex)
                } label: {
                    Label("Open in Codex", systemImage: "sparkles")
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
            }
            .disabled(actionsDisabled)
        }

        AdvancedDisclosure()
    }
}

struct AdvancedOptions: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            AdvancedGroup {
                AdvancedRow(title: "Location", subtitle: model.location) {
                    Button("Choose...") {
                        model.chooseLocation()
                    }
                }
                AdvancedDivider()
                AdvancedRow(title: "Template", subtitle: "Starter project") {
                    Picker("Template", selection: $model.template) {
                        ForEach(model.templates, id: \.self) {
                            Text($0).tag($0)
                        }
                    }
                    .labelsHidden()
                    .frame(width: 220)
                }
                AdvancedDivider()
                AdvancedRow(title: "Build system", subtitle: "Project tooling") {
                    Picker("Build system", selection: $model.buildSystem) {
                        ForEach(DesktopBuildSystem.allCases) { option in
                            Text(option.label).tag(option)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.segmented)
                    .frame(width: 220)
                }
            }

            AdvancedGroup {
                AdvancedRow(title: "Group ID", subtitle: "Java package prefix") {
                    TextField("Group ID", text: $model.groupId)
                        .textFieldStyle(.plain)
                        .frame(width: 240)
                }
                AdvancedDivider()
                AdvancedRow(title: "Artifact ID", subtitle: "Build artifact name") {
                    TextField("Artifact ID", text: $model.artifactId)
                        .textFieldStyle(.plain)
                        .frame(width: 240)
                }
                AdvancedDivider()
                AdvancedRow(title: "Package", subtitle: "Generated source package") {
                    TextField("Package", text: $model.packageName)
                        .textFieldStyle(.plain)
                        .frame(width: 240)
                }
                AdvancedDivider()
                AdvancedRow(title: "Git repository", subtitle: "Initialize version control") {
                    Toggle("", isOn: $model.initGit)
                        .labelsHidden()
                        .toggleStyle(.switch)
                }
            }
        }
        .controlSize(.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct AdvancedDisclosure: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) {
                    model.advancedExpanded.toggle()
                }
            } label: {
                HStack {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .rotationEffect(.degrees(model.advancedExpanded ? 90 : 0))
                    Text("Advanced options")
                        .font(.headline)
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if model.advancedExpanded {
                AdvancedOptions()
                    .transition(.opacity)
                    .clipped()
            }
        }
        .clipped()
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(.easeInOut(duration: 0.18), value: model.advancedExpanded)
    }
}

struct AdvancedGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            content
        }
        .background(Color(nsColor: .windowBackgroundColor), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color(nsColor: .separatorColor).opacity(0.22), lineWidth: 1)
        )
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct AdvancedRow<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 18)
            content
                .frame(width: 300, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

struct AdvancedDivider: View {
    var body: some View {
        Divider()
                .padding(.leading, 14)
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
        Section("Recent projects") {
            if model.projects.isEmpty {
                EmptyProjectsView()
            } else {
                ForEach(visibleProjects) { project in
                    ProjectRow(project: project)
                }
            }
        }
    }
}

struct EmptyProjectsView: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "folder.badge.plus")
                .font(.title)
                .foregroundStyle(.tertiary)
            Text("No projects yet")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 80)
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
                    .font(.body)
                Text(project.path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            ProjectActionButton(systemImage: "trash", help: "Delete project", isDestructive: true) {
                model.requestDeleteProject(project)
            }
            ProjectActionButton(systemImage: "doc.on.doc", help: "Copy prompt") {
                model.copyPrompt(project)
            }
            ProjectActionButton(systemImage: "folder", help: "Open folder") {
                model.openFolder(project)
            }
            ProjectActionButton(systemImage: "terminal", help: "Open in Claude Code") {
                model.openProject(project, agent: .claude)
            }
            ProjectActionButton(systemImage: "sparkles", help: "Open in Codex") {
                model.openProject(project, agent: .codex)
            }
        }
    }
}

struct ProjectActionButton: View {
    let systemImage: String
    let help: String
    var isDestructive = false
    let action: () -> Void
    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .frame(width: 26, height: 24)
                .background(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(isHovering ? Color(nsColor: .selectedControlColor).opacity(0.18) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .foregroundStyle(foregroundStyle)
        .help(help)
        .onHover { isHovering = $0 }
    }

    private var foregroundStyle: Color {
        if isDestructive && isHovering {
            return .red
        }
        return isHovering ? Color(nsColor: .labelColor) : Color(nsColor: .secondaryLabelColor)
    }
}

struct AppKitPlaceholderTextField: NSViewRepresentable {
    @Binding var text: String
    let placeholder: String

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeNSView(context: Context) -> NSTextField {
        let textField = NSTextField()
        textField.isBordered = false
        textField.drawsBackground = false
        textField.focusRingType = .none
        textField.alignment = .right
        textField.placeholderString = placeholder
        textField.delegate = context.coordinator
        textField.font = .systemFont(ofSize: NSFont.systemFontSize)
        textField.lineBreakMode = .byTruncatingTail
        return textField
    }

    func updateNSView(_ nsView: NSTextField, context: Context) {
        if nsView.stringValue != text {
            nsView.stringValue = text
        }
        nsView.placeholderString = placeholder
    }

    final class Coordinator: NSObject, NSTextFieldDelegate {
        @Binding var text: String

        init(text: Binding<String>) {
            _text = text
        }

        func controlTextDidChange(_ obj: Notification) {
            guard let textField = obj.object as? NSTextField else { return }
            text = textField.stringValue
        }
    }
}

struct AppKitPlaceholderTextView: NSViewRepresentable {
    @Binding var text: String
    let placeholder: String

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeNSView(context: Context) -> PlaceholderTextViewContainer {
        let view = PlaceholderTextViewContainer()
        view.textView.delegate = context.coordinator
        view.placeholderLabel.stringValue = placeholder
        return view
    }

    func updateNSView(_ nsView: PlaceholderTextViewContainer, context: Context) {
        if nsView.textView.string != text {
            nsView.textView.string = text
        }
        nsView.placeholderLabel.stringValue = placeholder
        nsView.updatePlaceholder()
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        @Binding var text: String

        init(text: Binding<String>) {
            _text = text
        }

        func textDidChange(_ notification: Notification) {
            guard let textView = notification.object as? NSTextView else { return }
            text = textView.string
            (textView.enclosingScrollView?.superview as? PlaceholderTextViewContainer)?.updatePlaceholder()
        }
    }
}

final class PlaceholderTextViewContainer: NSView {
    let scrollView = NSScrollView()
    let textView = NSTextView()
    let placeholderLabel = HitTransparentTextField(labelWithString: "")

    override var isFlipped: Bool {
        true
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.drawsBackground = false
        scrollView.borderType = .noBorder
        scrollView.hasVerticalScroller = false
        scrollView.hasHorizontalScroller = false

        textView.drawsBackground = false
        textView.isRichText = false
        textView.importsGraphics = false
        textView.allowsUndo = true
        textView.isEditable = true
        textView.isSelectable = true
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]
        textView.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        textView.textColor = .labelColor
        textView.alignment = .right
        textView.textContainerInset = NSSize(width: 0, height: 2)
        textView.textContainer?.lineFragmentPadding = 0
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        scrollView.documentView = textView

        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        placeholderLabel.textColor = .placeholderTextColor
        placeholderLabel.alignment = .right
        placeholderLabel.isSelectable = false
        placeholderLabel.refusesFirstResponder = true

        addSubview(scrollView)
        addSubview(placeholderLabel)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            placeholderLabel.leadingAnchor.constraint(equalTo: leadingAnchor),
            placeholderLabel.trailingAnchor.constraint(equalTo: trailingAnchor),
            placeholderLabel.topAnchor.constraint(equalTo: topAnchor)
        ])
    }

    override func layout() {
        super.layout()
        textView.frame = scrollView.contentView.bounds
        textView.textContainer?.containerSize = NSSize(
            width: scrollView.contentSize.width,
            height: CGFloat.greatestFiniteMagnitude
        )
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(textView)
        super.mouseDown(with: event)
    }

    func updatePlaceholder() {
        placeholderLabel.isHidden = !textView.string.isEmpty
    }
}

final class HitTransparentTextField: NSTextField {
    override func hitTest(_ point: NSPoint) -> NSView? {
        nil
    }
}

struct LaunchpadBackdrop: View {
    var body: some View {
        Color(nsColor: .windowBackgroundColor)
            .ignoresSafeArea()
    }
}
