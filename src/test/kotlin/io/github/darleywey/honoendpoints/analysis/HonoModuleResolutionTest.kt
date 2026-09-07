package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.darleywey.honoendpoints.framework.HonoSymbols

class HonoModuleResolutionTest : BasePlatformTestCase() {
    fun testNamedImportRouteArgumentResolves() {
        val users = myFixture.addFileToProject("src/users.ts", """
            import { Hono } from 'hono'
            export const users = new Hono()
                .get('/', listUsers)
                .post('/', createUser)
        """.trimIndent())
        val app = myFixture.addFileToProject("src/app.ts", """
            import { Hono } from 'hono'
            import { users } from './users.js'
            export const app = new Hono().basePath('/api').route('/users', users)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val resolver = HonoRouterResolver()
        val routeCall = PsiTreeUtil.findChildrenOfType(app, JSCallExpression::class.java)
            .single { (it.methodExpression as? JSReferenceExpression)?.referenceName == "route" }
        val specifier = PsiTreeUtil.findChildrenOfType(app, ES6ImportSpecifier::class.java)
            .single { it.declaration?.fromClause?.referenceText?.contains("users") == true }
        val fromClause = specifier.declaration!!.fromClause!!
        val imported = resolver.router(routeCall.arguments[1])
        val local = resolver.router(PsiTreeUtil.findChildOfType(users, JSNewExpression::class.java))
        assertEquals("users", specifier.referenceName)
        assertEquals("./users.js", HonoSymbols.unquote(fromClause.referenceText))
        assertNotNull(imported)
        assertNotNull(local)
        assertEquals(local, imported)
    }
}

