import Foundation

enum DesktopBuildSystem: String, CaseIterable, Identifiable, Codable, Sendable {
    case maven
    case gradle

    var id: String { rawValue }

    var label: String {
        switch self {
        case .maven: "Maven"
        case .gradle: "Gradle"
        }
    }
}

enum AgentChoice: String, CaseIterable, Identifiable, Codable, Sendable {
    case none
    case codex
    case claude
    case both

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "Create only"
        case .codex: "Codex"
        case .claude: "Claude Code"
        case .both: "Codex and Claude"
        }
    }
}

enum LaunchpadSection: String, CaseIterable, Identifiable, Sendable {
    case create
    case projects
    case upgrades

    var id: String { rawValue }

    var label: String {
        switch self {
        case .create: "Create"
        case .projects: "Projects"
        case .upgrades: "Upgrades"
        }
    }

    var systemImage: String {
        switch self {
        case .create: "wand.and.stars"
        case .projects: "folder"
        case .upgrades: "arrow.triangle.2.circlepath"
        }
    }
}

struct GenerateProjectRequest: Sendable {
    var template: String
    var name: String
    var outputBaseDir: String
    var packageName: String
    var groupId: String
    var artifactId: String
    var description: String
    var buildSystem: DesktopBuildSystem
    var initGit: Bool
    var firstPrompt: String
    var agentChoice: AgentChoice
}

struct GeneratedProject: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var path: String
    var template: String
    var buildSystem: String
    var packageName: String
    var generatedAt: String
    var cliVersion: String?
    var sdkVersion: String?
    var promptPath: String?
}

struct RegistryState: Codable, Sendable {
    var projects: [GeneratedProject] = []
}

struct CliStatus: Equatable, Sendable {
    var executablePath: String
    var version: String?
    var latestVersion: String?
    var updated: Bool
    var message: String
}

struct CommandResult: Sendable {
    var exitCode: Int32
    var output: String

    var successful: Bool { exitCode == 0 }
}

struct FluxzeroNewProjectLink: Sendable {
    var name: String?
    var prompt: String?
    var template: String?
    var location: String?
    var agentChoice: AgentChoice?
}

enum FluxzeroDirectLink: Sendable {
    case open(path: String, prompt: String?, agentChoice: AgentChoice)
    case create(
        name: String?,
        template: String?,
        location: String?,
        groupId: String?,
        artifactId: String?,
        packageName: String?,
        description: String?,
        buildSystem: DesktopBuildSystem?,
        initGit: Bool,
        prompt: String?,
        agentChoice: AgentChoice
    )
}

enum FluxzeroDeepLink: Sendable {
    case new(FluxzeroNewProjectLink)
    case direct(FluxzeroDirectLink)
}
