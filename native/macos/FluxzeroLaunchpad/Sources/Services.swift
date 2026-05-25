import AppKit
import CryptoKit
import Foundation

enum LaunchpadError: LocalizedError {
    case invalidProjectName
    case missingProjectName
    case cliFailed(String)
    case projectMissing(String)
    case releaseParseFailed
    case downloadFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidProjectName:
            "Project name must contain at least one letter or number."
        case .missingProjectName:
            "Project name is required."
        case .cliFailed(let message):
            "Fluxzero CLI failed.\n\(message)"
        case .projectMissing(let message):
            "Fluxzero CLI did not generate the expected project directory.\n\(message)"
        case .releaseParseFailed:
            "Could not parse the latest Fluxzero CLI release."
        case .downloadFailed(let message):
            "Could not download Fluxzero CLI.\n\(message)"
        }
    }
}

struct AppPaths: Sendable {
    var appDataDir: URL
    var binDir: URL
    var cliExecutable: URL
    var registryFile: URL

    static func detect() -> AppPaths {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let root = home
            .appending(path: "Library")
            .appending(path: "Application Support")
            .appending(path: "Fluxzero")
            .appending(path: "Launchpad")
        let bin = root.appending(path: "bin")
        return AppPaths(
            appDataDir: root,
            binDir: bin,
            cliExecutable: bin.appending(path: "fz"),
            registryFile: root.appending(path: "projects.json")
        )
    }
}

final class CommandRunner: @unchecked Sendable {
    func run(_ command: [String], timeout: TimeInterval = 300) throws -> CommandResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: command[0])
        process.arguments = Array(command.dropFirst())

        let outputPipe = Pipe()
        process.standardOutput = outputPipe
        process.standardError = outputPipe
        process.standardInput = Pipe()

        try process.run()

        let deadline = Date().addingTimeInterval(timeout)
        while process.isRunning && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        if process.isRunning {
            process.terminate()
            return CommandResult(exitCode: -1, output: "Command timed out: \(command.joined(separator: " "))")
        }

        let data = outputPipe.fileHandleForReading.readDataToEndOfFile()
        return CommandResult(
            exitCode: process.terminationStatus,
            output: String(data: data, encoding: .utf8) ?? ""
        )
    }
}

struct GitHubRelease: Decodable, Sendable {
    struct Asset: Decodable, Sendable {
        var name: String
        var browserDownloadUrl: URL

        enum CodingKeys: String, CodingKey {
            case name
            case browserDownloadUrl = "browser_download_url"
        }
    }

    var tagName: String
    var assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case assets
    }

    func downloadURL() -> URL {
        let assetName = "flux-macos-\(NativePlatform.currentArchitecture)"
        return assets.first(where: { $0.name == assetName })?.browserDownloadUrl
            ?? URL(string: "https://github.com/fluxzero-io/fluxzero-cli/releases/download/\(tagName)/\(assetName)")!
    }
}

enum NativePlatform {
    static var currentArchitecture: String {
        #if arch(arm64)
        "arm64"
        #else
        "amd64"
        #endif
    }
}

private actor CliInstallGate {
    private var inFlight: Task<CliStatus, Error>?

    func run(_ operation: @escaping @Sendable () async throws -> CliStatus) async throws -> CliStatus {
        if let inFlight {
            return try await inFlight.value
        }
        let task = Task {
            try await operation()
        }
        inFlight = task
        defer {
            inFlight = nil
        }
        return try await task.value
    }
}

final class CliRuntimeService: @unchecked Sendable {
    private static let installGate = CliInstallGate()

    private let paths: AppPaths
    private let runner: CommandRunner
    private let latestReleaseURL = URL(string: "https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases/latest")!

    init(paths: AppPaths = .detect(), runner: CommandRunner = CommandRunner()) {
        self.paths = paths
        self.runner = runner
    }

    func ensureLatestCli() async throws -> CliStatus {
        try await Self.installGate.run { [self] in
            try await ensureLatestCliUncoordinated()
        }
    }

