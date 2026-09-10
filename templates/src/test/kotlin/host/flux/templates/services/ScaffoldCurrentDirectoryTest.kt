package host.flux.templates.services

import host.flux.templates.models.ScaffoldProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaffoldCurrentDirectoryTest {
    @Test
    fun `initializes git when generating in the current directory`(@TempDir directory: Path) {
        val log = directory.resolveSibling("${directory.fileName}-scaffold.log")
        val java = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val executable = Path.of(System.getProperty("java.home"), "bin", java).toString()
        val process = ProcessBuilder(
            executable, "-cp", System.getProperty("java.class.path"), ScaffoldCurrentDirectoryFixture::class.java.name
        ).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Scaffolding did not finish")
            assertEquals(0, process.exitValue(), Files.readString(log))
            assertTrue(Files.isRegularFile(directory.resolve("pom.xml")), Files.readString(log))
            assertTrue(Files.isRegularFile(directory.resolve(".git/HEAD")), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

object ScaffoldCurrentDirectoryFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = ScaffoldService().scaffoldProject(
            ScaffoldProject(template = "flux-basic-java", name = "sample", inPlace = true, initGit = true)
        )
        check(result.success) { result.message }
        println(result.message)
    }
}
