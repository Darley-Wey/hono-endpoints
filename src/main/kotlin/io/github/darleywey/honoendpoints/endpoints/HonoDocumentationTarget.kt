package io.github.darleywey.honoendpoints.endpoints

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.model.Pointer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager

/** Owns a call's context while the language provider owns its documentation content. */
@Suppress("DEPRECATION")
internal class HonoDocumentationTarget(
    private val element: PsiElement,
    private val originalElement: PsiElement?,
    private val anchor: String? = null,
) : DocumentationTarget {
    override fun createPointer(): Pointer<HonoDocumentationTarget> = pointer()

    private fun pointer(): Pointer<HonoDocumentationTarget> {
        val targetPointer = SmartPointerManager.createPointer(element)
        val originalPointer = originalElement?.let { SmartPointerManager.createPointer(it) }
        val savedAnchor = anchor
        return Pointer {
            val restored = targetPointer.element ?: return@Pointer null
            val original = originalPointer?.let { it.element ?: return@Pointer null }
            HonoDocumentationTarget(restored, original, savedAnchor)
        }
    }

    override fun computePresentation(): TargetPresentation {
        val subject = documentationElement()
        return TargetPresentation.builder((subject as? PsiNamedElement)?.name ?: subject.containingFile?.name ?: "Hono")
            .icon(subject.getIcon(0))
            .locationText(subject.containingFile?.name)
            .presentation()
    }

    override val navigatable: Navigatable?
        get() {
            val subject = documentationElement().navigationElement
            val file = subject.containingFile?.virtualFile ?: return null
            return OpenFileDescriptor(subject.project, file, subject.textOffset)
        }

    override fun computeDocumentationHint(): String? {
        val subject = documentationElement()
        return DocumentationManager.getProviderFromElement(subject, originalElement)
            .getQuickNavigateInfo(subject, originalElement)
    }

    override fun computeDocumentation(): DocumentationResult? {
        if (!element.isValid || originalElement?.isValid == false) return null
        val saved = pointer()
        // The asynchronous callback captures pointers, so a pending request follows
        // edits and can disappear when its call site is deleted.
        return DocumentationResult.asyncDocumentation {
            readAction {
                ProgressManager.checkCanceled()
                saved.dereference()?.localDocumentation()
            }
        }
    }

    private fun localDocumentation(): DocumentationResult.Documentation? {
        val subject = documentationElement()
        val parts = DocumentationManager.getProviderFromElement(subject, originalElement)
            .getDocumentationParts(subject, originalElement) ?: return null
        ProgressManager.checkCanceled()
        var result = DocumentationResult.documentation(parts.doc)
        parts.definitionDetails?.let { result = result.definitionDetails(it) }
        anchor?.let { result = result.anchor(it) }
        return result
    }

    internal fun linkedElement(link: String): PsiElement? {
        if (!element.isValid || originalElement?.isValid == false) return null
        val subject = documentationElement()
        return DocumentationManager.getProviderFromElement(subject, originalElement)
            .getDocumentationElementForLink(subject.manager, link, subject)
    }

    private fun documentationElement(): PsiElement =
        (element as? JSReferenceExpression)?.resolve() ?: element
}
