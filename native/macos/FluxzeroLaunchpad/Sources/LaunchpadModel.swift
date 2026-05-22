import AppKit
import Foundation
import SwiftUI

@MainActor
final class LaunchpadModel: ObservableObject {
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
    @Published var selectedSection: LaunchpadSection = .create
    @Published var templates = ["flux-basic-java", "flux-basic-kotlin", "gamerental"]
    @Published var projects: [GeneratedProject] = []
    @Published var cliStatus: CliStatus?
    @Published var isBusy = false
    @Published var statusMessage = "Preparing Fluxzero CLI..."
    @Published var errorMessage: String?

    private let paths = AppPaths.detect()
    private lazy var cliRuntime = CliRuntimeService(paths: paths)
    private lazy var registry = ProjectRegistry(registryFile: paths.registryFile)
    private let agentLauncher = AgentLauncher()

    init() {
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
            let (status, loadedTemplates) = try await Task.detached(priority: .userInitiated) {
                let status = try await runtime.ensureLatestCli()
                return (status, runtime.listTemplates())
            }.value
            cliStatus = status
            templates = loadedTemplates.isEmpty ? templates : loadedTemplates
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
            location = url.path()
            advancedExpanded = true
        }
    }

    func setProjectName(_ value: String) {
        projectName = value
        updateDerivedIdentifiers(from: value)
    }

    func createAndOpen(agent: AgentChoice) {
        selectedAgent = agent
        Task {
            await generate(openIn: agent)
        }
    }

    func createOnly() {
        Task {
            await generate(openIn: .none)
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

    func openFolder(_ project: GeneratedProject) {
        agentLauncher.openFolder(project.path)
    }

    func copyPrompt(_ project: GeneratedProject) {
        let promptText = project.promptPath.flatMap { try? String(contentsOf: URL(fileURLWithPath: $0), encoding: .utf8) } ?? ""
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(promptText, forType: .string)
        statusMessage = "Prompt copied."
    }

    func handle(url: URL) {
        guard let link = DeepLinkParser.parse(url) else { return }
        switch link {
        case .new(let link):
            selectedSection = .create
            apply(link)
        case .direct(let direct):
            Task {
                do {
                    let runner = DeepLinkActionRunner(paths: paths, cliRuntime: cliRuntime, registry: registry, agentLauncher: agentLauncher)
                    _ = try await Task.detached(priority: .userInitiated) {
                        try await runner.run(direct)
                    }.value
                    projects = registry.listProjects()
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
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
            initGit: initGit,
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

    private func updateDerivedIdentifiers(from name: String) {
        let artifact = ProjectNameNormalizer.normalize(name)
        artifactId = artifact
        let suffix = artifact.replacingOccurrences(of: #"[^a-z0-9]"#, with: "", options: .regularExpression).ifBlank("app")
        packageName = "\(groupId).\(suffix)"
    }
}
