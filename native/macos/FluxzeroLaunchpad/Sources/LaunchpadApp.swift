import AppKit
import SwiftUI

@main
struct FluxzeroLaunchpadApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings {
            ProjectDefaultsSettingsView()
                .environmentObject(LaunchpadModel.shared)
                .frame(width: 560, height: 390)
        }
        .commands {
            CommandGroup(replacing: .appInfo) {
                Button("About Fluxzero Launchpad") {
                    FluxzeroAppActions.showAboutPanel()
                }
            }
            CommandGroup(replacing: .appSettings) {
                Button("Settings...") {
                    SettingsWindowController.shared.show()
                }
                .keyboardShortcut(",", modifiers: [.command])
            }
            CommandGroup(after: .newItem) {
                Button("Check for Updates") {
                    LaunchpadModel.shared.refresh()
                }
                .keyboardShortcut("r", modifiers: [.command])
            }
        }
    }
}

@MainActor
enum FluxzeroAppActions {
    static func showAboutPanel() {
        showAboutPanel(model: LaunchpadModel.shared)
    }

    static func showAboutPanel(model: LaunchpadModel) {
        let cliVersion = model.cliStatus?.version ?? "not ready"
        NSApp.orderFrontStandardAboutPanel(options: [
            .applicationName: "Fluxzero Launchpad",
            .applicationVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0",
            .version: "Fluxzero CLI \(cliVersion)"
        ])
    }
}

@MainActor
enum FluxzeroMenuBarAssets {
    private static let imageSize = NSSize(width: 18, height: 18)
    private static var rotatedImageCache: [Int: NSImage] = [:]

    private static let sourceImage: NSImage = {
        let image = Bundle.main.url(forResource: "FluxzeroMenuBar", withExtension: "svg")
            .flatMap { NSImage(contentsOf: $0) }
            ?? Bundle.main.url(forResource: "FluxzeroMenuBarTemplate", withExtension: "png")
                .flatMap { NSImage(contentsOf: $0) }
            ?? NSImage(systemSymbolName: "hexagon", accessibilityDescription: "Fluxzero")
            ?? NSImage(size: imageSize)
        let template = image.copy() as? NSImage ?? image
        template.isTemplate = true
        return template
    }()

    static let templateImage: NSImage = {
        drawRotatedImage(degrees: 0)
    }()

    static func rotatedTemplateImage(degrees: CGFloat) -> NSImage {
        let normalized = normalizedDegrees(degrees)
        if normalized < 0.001 {
            return templateImage
        }

        let cacheKey = Int((normalized * 100).rounded())
        if let cached = rotatedImageCache[cacheKey] {
            return cached
        }

        let image = drawRotatedImage(degrees: normalized)
        rotatedImageCache[cacheKey] = image
        return image
    }

    private static func drawRotatedImage(degrees: CGFloat) -> NSImage {
        let image = NSImage(size: imageSize, flipped: false) { bounds in
            NSGraphicsContext.current?.imageInterpolation = .high

            let transform = NSAffineTransform()
            transform.translateX(by: bounds.midX, yBy: bounds.midY)
            transform.rotate(byDegrees: degrees)
            transform.translateX(by: -bounds.midX, yBy: -bounds.midY)
            transform.concat()

            sourceImage.draw(
                in: aspectFitRect(for: sourceImage.size, in: bounds),
                from: NSRect(origin: .zero, size: sourceImage.size),
                operation: .sourceOver,
                fraction: 1
            )
            return true
        }
        image.isTemplate = true
        return image
    }

    private static func aspectFitRect(for sourceSize: NSSize, in bounds: NSRect) -> NSRect {
        guard sourceSize.width > 0, sourceSize.height > 0 else { return bounds }
        let scale = min(bounds.width / sourceSize.width, bounds.height / sourceSize.height)
        let width = sourceSize.width * scale
        let height = sourceSize.height * scale
        return NSRect(
            x: bounds.midX - width / 2,
            y: bounds.midY - height / 2,
            width: width,
            height: height
        )
    }

    private static func normalizedDegrees(_ degrees: CGFloat) -> CGFloat {
        let normalized = degrees.truncatingRemainder(dividingBy: 360)
        return normalized >= 0 ? normalized : normalized + 360
    }
}

@MainActor
enum FluxzeroAlertPresenter {
    private static var isShowing = false

    static func show(_ message: String, completion: @escaping () -> Void) {
        guard !isShowing else { return }
        isShowing = true
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "Could not complete the Fluxzero action"
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        alert.runModal()
        isShowing = false
        completion()
    }
}

struct LaunchpadView: View {
    @EnvironmentObject private var model: LaunchpadModel

    var body: some View {
        CreateScreen()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LaunchpadBackdrop())
        .background(Color(nsColor: .windowBackgroundColor))
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

struct SettingsForm<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        Form {
            content
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 620)
        .padding(.top, 4)
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
            .accessibilityLabel("Retry")
            .help("Retry")
        }
        .font(.caption)
    }
}

