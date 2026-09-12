package io.github.darleywey.honoendpoints.endpoints

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult

/** Resolves the language provider's PSI links for our independently scoped targets. */
class HonoDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is HonoDocumentationTarget ||
            !url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
        ) return null
        val link = url.removePrefix(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
        val separator = DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL_REF_SEPARATOR
        val offset = link.lastIndexOf(separator)
        val reference = if (offset < 0) link else link.substring(0, offset)
        val anchor = if (offset < 0) null else link.substring(offset + separator.length)
        val element = target.linkedElement(reference) ?: return null
        return LinkResolveResult.resolvedTarget(HonoDocumentationTarget(element, null, anchor))
    }
}
