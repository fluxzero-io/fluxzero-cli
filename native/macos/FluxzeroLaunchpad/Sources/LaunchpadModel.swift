import AppKit
import Foundation
import SwiftUI

@MainActor
final class LaunchpadModel: ObservableObject {
    static let shared = LaunchpadModel()

    @Published var projectName = ""
    @Published var prompt = ""
    @Published var location = "\(NSHomeDirectory())/FluxzeroProjects"
    @Published var template = "flux-basic-java"
    @Published var groupId = "com.example"
    @Published var artifactId = ""
    @Published var packageName = "com.example.app"
    @Published var description = "A Fluxzero application"
    @Published var buildSystem: DesktopBuildSystem = .maven
    @Published var initGit = true
    @Published var advancedExpanded = false
    @Published var selectedAgent: AgentChoice = .codex
    @Published var templates = ["flux-basic-java", "flux-basic-kotlin", "gamerental"]
    @Published var projects: [GeneratedProject] = []
    @Published var cliStatus: CliStatus?
    @Published var isBusy = false
    @Published var statusMessage = "Preparing Fluxzero CLI..."
    @Published var errorMessage: String?
    @Published var pendingProjectDeletion: GeneratedProject?
    @Published var creationDefaults = ProjectCreationDefaults.fallback
    @Published var isGitAvailable = true
    @Published var javaStatus: JavaRuntimeStatus = .missing

    private let paths = AppPaths.detect()
    private lazy var cliRuntime = CliRuntimeService(paths: paths)
    private lazy var registry = ProjectRegistry(registryFile: paths.registryFile)
    private lazy var dependencies = DevelopmentDependencyService(paths: paths)
    private let creationDefaultsStore = ProjectCreationDefaultsStore()
    private let agentLauncher = AgentLauncher()

    var isLaunchpadUpToDate: Bool {
        guard let version = cliStatus?.version?.trimmingPrefix("v"),
              let latestVersion = cliStatus?.latestVersion?.trimmingPrefix("v") else {
            return false
        }
        return version == latestVersion
    }

    init() {
        let savedDefaults = creationDefaultsStore.load()
        creationDefaults = savedDefaults
        applyCreationDefaults(savedDefaults)
        updateDerivedIdentifiers(from: projectName)
    }

    func refresh() {
        Task {
            await prepare(showError: true)
        }
    }

    func prepare(showError: Bool = false) async {
        isBusy = true
        defer { isBusy = false }
        do {
            let runtime = cliRuntime
            let dependencies = dependencies
            let (status, loadedTemplates, gitAvailable, detectedJava) = try await Task.detached(priority: .userInitiated) {
                let status = try await runtime.ensureLatestCli()
                return (status, runtime.listTemplates(), dependencies.isGitAvailable(), dependencies.detectJava25())
            }.value
            cliStatus = status
            templates = loadedTemplates.isEmpty ? templates : loadedTemplates
            isGitAvailable = gitAvailable
            if !gitAvailable {
                initGit = false
            }
            javaStatus = detectedJava
            projects = registry.listProjects()
            statusMessage = status.message
        } catch {
            projects = registry.listProjects()
            statusMessage = "Fluxzero CLI is not ready yet: \(error.localizedDescription)"
            if showError {
                errorMessage = error.localizedDescription
            }
        }
    }

