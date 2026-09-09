package io.github.darleywey.honoendpoints.endpoints

import com.intellij.ide.util.EditSourceUtil
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup

class HonoNavigationTest : BasePlatformTestCase() {
    fun testChainedRoutesNavigateToTheirOwnLinesAfterMount() {
        val file = myFixture.addFileToProject("app.mts", """
            import { Hono } from 'hono'
            const app = new Hono()
                .route('/child', child)
                .get('/health', health)
                .post('/items', createItem)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertEquals(listOf("/health", "/items"), group.endpoints.map { it.path })
        for (endpoint in group.endpoints) {
            assertNavigation(provider, group, endpoint, file, "'${endpoint.path}'")
        }
    }

    fun testRepeatedPathInDifferentMethodsHasDifferentNavigationOffsets() {
        val file = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            new Hono()
                .get('/items', listItems)
                .post('/items', createItem)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertEquals(listOf("GET", "POST"), group.endpoints.map { it.method })
        assertNavigation(provider, group, group.endpoints[0], file, "'/items'")
        assertNavigation(provider, group, group.endpoints[1], file, "'/items'", lastOccurrence = true)
    }

    fun testCompositionLayerNavigatesPastRepeatedMountPrefixes() {
        val file = myFixture.addFileToProject("app.mts", """
            import { Hono } from 'hono'
            const documented = new Hono().route('/', child)
            const app = documented.route('/', anotherChild)
            app.get('/', health)
            app.get('/json', json)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertEquals(listOf("/", "/json"), group.endpoints.map { it.path })
        assertNavigation(provider, group, group.endpoints[0], file, "'/'", lastOccurrence = true)
        assertNavigation(provider, group, group.endpoints[1], file, "'/json'")
    }

    fun testChildAndParentRoutesKeepTheirOwnSourceFiles() {
        val child = myFixture.addFileToProject("routes/child.mts", """
            import { Hono } from 'hono'
            export const child = new Hono()
                .get('/details', details)
        """.trimIndent())
        val parent = myFixture.addFileToProject("app.mts", """
            import { Hono } from 'hono'
            import { child } from './routes/child.mjs'
            new Hono()
                .route('/child', child)
                .get('/health', health)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val groups = provider.getEndpointGroups(project, ExternalEndpointsFilter).associateBy { it.file.name }
        assertEquals(setOf("app.mts", "child.mts"), groups.keys)
        val childGroup = groups.getValue("child.mts")
        val parentGroup = groups.getValue("app.mts")
        assertNavigation(provider, childGroup, childGroup.endpoints.single(), child, "'/details'")
        assertNavigation(provider, parentGroup, parentGroup.endpoints.single(), parent, "'/health'")
    }

    fun testCommonJsRouterAliasNavigatesPastMiddleware() {
        val file = myFixture.addFileToProject("app.cjs", """
            const { Hono: App } = require('hono')
            const app = new App()
            const router = app
            router
                .use('*', middleware)
                .get('/alias', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertNavigation(provider, group, group.endpoints.single(), file, "'/alias'")
    }

    fun testMultilineArgumentNavigatesToThePathLine() {
        val file = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            new Hono().get(
                "/multiline",
                handler,
            )
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertNavigation(provider, group, group.endpoints.single(), file, "\"/multiline\"")
    }

    fun testNavigationUpdatesAfterLinesAreInserted() {
        val file = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            new Hono()
                .get('/moving', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val before = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val originalOffset = before.endpoints.single().target.textOffset
        assertNavigation(provider, before, before.endpoints.single(), file, "'/moving'")
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val prefix = "// inserted before the route\n\n"
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, prefix) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val after = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertEquals(originalOffset + prefix.length, after.endpoints.single().target.textOffset)
        assertNavigation(provider, after, after.endpoints.single(), file, "'/moving'")
    }

    fun testInvalidNavigationTargetIsRejectedEvenWhenSourceIsValid() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/health', handler)")
        val temporary = myFixture.addFileToProject("temporary.ts", "'/temporary'")
        val staleTarget = PsiTreeUtil.findChildOfType(temporary, JSLiteralExpression::class.java)!!
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = group.endpoints.single().copy(target = staleTarget)
        WriteCommandAction.runWriteCommandAction(project) { temporary.delete() }
        assertTrue(group.file.isValid)
        assertTrue(endpoint.source.isValid)
        assertFalse(staleTarget.isValid)
        assertFalse(provider.isValidEndpoint(group, endpoint))
    }

    private fun assertNavigation(
        provider: HonoEndpointsProvider,
        group: HonoEndpointGroup,
        endpoint: HonoEndpoint,
        file: PsiFile,
        literal: String,
        lastOccurrence: Boolean = false,
    ) {
        val expectedOffset = if (lastOccurrence) file.text.lastIndexOf(literal) else file.text.indexOf(literal)
        assertTrue(expectedOffset >= 0)
        val navigation = provider.getNavigationElement(group, endpoint)
        val descriptor = EditSourceUtil.getDescriptor(navigation)
        assertTrue("Expected a file descriptor, got ${descriptor?.javaClass?.simpleName}", descriptor is OpenFileDescriptor)
        descriptor as OpenFileDescriptor
        try {
            assertEquals(file.virtualFile, descriptor.file)
            assertEquals("Wrong navigation offset for ${endpoint.method} ${endpoint.path}", expectedOffset, descriptor.offset)
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            assertEquals(document.getLineNumber(expectedOffset), document.getLineNumber(descriptor.offset))
            val docs = provider.getDocumentationElement(group, endpoint)
            assertNotSame(endpoint.target, docs)
            assertTrue(docs.isValid)
            assertSame(file, docs.containingFile)
        } finally {
            descriptor.dispose()
        }
    }

    private fun provider(): HonoEndpointsProvider =
        EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
}
