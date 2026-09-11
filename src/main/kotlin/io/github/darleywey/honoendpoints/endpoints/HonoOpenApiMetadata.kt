package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.javascript.documentation.JSDocumentationUtils
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.jsdoc.JSDocComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import io.github.darleywey.honoendpoints.model.HonoEndpoint

/** Existing JSDoc metadata for OpenAPI export, independent of native Quick Documentation. */
internal object HonoOpenApiMetadata {
    data class JsDoc(
        val summary: String?,
        val description: String?,
        val tags: List<Pair<String, String>>,
    )

    fun jsDoc(endpoint: HonoEndpoint): JsDoc? {
        val comment = findJsDoc(endpoint.source) ?: return null
        val tags = comment.tags.mapNotNull { tag ->
            val name = tag.name.trim().trimStart('@').ifEmpty { null } ?: return@mapNotNull null
            val value = tag.descriptionText?.trim().orEmpty()
            name to value
        }
        val summary = tags.firstOrNull { it.first.equals("summary", ignoreCase = true) }?.second?.ifEmpty { null }
        val taggedDescription = tags.firstOrNull { it.first.equals("description", ignoreCase = true) }?.second?.ifEmpty { null }
        val body = comment.description?.descriptionText?.trim()?.ifEmpty { null }
        return JsDoc(
            summary = summary,
            description = taggedDescription ?: body,
            tags = tags.filterNot { it.first.equals("summary", ignoreCase = true) || it.first.equals("description", ignoreCase = true) },
        )
    }

    private fun findJsDoc(element: PsiElement): JSDocComment? {
        val seen = HashSet<PsiElement>()
        var current: PsiElement? = element
        repeat(16) {
            val node = current ?: return null
            if (!seen.add(node)) return null
            commentOn(node)?.let { return it }
            if (node is JSCallExpression) {
                commentOn(node.methodExpression)?.let { return it }
            }
            current = node.parent
            if (current is PsiFile) return null
        }
        return null
    }

    private fun commentOn(element: PsiElement?): JSDocComment? {
        element ?: return null
        JSDocumentationUtils.findOwnDocComment(element)?.let { return it }
        element.children.firstOrNull { it is JSDocComment }?.let { return it as JSDocComment }
        var sibling = element.prevSibling
        while (sibling != null) {
            when (sibling) {
                is JSDocComment -> return sibling
                is PsiWhiteSpace -> sibling = sibling.prevSibling
                else -> break
            }
        }
        return null
    }
}
