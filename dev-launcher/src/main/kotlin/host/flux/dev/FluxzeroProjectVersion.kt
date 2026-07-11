package host.flux.dev

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object FluxzeroProjectVersion {
    private val versionExpression = Regex("""\$\{([^}]+)}""")

    fun detect(projectDirectory: Path): String? {
        val pom = projectDirectory.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val project = factory.newDocumentBuilder().parse(pom.toFile()).documentElement
        val properties = project.firstDirectChild("properties")?.directChildren()
            ?.associate { it.tagName to it.textContent.trim() }
            .orEmpty()
        properties["fluxzero.version"]?.takeIf { it.isNotBlank() }?.let { return resolve(it, properties) }
        properties["fluxzero-sdk.version"]?.takeIf { it.isNotBlank() }?.let { return resolve(it, properties) }

        val dependencies = project.getElementsByTagName("dependency")
        val preferredArtifacts = listOf("dev-server", "fluxzero-bom", "sdk", "fluxzero-sdk")
        for (artifact in preferredArtifacts) {
            for (index in 0 until dependencies.length) {
                val dependency = dependencies.item(index) as? Element ?: continue
                if (dependency.directChildText("groupId") == "io.fluxzero" &&
                    dependency.directChildText("artifactId") == artifact
                ) {
                    dependency.directChildText("version")?.let { return resolve(it, properties) }
                }
            }
        }
        return null
    }

    private fun resolve(value: String, properties: Map<String, String>): String? {
        var result = value.trim()
        repeat(5) {
            val match = versionExpression.find(result) ?: return result.takeIf { it.isNotBlank() }
            val replacement = properties[match.groupValues[1]] ?: return null
            result = result.replace(match.value, replacement)
        }
        return result.takeIf { versionExpression.find(it) == null && it.isNotBlank() }
    }

    private fun Element.directChildText(name: String): String? =
        firstDirectChild(name)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun Element.firstDirectChild(name: String): Element? =
        directChildren().firstOrNull { it.tagName == name }

    private fun Element.directChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
