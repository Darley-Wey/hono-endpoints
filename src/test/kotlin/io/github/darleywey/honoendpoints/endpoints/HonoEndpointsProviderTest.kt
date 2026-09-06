package io.github.darleywey.honoendpoints.endpoints

import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoEndpointsProviderTest : BasePlatformTestCase() {
    fun testRegisteredProviderCollectsContentOnlyProject() {
        val file = myFixture.addFileToProject("backend/src/app.mts", """
            import { Hono, type Context } from 'hono'
            export const app = new Hono()
                .use('*', middleware)
                .get('/hello', (c: Context) => c.text('hello'))
                .post('/items', handler)
        """.trimIndent())
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots.toList()
        sourceRoots.forEach { PsiTestUtil.removeSourceRoot(module, it) }
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertEmpty(ModuleRootManager.getInstance(module).sourceRoots)
            val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
            assertEquals(EndpointsProvider.Status.AVAILABLE, provider.getStatus(project))
            val groups = provider.getEndpointGroups(project, ExternalEndpointsFilter).toList()
            assertSize(1, groups)
            val group = groups.single()
            val endpoints = provider.getEndpoints(group).toList()
            assertEquals(listOf("/hello", "/items"), endpoints.map { it.path })
            for (endpoint in endpoints) {
                assertTrue(provider.isValidEndpoint(group, endpoint))
                assertEquals(endpoint.path, provider.getEndpointPresentation(group, endpoint).presentableText)
                assertSame(file, provider.getNavigationElement(group, endpoint).containingFile)
            }
        } finally {
            sourceRoots.forEach { PsiTestUtil.addSourceRoot(module, it) }
        }
    }

    fun testDoesNotReturnGroupsOutsideRequestedScope() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/hello', handler)")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val filter = object : com.intellij.microservices.endpoints.SearchScopeEndpointsFilter {
            override val contentSearchScope = com.intellij.psi.search.GlobalSearchScope.EMPTY_SCOPE
            override val transitiveSearchScope = com.intellij.psi.search.GlobalSearchScope.EMPTY_SCOPE
        }
        assertEmpty(HonoEndpointsProvider().getEndpointGroups(project, filter).toList())
    }

    fun testModificationTrackerChangesAfterIndexing() {
        val tracker = HonoEndpointsProvider().getModificationTracker(project)
        val before = tracker.modificationCount
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {}
        assertTrue(tracker.modificationCount > before)
    }

    fun testModuleFilterKeepsOnlyItsScope() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/hello', handler)")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val groups = HonoEndpointsProvider().getEndpointGroups(project, ModuleEndpointsFilter(module, false, false)).toList()
        assertSize(1, groups)
        assertEquals("/hello", groups.single().endpoints.single().path)
    }
}