    private func ensureLatestCliUncoordinated() async throws -> CliStatus {
        try FileManager.default.createDirectory(at: paths.binDir, withIntermediateDirectories: true)
        removeManagedDownloadLeftovers()
        recoverLegacyTempDownload()
        let installed = installedVersion()

        do {
            let release = try await fetchLatestRelease()
            if FileManager.default.isExecutableFile(atPath: paths.cliExecutable.fsPath) && versionsEqual(installed, release.tagName) {
                return CliStatus(
                    executablePath: paths.cliExecutable.fsPath,
                    version: installed,
                    latestVersion: release.tagName,
                    updated: false,
                    message: "Fluxzero Launchpad is up to date."
                )
            }
            try await download(release.downloadURL(), to: paths.cliExecutable)
            return CliStatus(
                executablePath: paths.cliExecutable.fsPath,
                version: installedVersion() ?? release.tagName,
                latestVersion: release.tagName,
                updated: true,
                message: "Downloaded Fluxzero CLI \(release.tagName)."
            )
        } catch {
            if FileManager.default.isExecutableFile(atPath: paths.cliExecutable.fsPath) {
                return CliStatus(
                    executablePath: paths.cliExecutable.fsPath,
                    version: installed,
                    latestVersion: nil,
                    updated: false,
                    message: "Using installed CLI; latest version check failed: \(error.localizedDescription)"
                )
            }
            throw error
        }
    }

