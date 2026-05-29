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
    case finder
    case codex
    case claude
    case cursor

    static let openDestinations: [AgentChoice] = [.codex, .claude, .cursor, .finder, .none]

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "Don't open"
        case .finder: "Finder"
        case .codex: "Codex"
        case .claude: "Claude Code"
        case .cursor: "Cursor"
        }
    }

    var systemImage: String {
        switch self {
        case .none: "slash.circle"
        case .finder: "folder"
        case .codex: "sparkles"
        case .claude: "terminal"
        case .cursor: "cursorarrow"
        }
    }

    var productIconAssetName: String? {
        switch self {
        case .codex: "CodexIcon"
        case .claude: "ClaudeCodeMark"
        case .cursor: "CursorCube"
        case .finder, .none: nil
        }
    }

    var actionTitle: String {
        switch self {
        case .none: "Generate"
        case .finder, .codex, .claude, .cursor: "Open"
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
        case .create: "Create Project"
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

enum DeepLinkPresentationMode: Sendable {
    case interactive
    case background
}

struct ProjectCreationDefaults: Codable, Equatable, Sendable {
    var location: String
    var template: String
    var groupId: String
    var buildSystem: DesktopBuildSystem
    var initGit: Bool
    var agentChoice: AgentChoice

    static var fallback: ProjectCreationDefaults {
        ProjectCreationDefaults(
            location: "\(NSHomeDirectory())/FluxzeroProjects",
            template: "flux-basic-java",
            groupId: "com.example",
            buildSystem: .maven,
            initGit: true,
            agentChoice: .codex
        )
    }
}

@MainActor
final class ProjectCreationDefaultsStore {
    private let defaults: UserDefaults
    private let key = "projectCreationDefaults.v3"
    private let legacyV2Key = "projectCreationDefaults.v2"
    private let legacyV1Key = "projectCreationDefaults.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> ProjectCreationDefaults {
        guard let data = defaults.data(forKey: key),
              let settings = try? JSONDecoder().decode(ProjectCreationDefaults.self, from: data) else {
            return loadLegacySettings()
        }
        return settings
    }

    func save(_ settings: ProjectCreationDefaults) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: key)
    }

    private func loadLegacySettings() -> ProjectCreationDefaults {
        if let data = defaults.data(forKey: legacyV2Key),
           var settings = try? JSONDecoder().decode(ProjectCreationDefaults.self, from: data) {
            if settings.agentChoice == .none {
                settings.agentChoice = .finder
            }
            save(settings)
            return settings
        }

        guard let data = defaults.data(forKey: legacyV1Key),
              var settings = try? JSONDecoder().decode(ProjectCreationDefaults.self, from: data) else {
            return .fallback
        }
        if settings.agentChoice == .none {
            settings.agentChoice = .codex
        }
        save(settings)
        return settings
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

struct JavaRuntimeStatus: Equatable, Sendable {
    enum Source: String, Sendable {
        case system
        case managed
        case missing
    }

    var homePath: String?
    var source: Source
    var version: String?

    var isReady: Bool {
        homePath != nil
    }

    static let missing = JavaRuntimeStatus(homePath: nil, source: .missing, version: nil)
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
