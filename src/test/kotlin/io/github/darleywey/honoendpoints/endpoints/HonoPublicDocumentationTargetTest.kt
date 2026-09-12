package io.github.darleywey.honoendpoints.endpoints

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.psi.PsiDocumentationLinkHandler
import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.blockingContext
import com.intellij.platform.backend.documentation.AsyncDocumentation
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.ResolvedTarget
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/** Compares the public adapter with native targets; internal APIs occur only in tests. */
@Suppress("DEPRECATION")
class HonoPublicDocumentationTargetTest : BasePlatformTestCase() {
    fun testGenericCallMatchesCompleteNativeDocumentation() {
        myFixture.configureByText("generic.ts", """
            interface HandlerInterface {
                <Path extends string>(path: Path, handler: (context: { path: Path }) => string): { route: Path };
            }
            declare const app: { post: HandlerInterface };
            app.post("/ticket/list", context => context.path);
        """.trimIndent())
        val reference = references().single()
        val native = render(nativeTarget(reference))!!
        assertTrue(native.contains("handler:"))
        assertEquals(native, render(adapter(reference)))
    }

    fun testCallsKeepTheirOwnContextDespiteSharedDeclarationUserData() {
        val (first, second) = configureOverloads()
        val firstHtml = render(nativeTarget(first))
        val secondHtml = render(nativeTarget(second))
        assertFalse(firstHtml == secondHtml)
        val firstTarget = adapter(first)
        val secondTarget = adapter(second)
        val firstPointer = firstTarget.createPointer()
        val secondPointer = secondTarget.createPointer()
        val shared = first.resolve()!!
        assertSame(shared, second.resolve())
        repeat(3) {
            DocumentationManager.storeOriginalElement(project, second.referenceNameElement, shared)
            assertEquals(firstHtml, render(firstTarget))
            assertEquals(secondHtml, render(secondTarget))
            assertEquals(firstHtml, render(firstPointer.dereference()!!))
            assertSame(second.referenceNameElement, DocumentationManager.getOriginalElement(shared))
            DocumentationManager.storeOriginalElement(project, first.referenceNameElement, shared)
            assertEquals(secondHtml, render(secondPointer.dereference()!!))
            assertEquals(firstHtml, render(firstTarget))
            assertSame(first.referenceNameElement, DocumentationManager.getOriginalElement(shared))
        }
    }

    fun testPendingDocumentationUsesEditedCallSite() {
        val (first) = configureOverloads()
        val target = adapter(first)
        val pointer = target.createPointer()
        val before = render(nativeTarget(first))
        val pending = compute(target)
        assertTrue(pending is AsyncDocumentation)
        val document = myFixture.editor.document
        val start = document.text.indexOf("app.post(\"/ticket/list\"") + "app.post(\"".length
        assertTrue(start >= "app.post(\"".length)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(start, start + "/ticket/list".length, "/ticket/update")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val expected = render(nativeTarget(references().first()))
        assertFalse(before == expected)
        assertEquals(expected, finish(pending))
        assertEquals(expected, render(pointer.dereference()!!))
    }

    fun testDeletedCallInvalidatesTargetAndPendingDocumentation() {
        val (first) = configureOverloads()
        val target = adapter(first)
        val pointer = target.createPointer()
        val pending = compute(target)
        val range = (first.parent as JSCallExpression).parent.textRange
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.deleteString(range.startOffset, range.endOffset)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(pointer.dereference())
        assertNull(finish(pending))
    }

    fun testNativeTypeLinksRenderAndNavigateLikeTheNativeTarget() {
        myFixture.configureByText("linked.ts", """
            /** The returned payload. */
            interface Payload { value: string; }
            interface HandlerInterface {
                (path: "/ticket/list", handler: (value: string) => string): Payload;
            }
            declare const app: { post: HandlerInterface };
            app.post("/ticket/list", value => value);
        """.trimIndent())
        val reference = references().single()
        val native = nativeTarget(reference)
        val candidate = adapter(reference)
        val html = render(native)!!
        assertEquals(html, render(candidate))
        val links = Regex("href=[\"'](psi_element://[^\"']+)[\"']").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("Expected a real type link in native HTML: " + html, links.any { it.contains("Payload") })
        val link = links.first { it.contains("Payload") }
        val nativeLinked = (PsiDocumentationLinkHandler().resolveLink(native, link) as ResolvedTarget).target
        val linked = (HonoDocumentationLinkHandler().resolveLink(candidate, link) as ResolvedTarget).target
        assertEquals(render(nativeLinked), render(linked))
        assertEquals(nativeLinked.computePresentation().presentableText, linked.computePresentation().presentableText)
        val navigation = linked.navigatable as OpenFileDescriptor
        assertEquals(myFixture.file.virtualFile, navigation.file)
        assertEquals(myFixture.file.text.indexOf("Payload {"), navigation.offset)
    }

    fun testLinkHandlerLeavesEditorTargetsAndExternalUrlsToThePlatform() {
        val (first) = configureOverloads()
        val handler = HonoDocumentationLinkHandler()
        assertNull(handler.resolveLink(nativeTarget(first), "psi_element://HandlerInterface"))
        assertNull(handler.resolveLink(adapter(first), "https://hono.dev/docs/"))
        assertNull(handler.resolveLink(adapter(first), "psi_element://MissingType"))
    }

    fun testJavaScriptCallUsesNativeDocumentation() {
        myFixture.configureByText("route.js", """
            const app = {
                /** Register a route. */
                get(path, handler) { return handler(path); }
            };
            app.get("/ticket/list", path => path);
        """.trimIndent())
        val reference = references().single()
        val expected = render(nativeTarget(reference))
        assertNotNull(expected)
        assertEquals(expected, render(adapter(reference)))
    }

    private fun configureOverloads(): List<JSReferenceExpression> {
        myFixture.configureByText("overloads.ts", """
            interface HandlerInterface {
                (path: "/ticket/list", handler: (context: { listing: true }) => number): { list: number };
                (path: "/ticket/create", handler: (context: { creating: true }) => string): { created: string };
                (path: "/ticket/update", handler: (context: { updating: true }) => number): { updated: number };
            }
            declare const app: { post: HandlerInterface };
            app.post("/ticket/list", context => 1);
            app.post("/ticket/create", context => "created");
        """.trimIndent())
        return references().also { assertSize(2, it) }
    }

    private fun references(): List<JSReferenceExpression> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, JSCallExpression::class.java)
            .mapNotNull { it.methodExpression as? JSReferenceExpression }
            .filter { it.referenceName in setOf("post", "get") }
            .sortedBy { it.textOffset }

    private fun adapter(reference: JSReferenceExpression): HonoDocumentationTarget =
        HonoDocumentationTarget(reference, reference.referenceNameElement!!)

    private fun nativeTarget(reference: JSReferenceExpression): DocumentationTarget =
        psiDocumentationTargets(reference, reference.referenceNameElement).single()

    private fun compute(target: DocumentationTarget): DocumentationResult? = runBlocking {
        blockingContext { target.computeDocumentation() }
    }

    private fun finish(result: DocumentationResult?): String? = runBlocking {
        val completed = if (result is AsyncDocumentation) result.supplier() else result
        (completed as DocumentationData?)?.html
    }

    private fun render(target: DocumentationTarget): String? = finish(compute(target))
}