    func listTemplates() -> [String] {
        guard FileManager.default.isExecutableFile(atPath: paths.cliExecutable.fsPath) else { return [] }
        guard let result = try? runner.run([paths.cliExecutable.fsPath, "templates", "list"]), result.successful else { return [] }
        return result.output
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "-", with: "", options: .anchored) }
            .map { String($0.split(separator: ":", maxSplits: 1).first ?? "") }
            .filter { !$0.isEmpty && !$0.localizedCaseInsensitiveHasPrefix("A new version") && !$0.localizedCaseInsensitiveHasPrefix("WARNING") }
    }

    func installedVersion() -> String? {
        guard FileManager.default.isExecutableFile(atPath: paths.cliExecutable.fsPath) else { return nil }
        guard let result = try? runner.run([paths.cliExecutable.fsPath, "version"], timeout: 15), result.successful else { return nil }
        let regex = try? NSRegularExpression(pattern: #"^v?\d+\.\d+\.\d+(?:[-.A-Za-z0-9]+)?$"#)
        return result.output
            .split(whereSeparator: \.isNewline)
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .last { line in
                guard let regex else { return false }
                let range = NSRange(line.startIndex..<line.endIndex, in: line)
                return regex.firstMatch(in: line, range: range) != nil
            }
    }

    private func fetchLatestRelease() async throws -> GitHubRelease {
        let (data, response) = try await URLSession.shared.data(from: latestReleaseURL)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LaunchpadError.releaseParseFailed
        }
        return try JSONDecoder().decode(GitHubRelease.self, from: data)
    }

    private func download(_ url: URL, to target: URL) async throws {
        let (downloadedFile, response) = try await URLSession.shared.download(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LaunchpadError.downloadFailed(url.absoluteString)
        }
        let stagedExecutable = target.deletingLastPathComponent().appending(path: ".\(target.lastPathComponent).\(UUID().uuidString).staged")
        try? FileManager.default.removeItem(at: stagedExecutable)
        defer {
            try? FileManager.default.removeItem(at: stagedExecutable)
        }
        do {
            try FileManager.default.copyItem(at: downloadedFile, to: stagedExecutable)
        } catch {
            throw LaunchpadError.downloadFailed("Could not stage downloaded CLI: \(error.localizedDescription)")
        }
        guard FileManager.default.fileExists(atPath: stagedExecutable.fsPath) else {
            throw LaunchpadError.downloadFailed("Downloaded CLI staging file was not created.")
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stagedExecutable.fsPath)
        try installExecutable(from: stagedExecutable, to: target)
    }

    private func installExecutable(from stagedExecutable: URL, to target: URL) throws {
        if FileManager.default.fileExists(atPath: target.fsPath) {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: stagedExecutable, backupItemName: nil)
        } else {
            try FileManager.default.moveItem(at: stagedExecutable, to: target)
        }
    }

    private func recoverLegacyTempDownload() {
        let legacyTemp = paths.binDir.appending(path: "fz.tmp")
        guard FileManager.default.fileExists(atPath: legacyTemp.fsPath) else { return }
        if !FileManager.default.fileExists(atPath: paths.cliExecutable.fsPath) {
            do {
                try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: legacyTemp.fsPath)
                try FileManager.default.moveItem(at: legacyTemp, to: paths.cliExecutable)
                return
            } catch {
                try? FileManager.default.removeItem(at: paths.cliExecutable)
            }
        }
        try? FileManager.default.removeItem(at: legacyTemp)
    }

    private func removeManagedDownloadLeftovers() {
        guard let files = try? FileManager.default.contentsOfDirectory(at: paths.binDir, includingPropertiesForKeys: nil) else { return }
        for file in files {
            let name = file.lastPathComponent
            if name.hasPrefix("fz.") && (name.hasSuffix(".download") || name.hasSuffix(".staged")) {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    private func versionsEqual(_ left: String?, _ right: String?) -> Bool {
        guard let left, let right else { return false }
        return left.trimmingPrefix("v") == right.trimmingPrefix("v")
    }
}

final class ProjectRegistry: @unchecked Sendable {
    private let registryFile: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(registryFile: URL = AppPaths.detect().registryFile) {
        self.registryFile = registryFile
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    }

    func listProjects() -> [GeneratedProject] {
        guard let data = try? Data(contentsOf: registryFile) else { return [] }
        let state = try? decoder.decode(RegistryState.self, from: data)
        return (state?.projects ?? []).sorted { $0.generatedAt > $1.generatedAt }
    }

    func saveProject(_ project: GeneratedProject) throws {
        let updated = ([project] + listProjects().filter { $0.path != project.path })
            .sorted { $0.generatedAt > $1.generatedAt }
        try write(RegistryState(projects: updated))
    }

    func removeProject(_ project: GeneratedProject) throws {
        let updated = listProjects().filter { $0.id != project.id && $0.path != project.path }
        try write(RegistryState(projects: updated))
    }

    private func write(_ state: RegistryState) throws {
        try FileManager.default.createDirectory(at: registryFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try encoder.encode(state)
        try data.write(to: registryFile, options: .atomic)
    }
}

final class PromptWriter: @unchecked Sendable {
    static let fileName = "START_PROMPT.md"

    func write(projectDir: URL, request: GenerateProjectRequest, sdkVersion: String?) throws -> URL {
        let prompt = request.firstPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? defaultPrompt(request: request, sdkVersion: sdkVersion)
            : request.firstPrompt
        let path = projectDir.appending(path: Self.fileName)
        try prompt.write(to: path, atomically: true, encoding: .utf8)
        return path
    }

    private func defaultPrompt(request: GenerateProjectRequest, sdkVersion: String?) -> String {
        var lines = [
            "You are working in a freshly generated Fluxzero project.",
            "",
            "Project context:",
            "- Name: \(request.name)",
            "- Template: \(request.template)",
            "- Build system: \(request.buildSystem.label)",
            "- Package: \(request.packageName)"
        ]
        if let sdkVersion {
            lines.append("- Detected Fluxzero SDK: \(sdkVersion)")
        }
        lines.append("")
        lines.append("Please inspect the project and continue building from here.")
        return lines.joined(separator: "\n")
    }
}

final class ProjectGenerator: @unchecked Sendable {
    private let cliExecutable: URL
    private let registry: ProjectRegistry
    private let runner: CommandRunner
    private let promptWriter: PromptWriter

    init(cliExecutable: URL, registry: ProjectRegistry, runner: CommandRunner = CommandRunner(), promptWriter: PromptWriter = PromptWriter()) {
        self.cliExecutable = cliExecutable
        self.registry = registry
        self.runner = runner
        self.promptWriter = promptWriter
    }

    func generate(_ request: GenerateProjectRequest, cliVersion: String?) throws -> GeneratedProject {
        let normalizedName = ProjectNameNormalizer.normalize(request.name)
        guard !normalizedName.isEmpty else { throw LaunchpadError.invalidProjectName }

        let outputBaseDir = URL(fileURLWithPath: request.outputBaseDir).standardizedFileURL
        try FileManager.default.createDirectory(at: outputBaseDir, withIntermediateDirectories: true)
        let projectDir = outputBaseDir.appending(path: normalizedName)

        let result = try runner.run(buildCommand(request), timeout: 600)
        guard result.successful else {
            throw LaunchpadError.cliFailed(result.output)
        }
        guard !result.output.split(whereSeparator: \.isNewline).contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("Error:") }),
              FileManager.default.fileExists(atPath: projectDir.fsPath) else {
            throw LaunchpadError.projectMissing(result.output)
        }

        let sdkVersion = SdkVersionDetector.detect(projectDir: projectDir)
        let promptPath = try promptWriter.write(projectDir: projectDir, request: request, sdkVersion: sdkVersion)
        let project = GeneratedProject(
            id: stableProjectId(projectDir),
            name: normalizedName,
            path: projectDir.fsPath,
            template: request.template,
            buildSystem: request.buildSystem.rawValue,
            packageName: request.packageName,
            generatedAt: ISO8601DateFormatter().string(from: Date()),
            cliVersion: cliVersion,
            sdkVersion: sdkVersion,
            promptPath: promptPath.fsPath
        )
        try registry.saveProject(project)
        return project
    }

    func buildCommand(_ request: GenerateProjectRequest) -> [String] {
        var command = [
            cliExecutable.fsPath, "init",
            "--template", request.template,
            "--name", request.name,
            "--dir", URL(fileURLWithPath: request.outputBaseDir).standardizedFileURL.fsPath,
            "--package", request.packageName,
            "--build", request.buildSystem.rawValue
        ]
        if !request.groupId.trimmingCharacters(in: .whitespaces).isEmpty {
            command += ["--group-id", request.groupId]
        }
        if !request.artifactId.trimmingCharacters(in: .whitespaces).isEmpty {
            command += ["--artifact-id", request.artifactId]
        }
        if !request.description.trimmingCharacters(in: .whitespaces).isEmpty {
            command += ["--description", request.description]
        }
        if request.initGit {
            command.append("--git")
        }
        return command
    }

    private func stableProjectId(_ projectDir: URL) -> String {
        let data = Data(projectDir.standardizedFileURL.fsPath.utf8)
        let digest = SHA256.hash(data: data)
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}

enum SdkVersionDetector {
    static func detect(projectDir: URL) -> String? {
        let candidates = ["pom.xml", "build.gradle.kts", "build.gradle"]
        for candidate in candidates {
            let url = projectDir.appending(path: candidate)
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            for pattern in [
                #"<fluxzero.version>([^<]+)</fluxzero.version>"#,
                #"fluxzeroVersion\s*=\s*["']([^"']+)["']"#,
                #"io\.fluxzero[^:"']*[:"]([0-9][^"'\s)]*)"#
            ] {
                if let match = text.firstMatch(pattern: pattern) {
                    return match
                }
            }
        }
        return nil
    }
}

enum ProjectNameNormalizer {
    static func normalize(_ name: String) -> String {
        name
            .lowercased()
            .replacingOccurrences(of: #"[^a-z0-9\s_-]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"[\s_]+"#, with: "-", options: .regularExpression)
            .replacingOccurrences(of: #"-+"#, with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }
}

final class AgentLauncher: @unchecked Sendable {
    func launch(choice: AgentChoice, projectPath: String, prompt: String) throws -> AgentLaunchResult {
        switch choice {
        case .none:
            return AgentLaunchResult()
        case .finder:
            return revealProject(projectPath: projectPath)
        case .codex:
            return try launchCodex(projectPath: projectPath, prompt: prompt)
        case .claude:
            return try launchClaude(projectPath: projectPath, prompt: prompt)
        case .both:
            return try launchCodex(projectPath: projectPath, prompt: prompt)
                .merged(with: launchClaude(projectPath: projectPath, prompt: prompt))
        }
    }

    func launchCodex(projectPath: String, prompt: String) throws -> AgentLaunchResult {
        guard isCodexInstalled() else {
            NSWorkspace.shared.open(codexDownloadURL())
            return AgentLaunchResult(openedCodexDownload: true)
        }
        NSWorkspace.shared.open(codexDeepLink(projectPath: projectPath, prompt: prompt))
        return AgentLaunchResult(openedCodex: true)
    }

    func launchClaude(projectPath: String, prompt: String) throws -> AgentLaunchResult {
        NSWorkspace.shared.open(claudeDeepLink(projectPath: projectPath, prompt: prompt))
        return AgentLaunchResult(openedClaude: true)
    }

    func revealProject(projectPath: String) -> AgentLaunchResult {
        openFolder(projectPath)
        return AgentLaunchResult(openedFinder: true)
    }

    func openFolder(_ path: String) {
        NSWorkspace.shared.open(URL(fileURLWithPath: path))
    }

    func codexDeepLink(projectPath: String, prompt: String) -> URL {
        URL(string: "codex://new?path=\(projectPath.urlEncoded)&prompt=\(prompt.ifBlank(defaultPrompt).urlEncoded)")!
    }

    func claudeDeepLink(projectPath: String, prompt: String) -> URL {
        URL(string: "claude-cli://open?cwd=\(projectPath.urlEncoded)&q=\(prompt.ifBlank(defaultPrompt).urlEncoded)")!
    }

    private func isCodexInstalled() -> Bool {
        findExecutable("codex") != nil
            || FileManager.default.isExecutableFile(atPath: "/Applications/Codex.app/Contents/Resources/codex")
            || FileManager.default.isExecutableFile(atPath: "\(NSHomeDirectory())/Applications/Codex.app/Contents/Resources/codex")
    }

    private func findExecutable(_ name: String) -> String? {
        (ProcessInfo.processInfo.environment["PATH"] ?? "")
            .split(separator: ":")
            .map { URL(fileURLWithPath: String($0)).appending(path: name).fsPath }
            .first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private func codexDownloadURL() -> URL {
        #if arch(arm64)
        URL(string: "https://persistent.oaistatic.com/codex-app-prod/Codex.dmg")!
        #else
        URL(string: "https://persistent.oaistatic.com/codex-app-prod/Codex-latest-x64.dmg")!
        #endif
    }

    private let defaultPrompt = "Open START_PROMPT.md and help me continue from there."
}

struct AgentLaunchResult: Sendable {
    var openedCodex = false
    var openedCodexDownload = false
    var openedClaude = false
    var openedFinder = false

    func merged(with other: AgentLaunchResult) -> AgentLaunchResult {
        AgentLaunchResult(
            openedCodex: openedCodex || other.openedCodex,
            openedCodexDownload: openedCodexDownload || other.openedCodexDownload,
            openedClaude: openedClaude || other.openedClaude,
            openedFinder: openedFinder || other.openedFinder
        )
    }
}

final class DeepLinkActionRunner: @unchecked Sendable {
    private let paths: AppPaths
    private let cliRuntime: CliRuntimeService
    private let registry: ProjectRegistry
    private let agentLauncher: AgentLauncher

    init(paths: AppPaths = .detect(), cliRuntime: CliRuntimeService? = nil, registry: ProjectRegistry? = nil, agentLauncher: AgentLauncher = AgentLauncher()) {
        self.paths = paths
        self.cliRuntime = cliRuntime ?? CliRuntimeService(paths: paths)
        self.registry = registry ?? ProjectRegistry(registryFile: paths.registryFile)
        self.agentLauncher = agentLauncher
    }

    func run(_ link: FluxzeroDirectLink) async throws -> AgentLaunchResult {
        switch link {
        case .open(let path, let prompt, let agentChoice):
            if agentChoice == .finder {
                return agentLauncher.revealProject(projectPath: path)
            }
            return try agentLauncher.launch(choice: agentChoice, projectPath: path, prompt: prompt ?? "")
        case .create(let name, let template, let location, let groupId, let artifactId, let packageName, let description, let buildSystem, let initGit, let prompt, let agentChoice):
            let projectName = name?.nilIfBlank ?? nil
            guard let projectName else { throw LaunchpadError.missingProjectName }
            let outputBaseDir = URL(fileURLWithPath: location?.nilIfBlank ?? "\(NSHomeDirectory())/FluxzeroProjects").standardizedFileURL
            let normalizedName = ProjectNameNormalizer.normalize(projectName)
            guard !normalizedName.isEmpty else { throw LaunchpadError.invalidProjectName }
            let projectDir = outputBaseDir.appending(path: normalizedName)
            if projectDir.isNonEmptyDirectory {
                if agentChoice == .finder {
                    return agentLauncher.revealProject(projectPath: projectDir.fsPath)
                }
                return try agentLauncher.launch(
                    choice: agentChoice,
                    projectPath: projectDir.fsPath,
                    prompt: prompt?.nilIfBlank ?? projectDir.startPromptText ?? ""
                )
            }

            let artifact = artifactId?.nilIfBlank ?? ProjectNameNormalizer.normalize(projectName).ifBlank("app")
            let group = groupId?.nilIfBlank ?? "com.example"
            let selectedTemplate = template?.nilIfBlank ?? "flux-basic-java"
            let request = GenerateProjectRequest(
                template: selectedTemplate,
                name: projectName,
                outputBaseDir: outputBaseDir.fsPath,
                packageName: packageName?.nilIfBlank ?? Self.defaultPackage(groupId: group, artifactId: artifact),
                groupId: group,
                artifactId: artifact,
                description: description?.nilIfBlank ?? "A Fluxzero application",
                buildSystem: buildSystem ?? (selectedTemplate.localizedCaseInsensitiveContains("kotlin") ? .gradle : .maven),
                initGit: initGit,
                firstPrompt: prompt ?? "",
                agentChoice: agentChoice
            )
            let status = try await cliRuntime.ensureLatestCli()
            let generator = ProjectGenerator(cliExecutable: URL(fileURLWithPath: status.executablePath), registry: registry)
            do {
                let project = try generator.generate(request, cliVersion: status.version)
                if agentChoice == .finder {
                    return agentLauncher.revealProject(projectPath: project.path)
                }
                return try agentLauncher.launch(choice: agentChoice, projectPath: project.path, prompt: project.promptPath.flatMap { try? String(contentsOf: URL(fileURLWithPath: $0), encoding: .utf8) } ?? prompt ?? "")
            } catch {
                if projectDir.isNonEmptyDirectory {
                    if agentChoice == .finder {
                        return agentLauncher.revealProject(projectPath: projectDir.fsPath)
                    }
                    return try agentLauncher.launch(choice: agentChoice, projectPath: projectDir.fsPath, prompt: prompt?.nilIfBlank ?? projectDir.startPromptText ?? "")
                }
                throw error
            }
        }
    }

    private static func defaultPackage(groupId: String, artifactId: String) -> String {
        "\(groupId).\(artifactId.replacingOccurrences(of: #"[^a-z0-9]"#, with: "", options: .regularExpression).ifBlank("app"))"
            .lowercased()
            .replacingOccurrences(of: #"\.+"#, with: ".", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
    }
}

enum DeepLinkParser {
    static func parse(_ url: URL) -> FluxzeroDeepLink? {
        guard url.scheme?.lowercased() == "fluxzero" else { return nil }
        let command = url.host ?? url.pathComponents.dropFirst().first
        let params = url.queryItems
        switch command?.lowercased() {
        case "new":
            return .new(
                FluxzeroNewProjectLink(
                    name: params["name"],
                    prompt: params["prompt"],
                    template: params["template"],
                    location: params["location"] ?? params["path"],
                    agentChoice: AgentChoice(urlValue: params["agent"])
                )
            )
        case "open", "codex", "claude", "claude-code":
            guard let path = params["path"] ?? params["location"] else { return nil }
            let agent: AgentChoice = switch command?.lowercased() {
            case "codex": .codex
            case "claude", "claude-code": .claude
            default: AgentChoice(urlValue: params["agent"]) ?? .finder
            }
            return .direct(.open(path: path, prompt: params["prompt"], agentChoice: agent))
        case "create":
            return .direct(
                .create(
                    name: params["name"],
                    template: params["template"],
                    location: params["location"] ?? params["path"],
                    groupId: params["groupId"] ?? params["group"],
                    artifactId: params["artifactId"] ?? params["artifact"],
                    packageName: params["packageName"] ?? params["package"],
                    description: params["description"],
                    buildSystem: DesktopBuildSystem(urlValue: params["build"] ?? params["buildSystem"]),
                    initGit: Bool(urlValue: params["git"]) ?? true,
                    prompt: params["prompt"],
                    agentChoice: AgentChoice(urlValue: params["agent"]) ?? .finder
                )
            )
        default:
            return nil
        }
    }
}

extension AgentChoice {
    init?(urlValue: String?) {
        switch urlValue?.lowercased().replacingOccurrences(of: "_", with: "-") {
        case "finder", "folder", "open-folder", "reveal": self = .finder
        case "codex": self = .codex
        case "claude", "claude-code": self = .claude
        case "both", "all": self = .both
        case "none", "generate", "dont-open", "don't-open", "no-open": self = .none
        default: return nil
        }
    }
}

extension DesktopBuildSystem {
    init?(urlValue: String?) {
        switch urlValue?.lowercased() {
        case "maven", "mvn": self = .maven
        case "gradle": self = .gradle
        default: return nil
        }
    }
}

extension Bool {
    init?(urlValue: String?) {
        switch urlValue?.lowercased() {
        case "1", "true", "yes", "y": self = true
        case "0", "false", "no", "n": self = false
        default: return nil
        }
    }
}

extension URL {
    var fsPath: String {
        path(percentEncoded: false)
    }

    var queryItems: [String: String] {
        URLComponents(url: self, resolvingAgainstBaseURL: false)?
            .queryItems?
            .reduce(into: [String: String]()) { result, item in
                result[item.name] = item.value ?? ""
            } ?? [:]
    }

    var isNonEmptyDirectory: Bool {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: fsPath, isDirectory: &isDirectory), isDirectory.boolValue else { return false }
        return ((try? FileManager.default.contentsOfDirectory(atPath: fsPath)) ?? []).isEmpty == false
    }

    var startPromptText: String? {
        try? String(contentsOf: appending(path: PromptWriter.fileName), encoding: .utf8)
    }
}

extension String {
    var urlEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? self
    }

    var nilIfBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    func ifBlank(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }

    func firstMatch(pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(startIndex..<endIndex, in: self)
        guard let match = regex.firstMatch(in: self, range: range), match.numberOfRanges > 1 else { return nil }
        guard let swiftRange = Range(match.range(at: 1), in: self) else { return nil }
        return String(self[swiftRange])
    }

    func trimmingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }

    func localizedCaseInsensitiveHasPrefix(_ prefix: String) -> Bool {
        range(of: prefix, options: [.caseInsensitive, .diacriticInsensitive, .anchored], locale: .current) != nil
    }
}
