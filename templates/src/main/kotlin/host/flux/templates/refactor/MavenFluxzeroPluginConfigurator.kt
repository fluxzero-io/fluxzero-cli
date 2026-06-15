package host.flux.templates.refactor

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

object MavenFluxzeroPluginConfigurator {
    private const val DEFAULT_OUTPUT_TIMESTAMP = "1980-01-01T00:00:00Z"

    fun configure(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        val pom = templateRoot.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) {
            return messages
        }

        try {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(pom.toFile())
            val plugin = document.findFluxzeroPlugin()
            if (plugin == null) {
                messages.warnings.add("Maven pom.xml does not contain the fluxzero-maven-plugin; publish configuration was not added.")
                return messages
            }

            document.configureReproducibleBuildOutput()

            val configuration = plugin.firstDirectChild("configuration")
                ?: plugin.appendElement(document, "configuration")
            configuration.setDirectChildText(document, "packageName", variables.finalArtifactId)
            variables.applicationId?.takeIf { it.isNotBlank() }?.let {
                configuration.setDirectChildText(document, "applicationId", it)
            }

            document.removeWhitespaceTextNodes()
            TransformerFactory.newInstance()
                .newTransformer()
                .apply {
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                }
                .transform(DOMSource(document), StreamResult(pom.toFile()))
        } catch (e: Exception) {
            messages.warnings.add("Failed to configure Fluxzero Maven plugin in pom.xml: ${e.message}")
        }
        return messages
    }

    private fun Document.configureReproducibleBuildOutput() {
        val properties = documentElement.firstDirectChild("properties")
            ?: documentElement.insertElementBefore(document = this, tagName = "properties", beforeTags = setOf("dependencies", "build"))
        if (properties.firstDirectChild("project.build.outputTimestamp") == null) {
            properties.setDirectChildText(this, "project.build.outputTimestamp", DEFAULT_OUTPUT_TIMESTAMP)
        }
    }

    private fun Document.findFluxzeroPlugin(): Element? =
        (0 until getElementsByTagName("plugin").length)
            .asSequence()
            .map { getElementsByTagName("plugin").item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { plugin ->
                plugin.directChildText("groupId") == "io.fluxzero.tools" &&
                    plugin.directChildText("artifactId") == "fluxzero-maven-plugin"
            }

    private fun Element.directChildText(tagName: String): String? =
        firstDirectChild(tagName)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun Element.setDirectChildText(document: Document, tagName: String, value: String) {
        val child = firstDirectChild(tagName) ?: appendElement(document, tagName)
        child.textContent = value
    }

    private fun Element.appendElement(document: Document, tagName: String): Element {
        val child = document.createElement(tagName)
        appendChild(child)
        return child
    }

    private fun Element.insertElementBefore(document: Document, tagName: String, beforeTags: Set<String>): Element {
        val child = document.createElement(tagName)
        val nextSibling = firstDirectChild { it.tagName in beforeTags }
        if (nextSibling == null) {
            appendChild(child)
        } else {
            insertBefore(child, nextSibling)
        }
        return child
    }

    private fun Element.firstDirectChild(tagName: String): Element? =
        firstDirectChild { it.tagName == tagName }

    private fun Element.firstDirectChild(predicate: (Element) -> Boolean): Element? =
        (0 until childNodes.length)
            .asSequence()
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull(predicate)

    private fun Node.removeWhitespaceTextNodes() {
        for (index in childNodes.length - 1 downTo 0) {
            val child = childNodes.item(index)
            if (child.nodeType == Node.TEXT_NODE && child.textContent.isBlank()) {
                removeChild(child)
            } else {
                child.removeWhitespaceTextNodes()
            }
        }
    }
}
