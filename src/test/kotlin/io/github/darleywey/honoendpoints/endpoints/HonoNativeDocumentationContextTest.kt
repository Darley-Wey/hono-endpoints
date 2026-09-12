package io.github.darleywey.honoendpoints.endpoints

import com.intellij.openapi.progress.blockingContext
import kotlinx.coroutines.runBlocking
import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.AsyncDocumentation
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.lang.javascript.psi.JSCallExpression
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import com.intellij.lang.javascript.documentation.JSDocumentationProvider
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Native rendering parity, route identity, and the original context-loss reproduction. */
class HonoNativeDocumentationContextTest : BasePlatformTestCase() {
    fun testEndpointNativeTargetRendersExactlyLikeEditorContext() {
        myFixture.configureByText("native-doc.ts", """
            interface HandlerInterface {
                <Path extends string>(path: Path, handler: (context: { path: Path }) => string): { route: Path };
            }
            declare const app: { post: HandlerInterface };
            app.po<caret>st("/ticket/list", (context) => context.path);
            app.post("/ticket/create", (context) => context.path);
        """.trimIndent())
        val original = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val reference = PsiTreeUtil.getParentOfType(original, JSReferenceExpression::class.java)!!
        val call = reference.parent as JSCallExpression
        val endpoint = HonoEndpoint("POST", "/ticket/list", call, call.arguments[0])
        fun html(originalElement: com.intellij.psi.PsiElement?): String =
            render(psiDocumentationTargets(reference, originalElement).single())

        val before = html(null)
        val editor = html(original)
        assertFalse(before == editor)
        assertTrue(editor.contains("handler:"))
        val input = HonoEndpointDocumentation.documentationElement(endpoint)
        assertSame(reference, input)
        assertTrue(psiDocumentationTargets(reference, null).single() is HonoDocumentationTarget)
        assertEquals(editor, html(null))
        assertEquals(editor, html(original))
        val pointer = psiDocumentationTargets(reference, null).single().createPointer()
        assertEquals(editor, render(pointer.dereference()!!))
        val otherCall = PsiTreeUtil.findChildrenOfType(myFixture.file, JSCallExpression::class.java).last()
        val other = HonoEndpoint("POST", "/ticket/create", otherCall, otherCall.arguments[0])
        val otherInput = HonoEndpointDocumentation.documentationElement(other)
        val otherPointer = psiDocumentationTargets(otherInput, null).single().createPointer()
        assertFalse("Different calls to the same member must keep distinct native target pointers", pointer == otherPointer)
        val bridge = HonoMethodDocumentationTargetProvider()
        assertEmpty(bridge.documentationTargets(reference, original))
    }

    fun testBridgeIsRegisteredAndIgnoresUnmarkedReferencesAndEditorContext() {
        val bridge = PsiDocumentationTargetProvider.EP_NAME.extensionList
            .filterIsInstance<HonoMethodDocumentationTargetProvider>().single()
        myFixture.configureByText("unrelated.ts", "declare const app: { post: () => void }; app.po<caret>st();")
        val original = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val reference = PsiTreeUtil.getParentOfType(original, JSReferenceExpression::class.java)!!
        assertEmpty(bridge.documentationTargets(reference, null))
        assertEmpty(bridge.documentationTargets(reference, original))
    }

    fun testNativeDocumentationWithAndWithoutCallSiteContext() {
        myFixture.configureByText("native-doc.ts", """
            interface HandlerInterface {
                <Path extends string>(path: Path, handler: (context: { path: Path }) => string): { route: Path };
            }
            declare const app: { post: HandlerInterface };
            app.po<caret>st("/ticket/list", (context) => context.path);
        """.trimIndent())
        val original = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val reference = PsiTreeUtil.getParentOfType(original, JSReferenceExpression::class.java)!!
        val resolved = reference.resolve()!!
        val docs = JSDocumentationProvider()
        val noContext = docs.generateDoc(resolved, null)
        val withContext = docs.generateDoc(resolved, original)
        println("NATIVE_DOC_NULL_CONTEXT=$noContext")
        println("NATIVE_DOC_EDITOR_CONTEXT=$withContext")
        assertNotNull(noContext)
        assertNotNull(withContext)
        assertTrue(noContext!!.contains("HandlerInterface"))
        assertFalse(noContext.contains("handler:"))
        assertTrue(withContext!!.contains("handler:"))
        assertFalse(noContext == withContext)
        // Warming native resolution by rendering the contextual form does not repair
        // a subsequent render that still has no original call-site element.
        repeat(3) {
            assertEquals(noContext, docs.generateDoc(resolved, null))
            assertEquals(withContext, docs.generateDoc(resolved, original))
        }
    }
    // Plain JUnit does not install the coroutine Job required by native documentation.
    @Suppress("DEPRECATION")
    private fun render(target: DocumentationTarget): String = runBlocking {
        val result = blockingContext { target.computeDocumentation() }
        val completed = if (result is AsyncDocumentation) result.supplier() else result
        (completed as DocumentationData).html
    }
}
