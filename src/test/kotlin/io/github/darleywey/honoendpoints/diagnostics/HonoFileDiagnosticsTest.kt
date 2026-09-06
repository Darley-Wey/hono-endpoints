package io.github.darleywey.honoendpoints.diagnostics

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
