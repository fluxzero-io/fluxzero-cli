package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeepLinkRouterTest {
    @Test
    fun parsesFluxzeroNewProjectLink() {
        val link = assertNotNull(DeepLinkRouter.parse(
            URI("fluxzero://new?name=My%20App&prompt=Build%20a%20CRM&template=flux-basic-kotlin&location=%2Ftmp%2FFluxzero&agent=codex")
        ) as? FluxzeroNewProjectLink)

        assertEquals("My App", link.name)
        assertEquals("Build a CRM", link.prompt)
        assertEquals("flux-basic-kotlin", link.template)
        assertEquals("/tmp/Fluxzero", link.location)
        assertEquals(AgentChoice.CODEX, link.agentChoice)
    }

    @Test
    fun parsesFluxzeroOpenAgentLink() {
        val link = assertNotNull(DeepLinkRouter.parse(
            URI("fluxzero://open?path=%2FUsers%2Falice%2Fdemo&prompt=Ship%20it&agent=claude")
        ) as? FluxzeroOpenAgentLink)

        assertEquals("/Users/alice/demo", link.path)
        assertEquals("Ship it", link.prompt)
        assertEquals(AgentChoice.CLAUDE, link.agentChoice)
    }

    @Test
    fun parsesFluxzeroCreateProjectLink() {
        val link = assertNotNull(DeepLinkRouter.parse(
            URI("fluxzero://create?name=My%20App&prompt=Build%20it&build=gradle&git=false&agent=both")
        ) as? FluxzeroCreateProjectLink)

        assertEquals("My App", link.name)
        assertEquals("Build it", link.prompt)
        assertEquals(AgentChoice.BOTH, link.agentChoice)
        assertEquals(host.flux.desktop.model.DesktopBuildSystem.GRADLE, link.buildSystem)
        assertEquals(false, link.initGit)
    }

    @Test
    fun ignoresUnsupportedLinks() {
        assertNull(DeepLinkRouter.parse(URI("https://fluxzero.io/new")))
        assertNull(DeepLinkRouter.parse(URI("fluxzero://upgrade?path=%2Ftmp%2Fdemo")))
    }
}