    func chooseLocation() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: location)
        if panel.runModal() == .OK, let url = panel.url {
            location = url.fsPath
            advancedExpanded = true
        }
    }

    func chooseDefaultLocation() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: creationDefaults.location)
        if panel.runModal() == .OK, let url = panel.url {
            updateCreationDefault(\.location, to: url.fsPath)
        }
    }

    func updateCreationDefault<Value>(_ keyPath: WritableKeyPath<ProjectCreationDefaults, Value>, to value: Value) {
        var updated = creationDefaults
        updated[keyPath: keyPath] = value
        setCreationDefaults(updated)
    }

    func updateDefaultTemplate(_ template: String) {
        var updated = creationDefaults
        updated.template = template
        if template.localizedCaseInsensitiveContains("kotlin") {
            updated.buildSystem = .gradle
        }
        setCreationDefaults(updated)
    }

    func setProjectName(_ value: String) {
        projectName = value
        updateDerivedIdentifiers(from: value)
    }

    func createAndOpenSelectedDestination() {
        Task {
            await generate(openIn: selectedAgent)
        }
    }

    func openProject(_ project: GeneratedProject, agent: AgentChoice) {
        Task {
            do {
                let promptText = project.promptPath.flatMap { try? String(contentsOf: URL(fileURLWithPath: $0), encoding: .utf8) } ?? ""
                _ = try agentLauncher.launch(choice: agent, projectPath: project.path, prompt: promptText)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func openRecentProject(_ project: GeneratedProject) {
        let agent = creationDefaults.agentChoice == .none ? AgentChoice.finder : creationDefaults.agentChoice
        openProject(project, agent: agent)
    }

    func openFolder(_ project: GeneratedProject) {
        agentLauncher.openFolder(project.path)
    }

    func reloadProjects() {
        projects = registry.listProjects()
    }

    func copyPrompt(_ project: GeneratedProject) {
        let promptText = project.promptPath.flatMap { try? String(contentsOf: URL(fileURLWithPath: $0), encoding: .utf8) } ?? ""
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(promptText, forType: .string)
        statusMessage = "Prompt copied."
    }

    func requestDeleteProject(_ project: GeneratedProject) {
        pendingProjectDeletion = project
    }

    func deleteProject(_ project: GeneratedProject) {
        do {
            let projectURL = URL(fileURLWithPath: project.path)
            if FileManager.default.fileExists(atPath: projectURL.fsPath) {
                try FileManager.default.trashItem(at: projectURL, resultingItemURL: nil)
            }
            try registry.removeProject(project)
            projects = registry.listProjects()
            pendingProjectDeletion = nil
            statusMessage = "Moved \(project.name) to Trash."
        } catch {
            pendingProjectDeletion = nil
            errorMessage = error.localizedDescription
        }
    }

    func handle(url: URL, presentationMode: DeepLinkPresentationMode = .interactive) {
        guard let link = DeepLinkParser.parse(url) else { return }
        switch link {
        case .new(let link):
            apply(link)
            if presentationMode == .background {
                Task {
                    await runDirectLink(link.asDirectCreate(defaults: creationDefaults))
                }
            }
        case .direct(let direct):
            Task {
                await runDirectLink(direct)
            }
        }
    }

    private func runDirectLink(_ direct: FluxzeroDirectLink) async {
        isBusy = true
        defer { isBusy = false }
        do {
            if direct.isCreateRequest {
                try await prepareJava25()
            }
            statusMessage = direct.isCreateRequest ? "Creating project..." : "Opening project..."
            let runner = DeepLinkActionRunner(paths: paths, cliRuntime: cliRuntime, registry: registry, agentLauncher: agentLauncher)
            let result = try await Task.detached(priority: .userInitiated) {
                try await runner.run(direct)
            }.value
            projects = registry.listProjects()
            statusMessage = result.statusMessage
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func generate(openIn agent: AgentChoice) async {
        guard let cliStatus else {
            errorMessage = "Fluxzero CLI is not ready yet."
            return
        }
        isBusy = true
        defer { isBusy = false }
        do {
            try await prepareJava25()
            statusMessage = "Creating project..."
            let request = makeRequest(agent: agent)
            let registry = registry
            let project = try await Task.detached(priority: .userInitiated) {
                let generator = ProjectGenerator(cliExecutable: URL(fileURLWithPath: cliStatus.executablePath), registry: registry)
                return try generator.generate(request, cliVersion: cliStatus.version)
            }.value
            projects = registry.listProjects()
            statusMessage = "Created \(project.name)."
            if agent != .none {
                let promptText = project.promptPath.flatMap { try? String(contentsOf: URL(fileURLWithPath: $0), encoding: .utf8) } ?? request.firstPrompt
                _ = try agentLauncher.launch(choice: agent, projectPath: project.path, prompt: promptText)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func makeRequest(agent: AgentChoice) -> GenerateProjectRequest {
        let effectiveArtifact = artifactId.nilIfBlank ?? ProjectNameNormalizer.normalize(projectName).ifBlank("app")
        let effectiveGroup = groupId.nilIfBlank ?? "com.example"
        let effectivePackage = packageName.nilIfBlank ?? "\(effectiveGroup).\(effectiveArtifact.replacingOccurrences(of: "-", with: ""))"
        return GenerateProjectRequest(
            template: template,
            name: projectName,
            outputBaseDir: location,
            packageName: effectivePackage,
            groupId: effectiveGroup,
            artifactId: effectiveArtifact,
            description: description.nilIfBlank ?? "A Fluxzero application",
            buildSystem: buildSystem,
            initGit: initGit && isGitAvailable,
            firstPrompt: prompt,
            agentChoice: agent
        )
    }

    private func apply(_ link: FluxzeroNewProjectLink) {
        if let name = link.name {
            setProjectName(name)
        }
        if let prompt = link.prompt {
            self.prompt = prompt
        }
        if let template = link.template {
            self.template = template
            buildSystem = template.localizedCaseInsensitiveContains("kotlin") ? .gradle : .maven
            advancedExpanded = true
        }
        if let location = link.location {
            self.location = location
            advancedExpanded = true
        }
        if let agentChoice = link.agentChoice {
            selectedAgent = agentChoice
        }
    }

    private func setCreationDefaults(_ defaults: ProjectCreationDefaults) {
        creationDefaults = defaults
        creationDefaultsStore.save(defaults)
        applyCreationDefaults(defaults)
    }

    private func applyCreationDefaults(_ defaults: ProjectCreationDefaults) {
        location = defaults.location
        template = defaults.template
        groupId = defaults.groupId
        buildSystem = defaults.buildSystem
        initGit = defaults.initGit
        selectedAgent = defaults.agentChoice
        updateDerivedIdentifiers(from: projectName)
    }

    private func updateDerivedIdentifiers(from name: String) {
        let artifact = ProjectNameNormalizer.normalize(name)
        artifactId = artifact
        let suffix = artifact.replacingOccurrences(of: #"[^a-z0-9]"#, with: "", options: .regularExpression).ifBlank("app")
        packageName = "\(groupId).\(suffix)"
    }

    private func prepareJava25() async throws {
        statusMessage = "Preparing Java 25..."
        let dependencies = dependencies
        javaStatus = try await Task.detached(priority: .userInitiated) {
            try await dependencies.ensureJava25()
        }.value
    }
}

extension FluxzeroNewProjectLink {
    func asDirectCreate(defaults: ProjectCreationDefaults) -> FluxzeroDirectLink {
        .create(
            name: name,
            template: template ?? defaults.template,
            location: location ?? defaults.location,
            groupId: defaults.groupId,
            artifactId: nil,
            packageName: nil,
            description: "A Fluxzero application",
            buildSystem: defaults.buildSystem,
            initGit: defaults.initGit,
            prompt: prompt,
            agentChoice: agentChoice ?? defaults.agentChoice
        )
    }
}

extension FluxzeroDirectLink {
    var isCreateRequest: Bool {
        switch self {
        case .create:
            true
        case .open:
            false
        }
    }
}

extension AgentLaunchResult {
    var statusMessage: String {
        if openedCodexDownload {
            return "Codex installer opened."
        }
        if openedCursorDownload {
            return "Cursor download page opened."
        }
        if openedCodex {
            return "Opened project in Codex."
        }
        if openedClaude {
            return "Opened project in Claude Code."
        }
        if openedCursor {
            return "Opened project in Cursor."
        }
        if openedFinder {
            return "Opened project in Finder."
        }
        return "Done."
    }
}
