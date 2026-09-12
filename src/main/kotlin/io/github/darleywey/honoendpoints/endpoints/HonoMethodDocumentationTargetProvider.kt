package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement

/** Restores the call-site context omitted by the native Endpoints panel. */
class HonoMethodDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTargets(element: PsiElement, originalElement: PsiElement?): List<DocumentationTarget> {
        // Editor requests already carry context and remain owned by the IDE.
        if (originalElement != null || !element.isValid ||
            element !is JSReferenceExpression || !HonoEndpointDocumentation.isDocumentationMethod(element)
        ) return emptyList()
        val identifier = element.referenceNameElement ?: return emptyList()
        return listOf(HonoDocumentationTarget(element, identifier))
    }
}
