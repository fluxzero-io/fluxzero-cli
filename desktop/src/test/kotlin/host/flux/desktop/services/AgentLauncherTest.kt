package host.flux.desktop.services

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AgentLauncherTest {
    @Test
    fun buildsClaudeDeepLinkWithEncodedPathAndPrompt() {
        val launcher = AgentLauncher(platform = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.ARM64))

        val link = launcher.buildClaudeDeepLink("/Users/alice/My App", "Open START_PROMPT.md")

        assertContains(link, "claude-cli://open")
        assertContains(link, "cwd=%2FUsers%2Falice%2FMy%20App")
        assertContains(link, "q=Open%20START_PROMPT.md")
    }

    @Test
    fun buildsCodexDeepLinkWithEncodedPathAndPrompt() {
        val launcher = AgentLauncher(platform = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.ARM64))

        val link = launcher.buildCodexDeepLink("/Users/alice/My App", "Build a CRM")

        assertContains(link, "codex://new")
        assertContains(link, "path=%2FUsers%2Falice%2FMy%20App")
        assertContains(link, "prompt=Build%20a%20CRM")
    }

    @Test
    fun selectsCodexDownloadForCurrentMacArchitecture() {
        val appleSilicon = AgentLauncher(platform = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.ARM64))
        val intel = AgentLauncher(platform = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.AMD64))
        val windows = AgentLauncher(platform = PlatformTarget(OperatingSystem.WINDOWS, CpuArchitecture.AMD64))

        assertEquals("https://persistent.oaistatic.com/codex-app-prod/Codex.dmg", appleSilicon.codexDownloadUrl())
        assertEquals("https://persistent.oaistatic.com/codex-app-prod/Codex-latest-x64.dmg", intel.codexDownloadUrl())
        assertEquals(
            "https://get.microsoft.com/installer/download/9PLM9XGG6VKS?cid=website_cta_psi",
            windows.codexDownloadUrl()
        )
    }
}
