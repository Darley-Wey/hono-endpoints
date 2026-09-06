package io.github.darleywey.honoendpoints.project

import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.ExternalEndpointsFilter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.darleywey.honoendpoints.endpoints.HonoEndpointsProvider
import io.github.darleywey.honoendpoints.framework.HonoFrameworkDetector

class HonoProjectModelTest : BasePlatformTestCase() {
    fun testCacheIsReusedAndInvalidatedByRouteEdits() {
        val file = myFixture.addFileToProject("src/app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val first = HonoProjectModel.endpointGroups(project)
        assertSize(1, first)
        assertSame(first, HonoProjectModel.endpointGroups(project))

        replaceText(file, SOURCE.replace(":id", ":userId"))
        val updated = HonoProjectModel.endpointGroups(project)
        assertNotSame(first, updated)
        assertEquals("/users/:userId", updated.first().endpoints.first().path)
        assertSame(updated, HonoProjectModel.endpointGroups(project))
    }

    fun testAddedAndDeletedFilesInvalidateCache() {
        assertEmpty(HonoProjectModel.endpointGroups(project))
        val file = myFixture.addFileToProject("src/app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSize(1, HonoProjectModel.endpointGroups(project))

        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        assertEmpty(HonoProjectModel.endpointGroups(project))
    }

    fun testDependencySourcesAreNotScanned() {
        myFixture.addFileToProject("node_modules/example/app.ts", SOURCE)
        myFixture.addFileToProject("src/app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val groups = HonoProjectModel.endpointGroups(project)
        assertSize(1, groups)
        assertTrue(groups.first().file.virtualFile.path.endsWith("/src/app.ts"))
    }

    fun testExcludedRootsInvalidateCache() {
        val file = myFixture.addFileToProject("generated/app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSize(1, HonoProjectModel.endpointGroups(project))

        val directory = file.virtualFile.parent
        PsiTestUtil.addExcludedRoot(module, directory)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertEmpty(HonoProjectModel.endpointGroups(project))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, directory)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSize(1, HonoProjectModel.endpointGroups(project))
    }

    fun testManifestProbeFindsNestedWorkspaceAndHandlesUnsavedEdits() {
        val provider = HonoEndpointsProvider()
        assertEquals(EndpointsProvider.Status.UNAVAILABLE, provider.getStatus(project))
        val manifest = myFixture.addFileToProject("packages/server/package.json", MANIFEST)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(EndpointsProvider.Status.AVAILABLE, provider.getStatus(project))
        assertEmpty(HonoProjectModel.endpointGroups(project))

        replaceText(manifest, "{\"dependencies\":{\"express\":\"5\"}}")
        assertEquals(EndpointsProvider.Status.UNAVAILABLE, provider.getStatus(project))
        replaceText(manifest, MANIFEST)
        assertEquals(EndpointsProvider.Status.AVAILABLE, provider.getStatus(project))
        WriteCommandAction.runWriteCommandAction(project) { manifest.delete() }
        assertEquals(EndpointsProvider.Status.UNAVAILABLE, provider.getStatus(project))
    }

    fun testSourceOnlyProjectIsAvailableWithoutManifest() {
        val provider = HonoEndpointsProvider()
        assertEquals(EndpointsProvider.Status.UNAVAILABLE, provider.getStatus(project))
        val file = myFixture.addFileToProject("app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(EndpointsProvider.Status.AVAILABLE, provider.getStatus(project))
        assertSize(1, HonoProjectModel.endpointGroups(project))
        replaceText(file, "export const value = 42")
        assertEquals(EndpointsProvider.Status.UNAVAILABLE, provider.getStatus(project))
    }

    fun testDependencyManifestsDoNotEnableHono() {
        myFixture.addFileToProject("node_modules/example/package.json", MANIFEST)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertFalse(HonoFrameworkDetector.isPresent(project))
    }

    fun testIndexingDefersAnalysisAndRecovers() {
        myFixture.addFileToProject("package.json", MANIFEST)
        myFixture.addFileToProject("app.ts", SOURCE)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSize(1, HonoProjectModel.endpointGroups(project))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(EndpointsProvider.Status.AVAILABLE, HonoEndpointsProvider().getStatus(project))
            assertEmpty(HonoProjectModel.endpointGroups(project))
        }
        assertSize(1, HonoProjectModel.endpointGroups(project))
    }

    fun testProjectRoutesAlsoMarkedAsLibrarySourcesAreIncluded() {
        assertOverlappingLibraryDoesNotHideRoutes(false)
    }

    fun testProjectRoutesAlsoMarkedAsLibraryClassesAreIncluded() {
        assertOverlappingLibraryDoesNotHideRoutes(true)
    }

    fun testContentOnlyProjectRoutesAlsoMarkedAsLibrarySourcesAreIncluded() {
        assertOverlappingLibraryDoesNotHideRoutes(false, true)
    }

    fun testContentOnlyProjectRoutesAlsoMarkedAsLibraryClassesAreIncluded() {
        assertOverlappingLibraryDoesNotHideRoutes(true, true)
    }

    fun testFrameworkProbesKeepProjectFilesAlsoMarkedAsLibraries() {
        val manifest = myFixture.addFileToProject("backend/package.json", MANIFEST)
        val source = myFixture.addFileToProject("backend/app.mts", "export const value = 42")
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots.toList()
        sourceRoots.forEach { PsiTestUtil.removeSourceRoot(module, it) }
        val library = PsiTestUtil.addProjectLibrary(
            module, "ts-external-references", listOf(manifest.virtualFile.parent), emptyList(),
        )
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertTrue(HonoFrameworkDetector.isPresent(project))
            WriteCommandAction.runWriteCommandAction(project) { manifest.delete() }
            replaceText(source, SOURCE)
            assertTrue(HonoFrameworkDetector.isPresent(project))
            assertSize(1, HonoProjectModel.endpointGroups(project))
        } finally {
            PsiTestUtil.removeLibrary(module, library)
            sourceRoots.forEach { PsiTestUtil.addSourceRoot(module, it) }
        }
    }

    private fun assertOverlappingLibraryDoesNotHideRoutes(asClasses: Boolean, withoutSourceRoots: Boolean = false) {
        val backend = myFixture.addFileToProject("backend/src/app.mts", SOURCE)
        val cloud = myFixture.addFileToProject("cloudfunctions/app.js", """
            const { Hono } = require('hono')
            new Hono().get('/cloud', handler)
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSize(2, HonoProjectModel.endpointGroups(project))
        val sourceRoots = if (withoutSourceRoots) {
            ModuleRootManager.getInstance(module).sourceRoots.toList()
        } else {
            emptyList()
        }
        sourceRoots.forEach { PsiTestUtil.removeSourceRoot(module, it) }
        val roots = listOf(backend.virtualFile.parent)
        val library = PsiTestUtil.addProjectLibrary(
            module, "ts-external-references",
            if (asClasses) roots else emptyList(),
            if (asClasses) emptyList() else roots,
        )
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val index = ProjectRootManager.getInstance(project).fileIndex
            assertTrue(index.isInContent(backend.virtualFile))
            assertTrue(index.isInLibrary(backend.virtualFile))
            assertEquals(
                setOf(backend.virtualFile, cloud.virtualFile),
                HonoProjectModel.endpointGroups(project).map { it.file.virtualFile }.toSet(),
            )
            val provider = HonoEndpointsProvider()
            assertEquals(
                setOf(backend.virtualFile, cloud.virtualFile),
                provider.getEndpointGroups(project, ExternalEndpointsFilter)
                    .map { it.file.virtualFile }.toSet(),
            )
            PsiTestUtil.addExcludedRoot(module, backend.virtualFile.parent)
            try {
                IndexingTestUtil.waitUntilIndexesAreReady(project)
                assertEquals(
                    listOf(cloud.virtualFile),
                    HonoProjectModel.endpointGroups(project).map { it.file.virtualFile },
                )
            } finally {
                PsiTestUtil.removeExcludedRoot(module, backend.virtualFile.parent)
            }
        } finally {
            PsiTestUtil.removeLibrary(module, library)
            sourceRoots.forEach { PsiTestUtil.addSourceRoot(module, it) }
        }
    }

    private fun replaceText(file: PsiFile, text: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        assertNotNull(document)
        WriteCommandAction.runWriteCommandAction(project) { document!!.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    companion object {
        private const val MANIFEST = "{\"dependencies\":{\"hono\":\"^4.0.0\"}}"
        private const val SOURCE = """
            import { Hono } from 'hono'
            const app = new Hono()
            app.get('/users/:id', () => {})
        """
    }
}
