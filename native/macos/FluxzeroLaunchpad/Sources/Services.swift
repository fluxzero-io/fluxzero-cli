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
    case javaInstallFailed(String)

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
        case .javaInstallFailed(let message):
            "Could not prepare Java 25.\n\(message)"
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

private final class CommandOutputBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private var data = Data()

    func append(_ chunk: Data) {
        guard !chunk.isEmpty else { return }
        lock.lock()
        data.append(chunk)
        lock.unlock()
    }

    var stringValue: String {
        lock.lock()
        let value = String(data: data, encoding: .utf8) ?? ""
        lock.unlock()
        return value
    }
}

struct CommandRunner: Sendable {
    func run(_ command: [String], timeout: TimeInterval = 300) throws -> CommandResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: command[0])
        process.arguments = Array(command.dropFirst())

        let outputPipe = Pipe()
        process.standardOutput = outputPipe
        process.standardError = outputPipe
        process.standardInput = Pipe()

        let output = CommandOutputBuffer()
        let outputHandle = outputPipe.fileHandleForReading
        outputHandle.readabilityHandler = { handle in
            output.append(handle.availableData)
        }
        defer {
            outputHandle.readabilityHandler = nil
            try? outputHandle.close()
        }

        try process.run()

        let deadline = Date().addingTimeInterval(timeout)
        while process.isRunning && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        if process.isRunning {
            process.terminate()
            process.waitUntilExit()
            outputHandle.readabilityHandler = nil
            output.append(outputHandle.readDataToEndOfFile())
            return CommandResult(exitCode: -1, output: "Command timed out: \(command.joined(separator: " "))\n\(output.stringValue)")
        }

        process.waitUntilExit()
        outputHandle.readabilityHandler = nil
        output.append(outputHandle.readDataToEndOfFile())
        return CommandResult(
            exitCode: process.terminationStatus,
            output: output.stringValue
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

final class CliRuntimeService: Sendable {
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
        if let bundled = bundledCliExecutable(), FileManager.default.isExecutableFile(atPath: bundled.fsPath) {
            let version = version(for: bundled)
            return CliStatus(
                executablePath: bundled.fsPath,
                version: version,
                latestVersion: version,
                updated: false,
                message: "Fluxzero Launchpad is up to date"
            )
        }

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
                    message: "Fluxzero Launchpad is up to date"
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
        let executable = bundledCliExecutable() ?? paths.cliExecutable
        guard FileManager.default.isExecutableFile(atPath: executable.fsPath) else { return [] }
        guard let result = try? runner.run([executable.fsPath, "templates", "list"]), result.successful else { return [] }
        return result.output
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "-", with: "", options: .anchored) }
            .map { String($0.split(separator: ":", maxSplits: 1).first ?? "") }
            .filter { !$0.isEmpty && !$0.localizedCaseInsensitiveHasPrefix("A new version") && !$0.localizedCaseInsensitiveHasPrefix("WARNING") }
    }

    func installedVersion() -> String? {
        version(for: paths.cliExecutable)
    }

    private func version(for executable: URL) -> String? {
        guard FileManager.default.isExecutableFile(atPath: executable.fsPath) else { return nil }
        guard let result = try? runner.run([executable.fsPath, "version"], timeout: 15), result.successful else { return nil }
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

    private func bundledCliExecutable() -> URL? {
        [
            "fz-\(NativePlatform.currentArchitecture)",
            "fz-universal",
            "flux-macos-\(NativePlatform.currentArchitecture)"
        ]
        .compactMap { resource in
            Bundle.main.url(forResource: resource, withExtension: nil, subdirectory: "FluxzeroCLI")
        }
        .first
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

private actor JavaInstallGate {
    private var inFlight: Task<JavaRuntimeStatus, Error>?

    func run(_ operation: @escaping @Sendable () async throws -> JavaRuntimeStatus) async throws -> JavaRuntimeStatus {
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

final class DevelopmentDependencyService: Sendable {
    private static let javaInstallGate = JavaInstallGate()

    private let paths: AppPaths
    private let runner: CommandRunner

    init(paths: AppPaths = .detect(), runner: CommandRunner = CommandRunner()) {
        self.paths = paths
        self.runner = runner
    }

    func isGitAvailable() -> Bool {
        guard !Self.envFlag("FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_GIT") else { return false }
        return gitCandidates().contains { FileManager.default.isExecutableFile(atPath: $0) }
    }

    func detectJava25() -> JavaRuntimeStatus {
        if !Self.envFlag("FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA"),
           let system = systemJavaHome() {
            return system
        }
        if let managed = managedJavaHome() {
            return managed
        }
        return .missing
    }

    func ensureJava25() async throws -> JavaRuntimeStatus {
        try await Self.javaInstallGate.run { [self] in
            if let ready = readyJavaHomeBeforeInstall() {
                return ready
            }
            try await installJava25()
            guard let managed = managedJavaHome() else {
                throw LaunchpadError.javaInstallFailed("Installed Java could not be verified.")
            }
            return managed
        }
    }

    private func readyJavaHomeBeforeInstall() -> JavaRuntimeStatus? {
        if !Self.envFlag("FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA"),
           let system = systemJavaHome() {
            return system
        }
        return managedJavaHome()
    }

    private func gitCandidates() -> [String] {
        var paths = (ProcessInfo.processInfo.environment["PATH"] ?? "")
            .split(separator: ":")
            .map { URL(fileURLWithPath: String($0)).appending(path: "git").fsPath }
        paths += [
            "/opt/homebrew/bin/git",
            "/usr/local/bin/git",
            "/Library/Developer/CommandLineTools/usr/bin/git",
            "/Applications/Xcode.app/Contents/Developer/usr/bin/git"
        ]
        return Array(Set(paths))
    }

    private func systemJavaHome() -> JavaRuntimeStatus? {
        guard FileManager.default.isExecutableFile(atPath: "/usr/libexec/java_home"),
              let result = try? runner.run(["/usr/libexec/java_home", "-v", "25+"], timeout: 10),
              result.successful,
              let homePath = result.output
                .split(whereSeparator: \.isNewline)
                .map({ String($0).trimmingCharacters(in: .whitespacesAndNewlines) })
                .last(where: { $0.hasPrefix("/") }) else {
            return nil
        }
        guard let version = javaVersion(homePath: homePath) else { return nil }
        return JavaRuntimeStatus(homePath: homePath, source: .system, version: version)
    }

    private func managedJavaHome() -> JavaRuntimeStatus? {
        let home = managedJdkBundle().appending(path: "Contents").appending(path: "Home")
        guard let version = javaVersion(homePath: home.fsPath) else { return nil }
        return JavaRuntimeStatus(homePath: home.fsPath, source: .managed, version: version)
    }

    private func javaVersion(homePath: String) -> String? {
        let java = URL(fileURLWithPath: homePath).appending(path: "bin").appending(path: "java")
        guard FileManager.default.isExecutableFile(atPath: java.fsPath),
              let result = try? runner.run([java.fsPath, "-version"], timeout: 10),
              result.successful else {
            return nil
        }
        let output = result.output
        guard majorJavaVersion(output) >= 25 else { return nil }
        return output.firstMatch(pattern: #"version\s+"([^"]+)""#)
    }

    private func majorJavaVersion(_ versionOutput: String) -> Int {
        guard let version = versionOutput.firstMatch(pattern: #"version\s+"([^"]+)""#) else { return 0 }
        if version.hasPrefix("1.") {
            return Int(version.split(separator: ".").dropFirst().first ?? "") ?? 0
        }
        return Int(version.split(separator: ".").first ?? "") ?? 0
    }

    private func installJava25() async throws {
        if let source = configuredJavaSource() {
            try installJdk(from: source)
            return
        }
        try await downloadAndInstallJava25()
    }

    private func configuredJavaSource() -> URL? {
        ProcessInfo.processInfo.environment["FLUXZERO_LAUNCHPAD_JAVA_SOURCE"]
            .flatMap(\.nilIfBlank)
            .map { URL(fileURLWithPath: $0).standardizedFileURL }
    }

    private func managedJdkBundle() -> URL {
        if let override = ProcessInfo.processInfo.environment["FLUXZERO_LAUNCHPAD_JAVA_INSTALL_DIR"]?.nilIfBlank {
            let url = URL(fileURLWithPath: override).standardizedFileURL
            return url.pathExtension == "jdk" ? url : url.appending(path: "fluxzero-temurin-25.jdk")
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appending(path: "Library")
            .appending(path: "Java")
            .appending(path: "JavaVirtualMachines")
            .appending(path: "fluxzero-temurin-25.jdk")
    }

    private func installJdk(from source: URL) throws {
        if source.pathExtension == "gz" || source.pathExtension == "tgz" {
            try installJdkArchive(source)
            return
        }
        let bundle = try normalizedJdkBundle(from: source)
        try installJdkBundle(bundle)
    }

    private func normalizedJdkBundle(from source: URL) throws -> URL {
        let source = source.standardizedFileURL
        if FileManager.default.isExecutableFile(atPath: source.appending(path: "Contents/Home/bin/java").fsPath) {
            return source
        }
        if source.lastPathComponent == "Home",
           FileManager.default.isExecutableFile(atPath: source.appending(path: "bin/java").fsPath) {
            return source.deletingLastPathComponent().deletingLastPathComponent()
        }
        if FileManager.default.isExecutableFile(atPath: source.appending(path: "bin/java").fsPath) {
            let staging = stagingJdkBundle()
            try? FileManager.default.removeItem(at: staging)
            try FileManager.default.createDirectory(at: staging.appending(path: "Contents"), withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: source, to: staging.appending(path: "Contents/Home"))
            return staging
        }
        throw LaunchpadError.javaInstallFailed("Java source does not look like a JDK: \(source.fsPath)")
    }

    private func installJdkBundle(_ sourceBundle: URL) throws {
        let target = managedJdkBundle()
        let staging = stagingJdkBundle()
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.copyItem(at: sourceBundle, to: staging)
        guard FileManager.default.isExecutableFile(atPath: staging.appending(path: "Contents/Home/bin/java").fsPath) else {
            try? FileManager.default.removeItem(at: staging)
            throw LaunchpadError.javaInstallFailed("Staged JDK is missing Contents/Home/bin/java.")
        }
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: staging, to: target)
    }

    private func installJdkArchive(_ archive: URL) throws {
        let extractionDir = paths.appDataDir
            .appending(path: "tmp")
            .appending(path: "jdk-\(UUID().uuidString)")
        try? FileManager.default.removeItem(at: extractionDir)
        try FileManager.default.createDirectory(at: extractionDir, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.removeItem(at: extractionDir)
        }

        let result = try runner.run(["/usr/bin/tar", "-xzf", archive.fsPath, "-C", extractionDir.fsPath], timeout: 600)
        guard result.successful else {
            throw LaunchpadError.javaInstallFailed(result.output)
        }
        guard let bundle = findExtractedJdkBundle(in: extractionDir) else {
            throw LaunchpadError.javaInstallFailed("Downloaded archive did not contain a macOS JDK bundle.")
        }
        try installJdkBundle(bundle)
    }

    private func findExtractedJdkBundle(in root: URL) -> URL? {
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }
        for case let url as URL in enumerator {
            guard (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
            if FileManager.default.isExecutableFile(atPath: url.appending(path: "Contents/Home/bin/java").fsPath) {
                return url
            }
        }
        return nil
    }

    private func downloadAndInstallJava25() async throws {
        let (downloadedFile, response) = try await URLSession.shared.download(from: temurinDownloadURL())
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LaunchpadError.javaInstallFailed("Could not download Temurin JDK 25.")
        }
        try installJdkArchive(downloadedFile)
    }

    private func temurinDownloadURL() -> URL {
        let architecture = NativePlatform.currentArchitecture == "arm64" ? "aarch64" : "x64"
        return URL(string: "https://api.adoptium.net/v3/binary/latest/25/ga/mac/\(architecture)/jdk/hotspot/normal/eclipse?project=jdk")!
    }

    private func stagingJdkBundle() -> URL {
        managedJdkBundle()
            .deletingLastPathComponent()
            .appending(path: ".fluxzero-temurin-25-\(UUID().uuidString).staged.jdk")
    }

    private static func envFlag(_ name: String) -> Bool {
        switch ProcessInfo.processInfo.environment[name]?.lowercased() {
        case "1", "true", "yes", "y": true
        default: false
        }
    }
}

final class ProjectRegistry: Sendable {
    private let registryFile: URL
    private let queue = DispatchQueue(label: "io.fluxzero.launchpad.project-registry")

    init(registryFile: URL = AppPaths.detect().registryFile) {
        self.registryFile = registryFile
    }

    func listProjects() -> [GeneratedProject] {
        queue.sync {
            listProjectsUnlocked()
        }
    }

    func saveProject(_ project: GeneratedProject) throws {
        try queue.sync {
            let updated = ([project] + listProjectsUnlocked().filter { $0.path != project.path })
                .sorted { $0.generatedAt > $1.generatedAt }
            try writeUnlocked(RegistryState(projects: updated))
        }
    }

    func removeProject(_ project: GeneratedProject) throws {
        try queue.sync {
            let updated = listProjectsUnlocked().filter { $0.id != project.id && $0.path != project.path }
            try writeUnlocked(RegistryState(projects: updated))
        }
    }

    private func listProjectsUnlocked() -> [GeneratedProject] {
        guard let data = try? Data(contentsOf: registryFile) else { return [] }
        let state = try? JSONDecoder().decode(RegistryState.self, from: data)
        return (state?.projects ?? []).sorted { $0.generatedAt > $1.generatedAt }
    }

    private func writeUnlocked(_ state: RegistryState) throws {
        try FileManager.default.createDirectory(at: registryFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(state)
        try data.write(to: registryFile, options: .atomic)
    }
}

struct PromptWriter: Sendable {
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

struct ProjectGenerator: Sendable {
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

struct AgentLauncher: Sendable {
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
        case .cursor:
            return try launchCursor(projectPath: projectPath)
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

    func launchCursor(projectPath: String) throws -> AgentLaunchResult {
        if let cursor = findExecutable("cursor") {
            let result = try CommandRunner().run([cursor, projectPath], timeout: 15)
            if result.successful {
                return AgentLaunchResult(openedCursor: true)
            }
        }

        let projectURL = URL(fileURLWithPath: projectPath)
        if let appURL = cursorAppURL() {
            let configuration = NSWorkspace.OpenConfiguration()
            NSWorkspace.shared.open([projectURL], withApplicationAt: appURL, configuration: configuration)
            return AgentLaunchResult(openedCursor: true)
        }

        NSWorkspace.shared.open(cursorDownloadURL())
        return AgentLaunchResult(openedCursorDownload: true)
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
        var candidates = (ProcessInfo.processInfo.environment["PATH"] ?? "")
            .split(separator: ":")
            .map { URL(fileURLWithPath: String($0)).appending(path: name).fsPath }
        candidates += [
            "/opt/homebrew/bin/\(name)",
            "/usr/local/bin/\(name)"
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private func cursorAppURL() -> URL? {
        [
            "/Applications/Cursor.app",
            "\(NSHomeDirectory())/Applications/Cursor.app"
        ]
        .map { URL(fileURLWithPath: $0) }
        .first { FileManager.default.fileExists(atPath: $0.fsPath) }
    }

    private func codexDownloadURL() -> URL {
        #if arch(arm64)
        URL(string: "https://persistent.oaistatic.com/codex-app-prod/Codex.dmg")!
        #else
        URL(string: "https://persistent.oaistatic.com/codex-app-prod/Codex-latest-x64.dmg")!
        #endif
    }

    private func cursorDownloadURL() -> URL {
        URL(string: "https://www.cursor.com/downloads")!
    }

    private let defaultPrompt = "Open START_PROMPT.md and help me continue from there."
}

struct AgentLaunchResult: Sendable {
    var openedCodex = false
    var openedCodexDownload = false
    var openedClaude = false
    var openedCursor = false
    var openedCursorDownload = false
    var openedFinder = false

    func merged(with other: AgentLaunchResult) -> AgentLaunchResult {
        AgentLaunchResult(
            openedCodex: openedCodex || other.openedCodex,
            openedCodexDownload: openedCodexDownload || other.openedCodexDownload,
            openedClaude: openedClaude || other.openedClaude,
            openedCursor: openedCursor || other.openedCursor,
            openedCursorDownload: openedCursorDownload || other.openedCursorDownload,
            openedFinder: openedFinder || other.openedFinder
        )
    }
}

struct DeepLinkActionRunner: Sendable {
    private let paths: AppPaths
    private let cliRuntime: CliRuntimeService
    private let registry: ProjectRegistry
    private let agentLauncher: AgentLauncher

    init(
        paths: AppPaths = .detect(),
        cliRuntime: CliRuntimeService? = nil,
        registry: ProjectRegistry? = nil,
        agentLauncher: AgentLauncher = AgentLauncher()
    ) {
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
        case "open", "codex", "claude", "claude-code", "cursor":
            guard let path = params["path"] ?? params["location"] else { return nil }
            let agent: AgentChoice = switch command?.lowercased() {
            case "codex": .codex
            case "claude", "claude-code": .claude
            case "cursor": .cursor
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
        case "cursor": self = .cursor
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
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
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
