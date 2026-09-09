package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.resolve.JSResolveResult
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.oas.OasHttpMethod
import com.intellij.microservices.oas.OasParameterIn
import com.intellij.microservices.url.HTTP_SCHEMES
import com.intellij.microservices.url.LOCALHOST
import com.intellij.microservices.url.UrlPath
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
            assertSame(endpoint.target, provider.getNavigationElement(group, endpoint))
            assertTrue(provider.getNavigationElement(group, endpoint) is JSLiteralExpression)
            assertEquals("'${endpoint.path}'", provider.getNavigationElement(group, endpoint).text)
            assertSame((endpoint.source as JSCallExpression).methodExpression, provider.getDocumentationElement(group, endpoint))
        }
    }

    fun testUrlTargetInfoContainsHostPortAndScheme() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/hello', handler)")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val target = provider.getUrlTargetInfo(group, provider.getEndpoints(group).single()).single()
        assertEquals(HTTP_SCHEMES, target.schemes)
        assertEquals(setOf("GET"), target.methods)
        assertEquals("$LOCALHOST:3000", (target.authorities.single() as com.intellij.microservices.url.Authority.Exact).text)
        assertEquals(UrlPath.fromExactString("/hello"), target.path)
        assertTrue(target.resolveToPsiElement() is JSLiteralExpression)
        assertEquals("app.ts", target.source)
        assertTrue(target.documentationPsiElement is JSReferenceExpression)
    }

    fun testOpenApiSpecificationShowsPathAndMethod() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().post('/items', handler)")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val spec = provider.getOpenApiSpecification(group, provider.getEndpoints(group).single())
        val path = spec.paths.single()
        assertEquals("/items", path.path)
        assertEquals(OasHttpMethod.POST, path.operations.single().method)
        assertEquals("POST /items", path.operations.single().summary)
        assertTrue(path.operations.single().parameters.isEmpty())
    }

    fun testOpenApiSpecificationConvertsPathParameters() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/users/:id', handler)")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val spec = provider.getOpenApiSpecification(group, provider.getEndpoints(group).single())
        val path = spec.paths.single()
        assertEquals("/users/{id}", path.path)
        val parameter = path.operations.single().parameters.single()
        assertEquals("id", parameter.name)
        assertEquals(OasParameterIn.PATH, parameter.inPlace)
        assertTrue(parameter.isRequired)
    }

    fun testDocumentationElementIsRouteCall() {
        val file = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            function listUsers() {}
            new Hono().get('/users', listUsers)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = provider.getEndpoints(group).single()
        val docs = provider.getDocumentationElement(group, endpoint)
        assertSame((endpoint.source as JSCallExpression).methodExpression, docs)
        assertTrue(docs is JSReferenceExpression)
        assertSame(file, docs.containingFile)
    }

    fun testDocumentationElementIgnoresInlineHandler() {
        myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'; new Hono().get('/hello', (c) => c.text('ok'))")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = provider.getEndpoints(group).single()
        val docs = provider.getDocumentationElement(group, endpoint)
        assertSame((endpoint.source as JSCallExpression).methodExpression, docs)
        assertTrue(docs is JSReferenceExpression)
    }

    fun testDocumentationElementDoesNotInspectMiddlewareOrHandler() {
        val file = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            function auth() {}
            function getUser() {}
            new Hono().get('/users/:id', auth, getUser)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = provider.getEndpoints(group).single()
        val docs = provider.getDocumentationElement(group, endpoint)
        assertSame((endpoint.source as JSCallExpression).methodExpression, docs)
        assertTrue(docs is JSReferenceExpression)
        assertSame(file, docs.containingFile)
    }

    fun testNativeDocumentationKeepsOpenApiMetadataSeparate() {
        val file = myFixture.addFileToProject("ticket/routes.mts", """
            import { Hono } from 'hono'
            function ticketRoute(message, code, handle) {
                return async (c) => handle(c)
            }
            const ticketBase = new Hono()
            export const ticketRoutes = ticketBase
              /**
               * @tag Ticket
               * @summary 票根列表
               * @description 按类型分页。
               * 第二行说明。
               */
              .post(
                "/ticket/list",
                ticketRoute("票根列表暂时不可用", "TICKET_LIST_INTERNAL", ({ body }) => listTickets(body)),
              )
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val provider = EndpointsProvider.EP_NAME.extensionList.filterIsInstance<HonoEndpointsProvider>().single()
        val group = provider.getEndpointGroups(project, ExternalEndpointsFilter).single()
        val endpoint = provider.getEndpoints(group).single()
        val docs = provider.getDocumentationElement(group, endpoint)
        assertSame((endpoint.source as JSCallExpression).methodExpression, docs)
        assertTrue(docs is JSReferenceExpression)
        assertSame(file, docs.containingFile)

        val spec = provider.getOpenApiSpecification(group, endpoint)
        assertEquals("/ticket/list", spec.paths.single().path)
        val operation = spec.paths.single().operations.single()
        assertEquals("票根列表", operation.summary)
        assertEquals("按类型分页。\n第二行说明。", operation.description)
        assertEquals(listOf("Ticket"), operation.tags)
    }
}
