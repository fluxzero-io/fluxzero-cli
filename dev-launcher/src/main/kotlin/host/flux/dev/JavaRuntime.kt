package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

const val REQUIRED_JAVA_FEATURE = 25

data class JavaRuntime(
    val home: Path,
    val executable: Path,
    val feature: Int
)

fun interface JavaRuntimeProvider {
    fun resolve(): JavaRuntime
}

class JavaRequiredException : IllegalStateException(
    "Java $REQUIRED_JAVA_FEATURE is needed to start Fluxzero. Install it and try again."
)

data class RuntimeCommandResult(val exitCode: Int, val output: String)

fun interface RuntimeCommandRunner {
    fun run(command: List<String>): RuntimeCommandResult?
}

class JavaRuntimeDiscovery(
    private val environment: Map<String, String> = System.getenv(),
    private val javaHome: String? = System.getProperty("java.home"),
    private val osName: String = System.getProperty("os.name"),
    private val commandRunner: RuntimeCommandRunner = RuntimeCommandRunner(::runCommand)
) : JavaRuntimeProvider {
    override fun resolve(): JavaRuntime = find() ?: throw JavaRequiredException()

    fun find(): JavaRuntime? {
        val inspected = linkedSetOf<Path>()
        fun compatible(candidate: Path?): JavaRuntime? {
            if (candidate == null || !inspected.add(candidate.toAbsolutePath().normalize())) return null
            return inspect(candidate)?.takeIf { it.feature >= REQUIRED_JAVA_FEATURE }
        }

        compatible(environment["JAVA_HOME"]?.toPathOrNull()?.let(::javaExecutable))?.let { return it }
        compatible(javaHome?.toPathOrNull()?.let(::javaExecutable))?.let { return it }
        compatible(pathExecutable("java"))?.let { return it }
        compatible(javaHomeFromMacOs()?.let(::javaExecutable))?.let { return it }
        homebrewJavaHomes().forEach { compatible(javaExecutable(it))?.let { runtime -> return runtime } }
        windowsJavaHomes().forEach { compatible(javaExecutable(it))?.let { runtime -> return runtime } }
        return null
    }

    private fun inspect(candidate: Path): JavaRuntime? {
        val executable = runCatching { candidate.toRealPath() }.getOrElse { candidate.toAbsolutePath().normalize() }
        if (!Files.isRegularFile(executable)) return null
        val home = executable.parent?.parent ?: return null
        val javac = executable.parent.resolve(if (windows()) "javac.exe" else "javac")
        if (!Files.isRegularFile(javac)) return null
        val result = commandRunner.run(listOf(executable.toString(), "-version")) ?: return null
        if (result.exitCode != 0) return null
        val feature = JAVA_VERSION.find(result.output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return JavaRuntime(home, executable, feature)
    }

    private fun pathExecutable(name: String): Path? {
        val executable = if (windows()) "$name.exe" else name
        return environment["PATH"].orEmpty().split(pathSeparator())
            .asSequence().filter(String::isNotBlank).mapNotNull { it.toPathOrNull() }
            .map { it.resolve(executable) }.firstOrNull(Files::isRegularFile)
    }

    private fun javaHomeFromMacOs(): Path? {
        if (!macOs() || !Files.isExecutable(Path.of("/usr/libexec/java_home"))) return null
        return commandRunner.run(listOf("/usr/libexec/java_home", "-v", "$REQUIRED_JAVA_FEATURE+"))
            ?.takeIf { it.exitCode == 0 }?.output?.trim()?.toPathOrNull()
    }

    private fun homebrewJavaHomes(): Sequence<Path> {
        val brew = pathExecutable("brew") ?: return emptySequence()
        return sequenceOf("openjdk@$REQUIRED_JAVA_FEATURE", "openjdk").mapNotNull { formula ->
            val prefix = commandRunner.run(listOf(brew.toString(), "--prefix", formula))
                ?.takeIf { it.exitCode == 0 }?.output?.trim()?.toPathOrNull() ?: return@mapNotNull null
            val bundledHome = prefix.resolve("libexec/openjdk.jdk/Contents/Home")
            bundledHome.takeIf(Files::isDirectory) ?: prefix
        }.distinct()
    }

    private fun windowsJavaHomes(): Sequence<Path> {
        if (!windows()) return emptySequence()
        val roots = sequenceOf(environment["ProgramFiles"], environment["ProgramW6432"])
            .filterNotNull().distinct().mapNotNull { it.toPathOrNull() }
            .map { it.resolve("Eclipse Adoptium") }.filter(Files::isDirectory)
        return roots.flatMap { root ->
            runCatching {
                Files.list(root).use { paths ->
                    paths.filter(Files::isDirectory)
                        .filter { it.fileName.toString().lowercase(Locale.ROOT).startsWith("jdk-") }
                        .sorted(Comparator.reverseOrder()).toList().asSequence()
                }
            }.getOrDefault(emptySequence())
        }
    }

    private fun javaExecutable(home: Path): Path = home.resolve("bin").resolve(if (windows()) "java.exe" else "java")

    private fun pathSeparator(): String = if (windows()) ";" else ":"

    private fun windows(): Boolean = osName.lowercase(Locale.ROOT).contains("windows")

    private fun macOs(): Boolean = osName.lowercase(Locale.ROOT).contains("mac")

    companion object {
        private val JAVA_VERSION = Regex("""(?:java|openjdk) version \"(?:1\.)?(\d+)[^\"]*\"""")

        private fun String.toPathOrNull(): Path? = takeIf(String::isNotBlank)?.let {
            runCatching { Path.of(it) }.getOrNull()
        }

        private fun runCommand(command: List<String>): RuntimeCommandResult? = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
                return@runCatching null
            }
            RuntimeCommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
        }.getOrNull()
    }
}
