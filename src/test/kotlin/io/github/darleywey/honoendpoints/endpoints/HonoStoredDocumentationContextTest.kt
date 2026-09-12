package io.github.darleywey.honoendpoints.endpoints

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.blockingContext
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * Characterizes the proposed public storeOriginalElement workaround.
 * Internal dispatch is test-only: it reproduces the native Endpoints panel.
 * References stay unmarked so the production context bridge cannot mask the result.
 */
class HonoStoredDocumentationContextTest : BasePlatformTestCase() {
    fun testStoringOnMethodReferenceDoesNotReachResolvedNativeTarget() {
        val (first) = configureCalls()
        val target = nativeTarget(first)
        val short = render(target)
        val editor = render(nativeTarget(first, first.referenceNameElement))
        assertTrue(editor.contains("handler:"))
        assertFalse(short == editor)

        DocumentationManager.storeOriginalElement(project, first.referenceNameElement, first)

        assertEquals(short, render(target))
        assertEquals(short, render(nativeTarget(first)))
        assertEquals(editor, render(nativeTarget(first, first.referenceNameElement)))
    }

    fun testStoringOnResolvedDeclarationRestoresOneCall() {
        val (first, second) = configureCalls()
        val shared = sharedDeclaration(first, second)
        val target = nativeTarget(first)
        val editor = render(nativeTarget(first, first.referenceNameElement))
        assertFalse(render(target) == editor)

        DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)

        assertSame(first.referenceNameElement, DocumentationManager.getOriginalElement(shared))
        assertEquals(editor, render(target))
        assertEquals(editor, render(nativeTarget(first)))
        assertEquals(editor, render(target.createPointer().dereference()!!))
    }

    fun testAlternatingCallsOverwritesExistingNativeAndEditorTargets() {
        val (first, second) = configureCalls()
        val shared = sharedDeclaration(first, second)
        val firstTarget = nativeTarget(first)
        val secondTarget = nativeTarget(second)
        val firstEditorTarget = nativeTarget(first, first.referenceNameElement)
        val secondEditorTarget = nativeTarget(second, second.referenceNameElement)
        val firstHtml = render(firstEditorTarget)
        val secondHtml = render(secondEditorTarget)
        assertFalse("The overloads must produce distinct native documentation", firstHtml == secondHtml)
        println("FIRST_EDITOR_HTML=" + firstHtml)
        println("SECOND_EDITOR_HTML=" + secondHtml)

        repeat(3) {
            DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)
            assertEquals(firstHtml, render(firstTarget))
            assertEquals(firstHtml, render(secondTarget))
            assertEquals(firstHtml, render(secondEditorTarget))

            DocumentationManager.storeOriginalElement(project, second.referenceNameElement, shared)
            assertEquals(secondHtml, render(secondTarget))
            assertEquals(secondHtml, render(firstTarget))
            assertEquals(secondHtml, render(firstEditorTarget))
            assertEquals(secondHtml, render(firstTarget.createPointer().dereference()!!))
        }
    }

    fun testDistinctNativePointersStillReadLastStoredCallContext() {
        val (first, second) = configureCalls()
        val shared = sharedDeclaration(first, second)
        val secondHtml = render(nativeTarget(second, second.referenceNameElement))
        DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)
        val firstPointer = nativeTarget(first).createPointer()
        DocumentationManager.storeOriginalElement(project, second.referenceNameElement, shared)
        val secondPointer = nativeTarget(second).createPointer()

        assertFalse(
            "Separate pointer objects do not imply isolated original context",
            firstPointer == secondPointer,
        )
        assertEquals(secondHtml, render(firstPointer.dereference()!!))
        assertEquals(secondHtml, render(secondPointer.dereference()!!))
    }

    fun testStoredOriginalTracksCallPathEdit() {
        val (first, second) = configureCalls()
        val shared = sharedDeclaration(first, second)
        DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)
        val pointer = nativeTarget(first).createPointer()
        val before = render(pointer.dereference()!!)
        val document = myFixture.editor.document
        val start = document.text.indexOf("app.post(\"/ticket/list\"") + "app.post(\"".length
        assertTrue(start >= "app.post(\"".length)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(start, start + "/ticket/list".length, "/ticket/update")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val updated = methodReferences().first()
        val expected = render(nativeTarget(updated, updated.referenceNameElement))
        assertFalse("Editing the call must change its native overload documentation", before == expected)
        assertEquals(expected, render(pointer.dereference()!!))
    }

    fun testDeletingOriginalCallLeavesDeclarationTargetAliveWithoutContext() {
        val (first, second) = configureCalls()
        val shared = sharedDeclaration(first, second)
        val short = render(nativeTarget(first))
        DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)
        val pointer = nativeTarget(first).createPointer()
        val statement = (first.parent as JSCallExpression).parent
        val range = statement.textRange
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.deleteString(range.startOffset, range.endOffset)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val remaining = methodReferences().single()
        assertNull(DocumentationManager.getOriginalElement(remaining.resolve()!!))
        val restored = pointer.dereference()
        assertNotNull("The pointer retains only the shared declaration, not the deleted route", restored)
        assertEquals(short, render(restored!!))
    }

    private fun configureCalls(): List<JSReferenceExpression> {
        myFixture.configureByText("stored-context.ts", """
            interface HandlerInterface {
                (path: "/ticket/list", handler: (context: { listing: true }) => number): { list: number };
                (path: "/ticket/create", handler: (context: { creating: true }) => string): { created: string };
                (path: "/ticket/update", handler: (context: { updating: true }) => number): { updated: number };
            }
            declare const app: { post: HandlerInterface };
            app.post("/ticket/list", context => 1);
            app.post("/ticket/create", context => "created");
        """.trimIndent())
        return methodReferences().also { assertSize(2, it) }
    }

    private fun methodReferences(): List<JSReferenceExpression> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, JSCallExpression::class.java)
            .sortedBy { it.textOffset }
            .mapNotNull { it.methodExpression as? JSReferenceExpression }
            .filter { it.referenceName == "post" }

    private fun sharedDeclaration(first: JSReferenceExpression, second: JSReferenceExpression): PsiElement {
        val shared = first.resolve()!!
        assertSame("Both calls must resolve to the same property declaration", shared, second.resolve())
        return shared
    }

    private fun nativeTarget(reference: JSReferenceExpression, original: PsiElement? = null): DocumentationTarget =
        psiDocumentationTargets(reference, original).single()

    @Suppress("DEPRECATION")
    private fun render(target: DocumentationTarget): String = runBlocking {
        blockingContext { (target.computeDocumentation() as DocumentationData).html }
    }
}
