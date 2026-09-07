package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.resolve.JSResolveResult
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.darleywey.honoendpoints.analysis.HonoProjectScanner
import io.github.darleywey.honoendpoints.project.HonoProjectModel

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

    fun testClassTargetWithImportProvenanceReachesProvider() {
        assertClassTargetRoutes(aliased = false, withProvenance = true)
    }

    fun testAliasedClassTargetWithoutImportProvenanceReachesProvider() {
        assertClassTargetRoutes(aliased = true, withProvenance = false)
    }

    private fun assertClassTargetRoutes(aliased: Boolean, withProvenance: Boolean) {
        val declaration = myFixture.addFileToProject("node_modules/hono/index.d.ts", "export declare class Hono {}")
        val target = PsiTreeUtil.findChildOfType(declaration, TypeScriptClass::class.java)!!
        val imported = if (aliased) "Hono as App" else "Hono"
        val name = if (aliased) "App" else "Hono"
        val file = myFixture.addFileToProject("backend/src/app.mts", """
            import { $imported } from 'hono'
            export const app = new $name()
                .use('*', middleware)
                .post('/items', handler)
                .get('/health', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val reference = PsiTreeUtil.findChildOfType(file, JSNewExpression::class.java)!!.methodExpression as JSReferenceExpression
        val specifier = PsiTreeUtil.findChildOfType(file, ES6ImportSpecifier::class.java)!!
        val result: ResolveResult = if (withProvenance) JSResolveResult(target, specifier, null) else PsiElementResolveResult(target)

        // Simulate a completed class-target result in the same dependency-aware
        // cache used by real JavaScript/TypeScript PSI references.
        JSResolveUtil.clearResolveCaches(file)
        JSResolveUtil.resolve(
            file, reference, ResolveCache.PolyVariantResolver<JSReferenceExpression> { _, _ -> arrayOf(result) }, false,
        )
        assertSame(target, reference.resolve())
        assertEquals(listOf("POST /items", "GET /health"), HonoProjectScanner.scanFile(file).map { "${it.method} ${it.path}" })
        assertSize(1, HonoProjectModel.endpointGroups(project))
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val groups = provider.getEndpointGroups(project, ExternalEndpointsFilter).toList()
        assertSize(1, groups)
        val group = groups.single()
        val endpoints = provider.getEndpoints(group).toList()
        assertEquals(listOf("/items", "/health"), endpoints.map { it.path })
        for (endpoint in endpoints) {
            assertTrue(provider.isValidEndpoint(group, endpoint))
            assertEquals(endpoint.path, provider.getEndpointPresentation(group, endpoint).presentableText)
            assertSame(file, provider.getNavigationElement(group, endpoint).containingFile)
            assertSame(endpoint.source, provider.getNavigationElement(group, endpoint))
        }
    }
}
