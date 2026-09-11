package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement

/** Restores the call-site context omitted by the native Endpoints panel. */
class HonoMethodDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTargets(element: PsiElement, originalElement: PsiElement?): List<DocumentationTarget> {
        // An editor request already has context. This also terminates re-entry when
        // delegating to the complete native provider chain below.
        if (originalElement != null || !element.isValid ||
            element !is JSReferenceExpression || !HonoEndpointDocumentation.isDocumentationMethod(element)
        ) return emptyList()
        val identifier = element.referenceNameElement ?: return emptyList()
        // Native JS/TS providers own overload resolution, async service results,
        // rendering and pointers. Do not resolve a handler or construct a new target.
        return psiDocumentationTargets(element, identifier)
    }
}
