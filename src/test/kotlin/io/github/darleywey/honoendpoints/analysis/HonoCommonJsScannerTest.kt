package io.github.darleywey.honoendpoints.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoCommonJsScannerTest : BasePlatformTestCase() {
    fun testDestructuredRequire() = assertRoutes(
        "const { Hono } = require('hono'); const app = new Hono(); app.get('/hello', handler)",
        "GET /hello",
    )

    fun testAliasedDestructuredRequire() = assertRoutes(
        "const { Hono: App } = require('hono'); new App().post('/items', handler)",
        "POST /items",
    )

    fun testCommonJsFile() {
        val file = myFixture.configureByText("app.cjs", """
            const { Hono } = require('hono')
            new Hono().use('*', middleware).get('/hello', handler)
        """.trimIndent())
        assertEquals(listOf("/hello"), HonoProjectScanner.scanFile(file).map { it.path })
    }

    fun testRejectsOtherExportAndPackage() {
        assertRoutes("const { Other: Hono } = require('hono'); new Hono().get('/fake', handler)")
        assertRoutes("const { Hono } = require('other'); new Hono().get('/fake', handler)")
    }

    fun testRejectsShadowedRequire() {
        assertRoutes("""
            function install(require) {
                const { Hono } = require('hono')
                new Hono().get('/fake', handler)
            }
        """.trimIndent())
        assertRoutes("""
            function require(name) { return { Hono: class {} } }
            const { Hono } = require('hono')
            new Hono().get('/fake', handler)
        """.trimIndent())
    }

    fun testRejectsShadowedConstructor() = assertRoutes(
        """
        const { Hono } = require('hono')
        function install(Hono) { new Hono().get('/fake', handler) }
        new Hono().get('/real', handler)
        """.trimIndent(),
        "GET /real",
    )

    fun testRejectsNestedAndDefaultBindings() {
        assertRoutes("const { nested: { Hono } } = require('hono'); new Hono().get('/fake', handler)")
        assertRoutes("const { Other: Hono = Fallback } = require('hono'); new Hono().get('/fake', handler)")
    }

    private fun assertRoutes(source: String, vararg expected: String) {
        val file = myFixture.configureByText("app.js", source)
        assertEquals(expected.toList(), HonoProjectScanner.scanFile(file).map { "${it.method} ${it.path}" })
    }
}
