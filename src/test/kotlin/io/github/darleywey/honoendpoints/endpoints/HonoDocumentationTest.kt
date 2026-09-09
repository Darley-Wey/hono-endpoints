package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.microservices.endpoints.EndpointsDocumentationProvider
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoDocumentationTest : BasePlatformTestCase() {
    fun testRegisteredProviderUsesDefaultDocumentationPanel() {
        val provider: EndpointsProvider<*, *> = provider()
        assertFalse(provider is EndpointsDocumentationProvider<*, *, *>)
    }

    fun testRouteCallIsReturnedAsDocumentationElement() {
        val file = myFixture.addFileToProject("routes.ts", """
            import { Hono } from 'hono'
            function listTickets(context: unknown) { return [] }
            new Hono().get('/tickets', listTickets)
        """.trimIndent())
        val element = documentationElement()
        assertTrue(element is JSReferenceExpression)
        assertEquals("get", (element as JSReferenceExpression).referenceName)
        assertSame(file, element.containingFile)
    }

    fun testAllHttpMethodsReturnTheirMethodReference() {
        val file = myFixture.addFileToProject("routes.mts", """
            import { Hono } from 'hono'
            const app = new Hono()
            app.get('/a', named)
            app.post('/b', async (c) => c)
            app.put('/c', ticketRoute('msg', 'CODE', ({ body }) => body))
            app.patch('/d', auth, ((alias)))
            app.delete('/e', first)
            app.head('/f', unknownHandler)
            app.options('/g', ...handlers)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertSize(7, group.endpoints)
        for (endpoint in group.endpoints) {
            val element = provider.getDocumentationElement(group, endpoint)
            assertSame((endpoint.source as JSCallExpression).methodExpression, element)
            assertTrue(element is JSReferenceExpression)
            assertSame(file, element.containingFile)
            assertSame(element, provider.getUrlTargetInfo(group, endpoint).single().documentationPsiElement)
            assertSame(endpoint.target, provider.getNavigationElement(group, endpoint))
        }
    }

    fun testChainedRoutesKeepTheirOwnMethodReferences() {
        val source = """
            import { Hono } from 'hono'
            new Hono()
                .get('/first', handler)
                .post('/second', handler)
                .get('/third', handler)
        """.trimIndent()
        val file = myFixture.addFileToProject("routes.mts", source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        assertSize(3, group.endpoints)
        val seen = mutableSetOf<PsiElement>()
        for (endpoint in group.endpoints) {
            val element = provider.getDocumentationElement(group, endpoint)
            assertSame((endpoint.source as JSCallExpression).methodExpression, element)
            assertTrue(element is JSReferenceExpression)
            assertSame(file, element.containingFile)
            assertTrue(seen.add(element))
        }
    }

    fun testImportedHandlerDoesNotChangeDocumentationFile() {
        myFixture.addFileToProject("handlers.ts", """
            export function listTickets(context: { page: number }): number { return context.page }
        """.trimIndent())
        val file = myFixture.addFileToProject("routes.ts", """
            import { Hono } from 'hono'
            import { listTickets } from './handlers'
            new Hono().get('/tickets', listTickets)
        """.trimIndent())
        val element = documentationElement()
        assertSame(file, element.containingFile)
        assertTrue(element is JSReferenceExpression)
    }

    fun testMethodReferenceDoesNotRequireAHandlerArgument() {
        myFixture.addFileToProject("routes.ts", "import { Hono } from 'hono'; new Hono().get('/tickets')")
        val element = documentationElement()
        assertTrue(element is JSReferenceExpression)
    }

    fun testWarmUpIsSkippedInUnitTestModeAndKeepsDiscoveryWorking() {
        val file = myFixture.addFileToProject("routes.ts", """
            import { Hono } from 'hono'
            new Hono().get('/tickets', (context) => 'ok')
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val group = provider().getEndpointGroups(project, ExternalEndpointsFilter).single()
        HonoTsServiceWarmup.warmRouteElements(project, group.endpoints.map { HonoEndpointDocumentation.element(it) })
        assertSize(1, group.endpoints)
    }

    fun testStaleUrlTargetDoesNotReadInvalidDocumentationPsi() {
        val file = myFixture.addFileToProject("routes.ts", """
            import { Hono } from 'hono'
            new Hono().get('/temporary', (context) => 'ok')
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val target = provider.getUrlTargetInfo(group, group.endpoints.single()).single()
        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        assertNull(target.documentationPsiElement)
        assertNull(target.resolveToPsiElement())
    }

    private fun documentationElement(): PsiElement {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = provider()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = group.endpoints.single()
        val element = provider.getDocumentationElement(group, endpoint)
        assertSame((endpoint.source as JSCallExpression).methodExpression, element)
        return element
    }

    private fun provider(): HonoEndpointsProvider =
        EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
}
