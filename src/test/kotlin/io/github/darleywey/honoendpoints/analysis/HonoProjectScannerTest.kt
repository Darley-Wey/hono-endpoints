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

    fun testSkipsRoutesAfterBasePath() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono().basePath('/api').get('/users', () => {})
        const api = new Hono().basePath('/api')
        const alias = api
        alias.post('/users', () => {})
        """.trimIndent(),
    )

    fun testKeepsRoutesRegisteredBeforeBasePath() = assertRoutes(
        """
        import { Hono } from 'hono'
        new Hono()
            .get('/health', () => {})
            .basePath('/api')
            .get('/users', () => {})
        """.trimIndent(),
        "GET /health",
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
        assertSame(file, endpoints.first().source.containingFile)
        assertEquals("app.get('/users/:id', handler)", endpoints.first().source.text)
    }

    private fun assertRoutes(source: String, vararg expected: String) {
        val file = myFixture.configureByText("app.ts", source)
        assertEquals(expected.toList(), describe(HonoProjectScanner.scanFile(file)))
    }

    private fun describe(endpoints: List<HonoEndpoint>): List<String> =
        endpoints.map { "${it.method} ${it.path}" }
}
