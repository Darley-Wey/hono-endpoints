package io.github.darleywey.honoendpoints.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.darleywey.honoendpoints.model.HonoEndpoint

class HonoProjectScannerTest : BasePlatformTestCase() {
    fun testNamedImportAndChainedRoutes() = assertRoutes(
        """
        import { Hono } from 'hono'
        const app = new Hono()
            .get('/users', () => {})
            .post('/users', () => {})
        app.get('/users/:id', () => {})
        """.trimIndent(),
        "GET /users", "POST /users", "GET /users/:id",
    )

    fun testSortedBySourcePosition() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono()
            .post('/b', () => {})
            .get('/a', () => {})
        """.trimIndent(),
        "POST /b", "GET /a",
    )

    fun testAliasedConstructorAndRouter() = assertRoutes(
        """
        import { Hono as App } from 'hono'
        const app = new App()
        const alias = app
        alias.get('/users', () => {})
        """.trimIndent(),
        "GET /users",
    )

    fun testImportWhitespaceDoesNotAffectDiscovery() = assertRoutes(
        """
        import { Hono as App } from /* comment */
            'hono'
        new App().get('/users', () => {})
        """.trimIndent(),
        "GET /users",
    )

    fun testJavaScriptRoutes() {
        val file = myFixture.configureByText(
            "app.js",
            """
            import { Hono } from 'hono'
            new Hono().get('/hello', () => {})
            """.trimIndent(),
        )
        assertEquals(listOf("GET /hello"), describe(HonoProjectScanner.scanFile(file)))
    }

    fun testDoesNotAcceptUnrelatedHonoConstructor() = assertRoutes(
        """
        import { Hono } from 'another-framework'
        import { cors } from 'hono/cors'
        new Hono().get('/not-hono', () => {})
        """.trimIndent(),
    )

    fun testDoesNotAcceptShadowedConstructor() = assertRoutes(
        """
        import { Hono } from 'hono'
        function install(Hono) {
            new Hono().get('/not-hono', () => {})
        }
        new Hono().get('/real', () => {})
        """.trimIndent(),
        "GET /real",
    )

    fun testDoesNotAcceptQualifiedSameNameConstructor() = assertRoutes(
        """
        import { Hono } from 'hono'
        const other = { Hono: class {} }
        new other.Hono().get('/not-hono', () => {})
        """.trimIndent(),
    )

    fun testDoesNotAcceptAnotherExportNamedHonoLocally() = assertRoutes(
        """
        import { Other as Hono } from 'hono'
        new Hono().get('/not-hono', () => {})
        """.trimIndent(),
    )

    fun testDoesNotTreatDefaultImportAsHono() = assertRoutes(
        """
        import Hono from 'hono'
        new Hono().get('/not-hono', () => {})
        """.trimIndent(),
    )

    fun testBasePathAppliesToSubsequentRoutes() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono().basePath('/api').get('/users', () => {})
        const api = new Hono().basePath('/api')
        const alias = api
        alias.post('/users', () => {})
        """.trimIndent(),
        "GET /api/users", "POST /api/users",
    )

    fun testKeepsRoutesRegisteredBeforeBasePath() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono()
            .get('/health', () => {})
            .basePath('/api')
            .get('/users', () => {})
        """.trimIndent(),
        "GET /health", "GET /api/users",
    )

    fun testIgnoresUnknownRoutersAndDynamicPaths() = assertRoutes(
        """
        import { Hono } from 'hono'
        const app = new Hono()
        app.get(getPath(), () => {})
        const unknown = createApp()
        unknown.get('/not-proven', () => {})
        """.trimIndent(),
    )

    fun testTypeOnlyImportsAreNotConstructors() {
        assertRoutes(
            """
            import type { Hono } from 'hono'
            new Hono().get('/not-hono', () => {})
            """.trimIndent(),
        )
        assertRoutes(
            """
            import { type Hono } from 'hono'
            new Hono().get('/not-hono', () => {})
            """.trimIndent(),
        )
    }

    fun testResolutionWithInstalledPackage() {
        myFixture.addFileToProject(
            "node_modules/hono/package.json",
            """{ "name": "hono", "version": "4.0.0", "types": "index.d.ts" }""",
        )
        myFixture.addFileToProject(
            "node_modules/hono/index.d.ts",
            """
            export declare class Hono {
                get(path: string, handler: any): Hono;
            }
            """.trimIndent(),
        )
        assertRoutes(
            """
            import { Hono as App } from 'hono'
            new App().get('/hello', () => {})
            """.trimIndent(),
            "GET /hello",
        )
    }

    fun testMethodNamesAreCaseSensitive() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono().GET('/not-a-hono-method', () => {})
        """.trimIndent(),
    )

    fun testSourcePointsAtRouteCall() {
        val file = myFixture.configureByText(
            "app.ts",
            """
            import { Hono } from 'hono'
            const app = new Hono()
            app.get('/users/:id', handler)
            """.trimIndent(),
        )
        val endpoints = HonoProjectScanner.scanFile(file)
        assertSize(1, endpoints)
        val endpoint = endpoints.first()
        assertSame(file, endpoint.source.containingFile)
        assertEquals("app.get('/users/:id', handler)", endpoint.source.text)
        assertEquals("'/users/:id'", endpoint.target.text)
        assertSame(endpoint.source, endpoint.target.parent.parent)
        assertEquals(endpoint.source.textRange.endOffset, endpoint.target.textRange.endOffset + ", handler)".length)
    }

    fun testNavigationTargetsPathLiteralAfterRouteMount() {
        val file = myFixture.configureByText(
            "app.ts",
            """
            import { Hono } from 'hono'
            new Hono().route('/child', child).get('/health', handler)
            """.trimIndent(),
        )
        val endpoints = HonoProjectScanner.scanFile(file)
        assertSize(1, endpoints)
        val endpoint = endpoints.first()
        assertEquals("GET /health", "${endpoint.method} ${endpoint.path}")
        assertEquals("'/health'", endpoint.target.text)
        assertEquals("new Hono().route('/child', child).get('/health', handler)", endpoint.source.text)
        assertTrue(endpoint.target.textRange.startOffset > endpoint.source.textRange.startOffset)
        assertTrue(endpoint.target.textRange.endOffset < endpoint.source.textRange.endOffset)
    }

    fun testCompositionLayerRoutesPointAtTheirOwnPathLiterals() {
        val file = myFixture.configureByText(
            "app.mts",
            """
            import { Hono } from 'hono'
            const documented = new Hono().route('/', firstChild).route('/', secondChild)
            const app = documented.route('/', thirdChild)
            app.get('/', health)
            app.get('/json', json)
            """.trimIndent(),
        )
        val endpoints = HonoProjectScanner.scanFile(file)
        assertEquals(listOf("GET /", "GET /json"), describe(endpoints))
        val health = endpoints.first { it.path == "/" && it.method == "GET" }
        val json = endpoints.first { it.path == "/json" && it.method == "GET" }
        assertEquals("'/'", health.target.text)
        assertEquals("'/json'", json.target.text)
        assertSame(file, health.target.containingFile)
        assertSame(file, json.target.containingFile)
        assertTrue(json.target.textRange.startOffset > health.target.textRange.endOffset)
        assertEquals("app.get('/', health)", health.source.text)
        assertEquals("app.get('/json', json)", json.source.text)
    }

    fun testNavigationTargetsPathLiteralAfterMiddlewareChain() {
        val file = myFixture.configureByText(
            "app.mts",
            """
            import { Hono } from 'hono'
            new Hono()
                .use('*', middleware)
                .post('/api/items', handler)
            """.trimIndent(),
        )
        val endpoints = HonoProjectScanner.scanFile(file)
        assertSize(1, endpoints)
        val endpoint = endpoints.first()
        assertEquals("'/api/items'", endpoint.target.text)
        assertTrue(endpoint.target.textRange.startOffset > endpoint.source.textRange.startOffset)
    }

    fun testParentRoutesAfterMountingChild() = assertRoutes(
        """
        import { Hono } from 'hono'
        const child = new Hono()
        const documented = new Hono().route('/child', child)
        const app = documented.route('/', anotherChild)
        app.get('/', handler)
        app.get('/json', handler)
        """.trimIndent(),
        "GET /", "GET /json",
    )

    fun testParentRouteDoesNotInheritMountPrefix() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono().route('/child', child).get('/health', handler)
        new Hono().basePath('/api').route('/child', child).get('/hidden', handler)
        """.trimIndent(),
        "GET /health", "GET /api/hidden",
    )

    fun testMtsMiddlewareChainWithReexportedDependency() {
        myFixture.addFileToProject("node_modules/hono/package.json", """
            { "name": "hono", "exports": { ".": { "types": "./dist/types/index.d.ts" } } }
        """.trimIndent())
        myFixture.addFileToProject("node_modules/hono/dist/types/index.d.ts", "export { Hono } from './hono.js'")
        myFixture.addFileToProject("node_modules/hono/dist/types/hono.d.ts", "export declare class Hono {}")
        val file = myFixture.configureByText("app.mts", """
            import { Hono, type Context } from 'hono'
            export const app = new Hono()
                .use('*', middleware)
                .post('/api/items', (c: Context) => c.json({ ok: true }))
        """.trimIndent())
        assertEquals(listOf("POST /api/items"), describe(HonoProjectScanner.scanFile(file)))
    }

    private fun assertRoutes(source: String, vararg expected: String) {
        val file = myFixture.configureByText("app.ts", source)
        assertEquals(expected.toList(), describe(HonoProjectScanner.scanFile(file)))
    }

    private fun describe(endpoints: List<HonoEndpoint>): List<String> =
        endpoints.map { "${it.method} ${it.path}" }
}