struct GeneratorPanel: View {
    @EnvironmentObject private var model: LaunchpadModel
    private let destinationControlWidth: CGFloat = 160

    private var actionsDisabled: Bool {
        model.isBusy || model.projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Section("New Fluxzero Project") {
            LabeledContent("Name") {
                AppKitPlaceholderTextField(
                    text: Binding(
                    get: { model.projectName },
                    set: { model.setProjectName($0) }
                    ),
                    placeholder: "Enter project name",
                    accessibilityLabel: "Project name"
                )
                .frame(maxWidth: .infinity, minHeight: 22)
            }

            TopAlignedFormRow("Description") {
                AppKitPlaceholderTextView(
                    text: $model.prompt,
                    placeholder: "Describe what you want to build",
                    accessibilityLabel: "Project description"
                )
                .frame(maxWidth: .infinity, minHeight: 96)
            }

            LabeledContent("Open project in") {
                TrailingControlColumn(width: destinationControlWidth) {
                    Picker("Open project in", selection: $model.selectedAgent) {
                        ForEach(AgentChoice.openDestinations) { option in
                            AgentChoiceMenuLabel(option: option).tag(option)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .fixedSize()
                }
            }

            AdvancedDisclosure()
        }

        Section {
            HStack {
                Spacer(minLength: 0)
                Button {
                    model.createAndOpenSelectedDestination()
                } label: {
                    Text(model.selectedAgent.actionTitle)
                        .frame(minWidth: 88)
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(actionsDisabled)
            }
        }
    }
}

struct TopAlignedFormRow<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        HStack(alignment: .top, spacing: 18) {
            Text(title)
            content
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

struct AdvancedOptions: View {
    @EnvironmentObject private var model: LaunchpadModel
    private let controlWidth: CGFloat = 240

    var body: some View {
        VStack(spacing: 0) {
            AdvancedRow(title: "Location", subtitle: model.location) {
                Button("Choose...") {
                    model.chooseLocation()
                }
                .accessibilityLabel("Choose project location")
            }
            AdvancedDivider()
            AdvancedRow(title: "Template", subtitle: "Starter project") {
                TrailingControlColumn(width: controlWidth) {
                    Picker("Template", selection: $model.template) {
                        ForEach(model.templates, id: \.self) {
                            Text($0).tag($0)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .fixedSize()
                }
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
                .frame(width: controlWidth)
            }
            AdvancedDivider()
            AdvancedRow(title: "Group ID", subtitle: "Java package prefix") {
                TextField("", text: $model.groupId)
                    .labelsHidden()
                    .accessibilityLabel("Group ID")
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .frame(width: controlWidth)
            }
            AdvancedDivider()
            AdvancedRow(title: "Artifact ID", subtitle: "Build artifact name") {
                TextField("", text: $model.artifactId)
                    .labelsHidden()
                    .accessibilityLabel("Artifact ID")
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .frame(width: controlWidth)
            }
            AdvancedDivider()
            AdvancedRow(title: "Package", subtitle: "Generated source package") {
                TextField("", text: $model.packageName)
                    .labelsHidden()
                    .accessibilityLabel("Package")
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .frame(width: controlWidth)
            }
            if model.isGitAvailable {
                AdvancedDivider()
                AdvancedRow(title: "Git repository", subtitle: "Initialize version control") {
                    Toggle("", isOn: $model.initGit)
                        .labelsHidden()
                        .toggleStyle(.switch)
                }
            }
        }
        .padding(.top, 8)
        .controlSize(.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct AdvancedDisclosure: View {
    @EnvironmentObject private var model: LaunchpadModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        DisclosureGroup(isExpanded: $model.advancedExpanded) {
            AdvancedOptions()
        } label: {
            Text("Advanced options")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: model.advancedExpanded)
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
                .frame(width: 260, alignment: .trailing)
        }
        .padding(.vertical, 9)
    }
}

struct AdvancedDivider: View {
    var body: some View {
        Divider()
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
                .accessibilityHidden(true)
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
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(project.name)
                    .font(.body)
                Text(project.path)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
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
            ProductIconProjectActionButton(assetName: "CursorCube", help: "Open in Cursor", size: 16) {
                model.openProject(project, agent: .cursor)
            }
            ProductIconProjectActionButton(assetName: "ClaudeCodeMark", help: "Open in Claude Code", size: 16) {
                model.openProject(project, agent: .claude)
            }
            ProductIconProjectActionButton(assetName: "CodexIcon", help: "Open in Codex", size: 18) {
                model.openProject(project, agent: .codex)
            }
        }
    }
}

struct ProductIconProjectActionButton: View {
    let assetName: String
    let help: String
    let size: CGFloat
    let action: () -> Void
    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            ProductIconImage(assetName: assetName, fallbackSystemImage: fallbackSystemImage, size: size)
                .frame(width: 28, height: 28)
                .background(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(isHovering ? Color(nsColor: .selectedControlColor).opacity(0.18) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(help)
        .help(help)
        .onHover { isHovering = $0 }
    }

    private var fallbackSystemImage: String {
        if assetName == "ClaudeCodeMark" {
            return "terminal"
        }
        if assetName == "CodexIcon" {
            return "sparkles"
        }
        return "cursorarrow"
    }
}

struct ProductIconImage: View {
    let assetName: String
    let fallbackSystemImage: String
    let size: CGFloat

    var body: some View {
        if let image {
            if assetName == "CodexIcon" {
                Image(nsImage: image)
                    .renderingMode(.original)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
            } else {
                Image(nsImage: image)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
                    .foregroundStyle(Color(nsColor: .labelColor))
            }
        } else {
            Image(systemName: fallbackSystemImage)
                .frame(width: size, height: size)
                .foregroundStyle(Color(nsColor: .secondaryLabelColor))
        }
    }

    private var image: NSImage? {
        for fileExtension in ["svg", "png"] {
            guard
                let url = Bundle.main.url(forResource: assetName, withExtension: fileExtension),
                let image = NSImage(contentsOf: url)
            else {
                continue
            }
            image.isTemplate = true
            return image
        }
        return nil
    }
}

struct AgentChoiceMenuLabel: View {
    let option: AgentChoice
    private var iconSize: CGFloat {
        option == .codex ? 16 : 14
    }

    var body: some View {
        HStack(spacing: 6) {
            if let assetName = option.productIconAssetName {
                ProductIconImage(assetName: assetName, fallbackSystemImage: option.systemImage, size: iconSize)
            } else {
                Image(systemName: option.systemImage)
                    .frame(width: iconSize, height: iconSize)
            }
            Text(option.label)
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
                .frame(width: 28, height: 28)
                .background(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(isHovering ? Color(nsColor: .selectedControlColor).opacity(0.18) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .foregroundStyle(foregroundStyle)
        .accessibilityLabel(help)
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
    let accessibilityLabel: String

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
        textField.setAccessibilityLabel(accessibilityLabel)
        return textField
    }

    func updateNSView(_ nsView: NSTextField, context: Context) {
        if nsView.stringValue != text {
            nsView.stringValue = text
        }
        nsView.placeholderString = placeholder
        nsView.setAccessibilityLabel(accessibilityLabel)
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
    let accessibilityLabel: String

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeNSView(context: Context) -> PlaceholderTextViewContainer {
        let view = PlaceholderTextViewContainer()
        view.textView.delegate = context.coordinator
        view.placeholderLabel.stringValue = placeholder
        view.updateAccessibilityLabel(accessibilityLabel)
        return view
    }

    func updateNSView(_ nsView: PlaceholderTextViewContainer, context: Context) {
        if nsView.textView.string != text {
            nsView.textView.string = text
        }
        nsView.placeholderLabel.stringValue = placeholder
        nsView.updateAccessibilityLabel(accessibilityLabel)
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
    private var placeholderTopConstraint: NSLayoutConstraint?

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
        textView.textContainerInset = .zero
        textView.textContainer?.lineFragmentPadding = 0
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        scrollView.documentView = textView
        scrollView.setAccessibilityRole(.scrollArea)

        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        placeholderLabel.textColor = .placeholderTextColor
        placeholderLabel.alignment = .right
        placeholderLabel.isSelectable = false
        placeholderLabel.refusesFirstResponder = true
        placeholderLabel.setAccessibilityHidden(true)

        addSubview(scrollView)
        addSubview(placeholderLabel)

        let placeholderTopConstraint = placeholderLabel.topAnchor.constraint(equalTo: topAnchor)
        self.placeholderTopConstraint = placeholderTopConstraint
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            placeholderLabel.leadingAnchor.constraint(equalTo: leadingAnchor),
            placeholderLabel.trailingAnchor.constraint(equalTo: trailingAnchor),
            placeholderTopConstraint
        ])
    }

    override func layout() {
        super.layout()
        textView.frame = scrollView.contentView.bounds
        textView.minSize = NSSize(width: 0, height: scrollView.contentSize.height)
        textView.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        textView.textContainer?.containerSize = NSSize(
            width: scrollView.contentSize.width,
            height: CGFloat.greatestFiniteMagnitude
        )
        if let textContainer = textView.textContainer {
            textView.layoutManager?.ensureLayout(for: textContainer)
        }
        placeholderTopConstraint?.constant = textView.textContainerOrigin.y
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(textView)
        super.mouseDown(with: event)
    }

    func updateAccessibilityLabel(_ label: String) {
        textView.setAccessibilityLabel(label)
        scrollView.setAccessibilityLabel(label)
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
