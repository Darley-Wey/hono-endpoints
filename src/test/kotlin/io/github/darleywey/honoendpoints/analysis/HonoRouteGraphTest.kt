package io.github.darleywey.honoendpoints.analysis

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoRouteGraphTest : BasePlatformTestCase() {
    fun testUsersMountedUnderApiBasePath() {
        myFixture.addFileToProject("src/users.ts", """
            import { Hono } from 'hono'
            export const users = new Hono()
                .get('/', listUsers)
                .get('/:id', getUser)
                .post('/', createUser)
        """.trimIndent())
        val app = myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { users } from './users.js'
            export const app = new Hono().basePath('/api').route('/users', users)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(
            listOf("GET /api/users", "GET /api/users/:id", "POST /api/users"),
            describeProject("src/users.ts"),
        )
        val endpoints = HonoProjectScanner.scanProject(project)
            .single { it.file.virtualFile.path.endsWith("/src/users.ts") }
            .endpoints
        assertEquals("'/'", endpoints.first { it.path == "/api/users" && it.method == "GET" }.target.text)
        assertEquals("'/:id'", endpoints.first { it.path.endsWith("/:id") }.target.text)
    }

    fun testSameChildMountedTwiceKeepsBothPrefixes() {
        myFixture.addFileToProject("src/child.ts", """
            import { Hono } from 'hono'
            export const child = new Hono().get('/details', handler)
        """.trimIndent())
        myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { child } from './child.js'
            const app = new Hono()
            app.route('/v1', child)
            app.route('/v2', child)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("GET /v1/details", "GET /v2/details"), describeProject("src/child.ts"))
    }

    fun testMountedChildIsNotListedBare() {
        myFixture.addFileToProject("src/child.ts", """
            import { Hono } from 'hono'
            export const child = new Hono().get('/details', handler)
        """.trimIndent())
        myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { child } from './child.js'
            new Hono().route('/child', child).get('/health', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("GET /child/details"), describeProject("src/child.ts"))
        assertEquals(listOf("GET /health"), describeProject("src/app.ts"))
    }

    fun testDefaultImportAndNodeNextExtension() {
        myFixture.addFileToProject("src/train.mts", """
            import { Hono } from 'hono'
            const train = new Hono().get('/:cityCode', handler)
            export default train
        """.trimIndent())
        myFixture.addFileToProject("src/app.mts", """
            import { Hono } from 'hono'
            import train from './train.mjs'
            new Hono().route('/train', train)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("GET /train/:cityCode"), describeProject("src/train.mts"))
    }

    fun testCommonJsMountedChild() {
        myFixture.addFileToProject("src/child.cjs", """
            const { Hono } = require('hono')
            const child = new Hono().get('/code', handler)
            module.exports = child
        """.trimIndent())
        myFixture.addFileToProject("src/app.cjs", """
            const { Hono } = require('hono')
            const child = require('./child.cjs')
            new Hono().route('/wx', child)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("GET /wx/code"), describeProject("src/child.cjs"))
    }

    fun testLateChildRegistrationIsNotCopied() {
        val file = myFixture.configureByText("app.ts", """
            import { Hono } from 'hono'
            const child = new Hono().get('/early', handler)
            const app = new Hono().route('/child', child)
            child.get('/late', handler)
            app.get('/health', handler)
        """.trimIndent())
        assertEquals(
            listOf("GET /child/early", "GET /health"),
            HonoProjectScanner.scanFile(file).map { "${it.method} ${it.path}" },
        )
    }

    fun testDiscardedBasePathDoesNotRewriteOriginalView() {
        val file = myFixture.configureByText("app.ts", """
            import { Hono } from 'hono'
            const app = new Hono()
            app.basePath('/api')
            app.get('/health', handler)
        """.trimIndent())
        assertEquals(listOf("GET /health"), HonoProjectScanner.scanFile(file).map { "${it.method} ${it.path}" })
    }

    fun testMountProvenanceIgnoresBareBasePathAndCountsRootMounts() {
        myFixture.addFileToProject("src/child.ts", """
            import { Hono } from 'hono'
            export const child = new Hono().get('/health', handler)
        """.trimIndent())
        val app = myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { child } from './child.js'
            new Hono().basePath('/api').get('/ready', handler)
            new Hono().route('/', child)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val childEndpoints = HonoProjectScanner.scanProject(project)
            .single { it.file.virtualFile.path.endsWith("/src/child.ts") }
            .endpoints
        assertEquals(listOf("GET /health"), childEndpoints.map { "${it.method} ${it.path}" })
        assertTrue(childEndpoints.single().mounted)
        val appEndpoints = HonoProjectScanner.scanFile(app)
        assertEquals(listOf("GET /api/ready"), appEndpoints.map { "${it.method} ${it.path}" })
        assertFalse(appEndpoints.single().mounted)
    }

    fun testDynamicMountPrefixIsUnresolved() {
        myFixture.addFileToProject("src/child.ts", """
            import { Hono } from 'hono'
            export const child = new Hono().get('/details', handler)
        """.trimIndent())
        myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { child } from './child.js'
            new Hono().route(prefix(), child).get('/health', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("GET /details"), describeProject("src/child.ts"))
        assertEquals(listOf("GET /health"), describeProject("src/app.ts"))
    }

    private fun describeProject(path: String): List<String> =
        HonoProjectScanner.scanProject(project)
            .single { it.file.virtualFile.path.endsWith("/$path") }
            .endpoints
            .map { "${it.method} ${it.path}" }
}
