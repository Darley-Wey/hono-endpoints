package io.github.darleywey.honoendpoints.diagnostics

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoFileDiagnosticsTest : BasePlatformTestCase() {
    fun testReportsFlagsAndCountsWithoutSourceText() {
        val file = myFixture.addFileToProject("backend/app.mts", """
            import { Hono } from 'hono'
            const secret = 'do-not-copy-this-value'
            new Hono().get('/hello', c => c.text(secret))
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val report = HonoFileDiagnostics.collect(project, file.virtualFile)
        assertTrue(report.contains("In project content: true"))
        assertTrue(report.contains("Found by filename index: true"))
        assertTrue(report.contains("File analysis routes: 1"))
        assertTrue(report.contains("Project model routes in file: 1"))
        assertTrue(report.contains("Mounted routes in file: 0"))
        assertFalse(report.contains("do-not-copy-this-value"))

        PsiTestUtil.addExcludedRoot(module, file.virtualFile.parent)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val excludedReport = HonoFileDiagnostics.collect(project, file.virtualFile)
            assertTrue(excludedReport.contains("Excluded: true"))
            assertTrue(excludedReport.contains("File analysis routes: 1"))
            assertTrue(excludedReport.contains("Project model routes in file: 0"))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, file.virtualFile.parent)
        }
    }

    fun testReportsClassResolutionAndRecoveredBindingWithoutSourceText() {
        val declaration = myFixture.addFileToProject("node_modules/hono/index.d.ts", "export declare class Hono {}")
        val target = PsiTreeUtil.findChildOfType(declaration, TypeScriptClass::class.java)!!
        val file = myFixture.addFileToProject("backend/app.mts", """
            import { Hono } from 'hono'
            new Hono().get('/do-not-copy-this-route', () => 'do-not-copy-this-body')
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val reference = PsiTreeUtil.findChildOfType(file, JSNewExpression::class.java)!!.methodExpression as JSReferenceExpression
        JSResolveUtil.clearResolveCaches(file)
        JSResolveUtil.resolve(file, reference, ResolveCache.PolyVariantResolver<JSReferenceExpression> { _, _ ->
            arrayOf<ResolveResult>(PsiElementResolveResult(target))
        }, false)
        assertSame(target, reference.resolve())

        val report = HonoFileDiagnostics.collect(project, file.virtualFile)
        assertTrue(report.contains("File analysis routes: 1"))
        assertTrue(report.contains("Project model routes in file: 1"))
        assertTrue(report.contains("Mounted routes in file: 0"))
        assertTrue(report.contains("Hono: TypeScriptClassImpl, Hono=true"))
        assertTrue(report.contains("Resolve results: 1; result type: PsiElementResolveResult"))
        assertTrue(report.contains("Import provenance: none"))
        assertTrue(report.contains("Local binding: ES6ImportSpecifierImpl"))
        assertFalse(report.contains("do-not-copy-this-route"))
        assertFalse(report.contains("do-not-copy-this-body"))
    }

    fun testNavigationTargetsReportBoundedOffsetsWithoutRouteText() {
        val routes = (1..12).joinToString("\n") { "app.get('/private-route-$it', handler)" }
        val file = myFixture.addFileToProject("app.ts", "import { Hono } from 'hono'\nconst app = new Hono()\n$routes")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val firstTarget = PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
            .single { it.stringValue == "/private-route-1" }
        val report = HonoFileDiagnostics.collect(project, file.virtualFile)
        assertTrue(report.contains("File analysis routes: 12"))
        assertTrue(report.contains("Navigation targets (source order, up to 10):"))
        val targetLines = report.lines().filter { it.startsWith("  #") }
        assertSize(10, targetLines)
        assertTrue(targetLines.first().startsWith("  #1 GET:"))
        assertTrue(targetLines.first().contains("target=${firstTarget.javaClass.simpleName} ${firstTarget.textRange}"))
        assertTrue(targetLines.first().contains("offset=${firstTarget.textOffset}"))
        assertTrue(targetLines.last().startsWith("  #10 GET:"))
        assertFalse(report.contains("private-route"))
        assertFalse(report.contains("handler"))
    }

    fun testMountedRoutesCountMountProvenanceNotPrefixedPaths() {
        val child = myFixture.addFileToProject("child.ts", """
            import { Hono } from 'hono'
            export const child = new Hono().get('/health', handler)
        """.trimIndent())
        val app = myFixture.addFileToProject("app.ts", """
            import { Hono } from 'hono'
            import { child } from './child'
            new Hono().basePath('/api').get('/ready', handler)
            new Hono().route('/', child)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val appReport = HonoFileDiagnostics.collect(project, app.virtualFile)
        assertTrue(appReport.contains("File analysis routes: 1"))
        assertTrue(appReport.contains("Mounted routes in file: 0"))
        val childReport = HonoFileDiagnostics.collect(project, child.virtualFile)
        assertTrue(childReport.contains("File analysis routes: 1"))
        assertTrue(childReport.contains("Mounted routes in file: 1"))
    }

    fun testDiagnosticsDeferDuringIndexing() {
        val file = myFixture.addFileToProject("app.ts", "export const value = 42")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(HonoFileDiagnostics.collect(project, file.virtualFile).startsWith("Indexing is in progress"))
        }
    }

    fun testCopyActionIsRegistered() {
        val action = ActionManager.getInstance()
            .getAction("io.github.darleywey.honoendpoints.diagnostics.CopyFileDiagnostics")
            ?: error("Hono diagnostics action was not registered")
        assertTrue(action is CopyHonoFileDiagnosticsAction)
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread())
    }
}
