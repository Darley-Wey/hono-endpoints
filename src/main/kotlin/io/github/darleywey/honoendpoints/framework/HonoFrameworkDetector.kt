package io.github.darleywey.honoendpoints.framework

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Cached manifest and word-index probes; never traverses source PSI to find endpoints. */
object HonoFrameworkDetector {
    private val PRESENT_KEY = Key.create<CachedValue<Boolean>>("hono.framework.present")
    private val IMPORTS_KEY = Key.create<CachedValue<Boolean>>("hono.framework.imports")
    private val DEPENDENCY_SECTIONS = listOf(
        "dependencies", "devDependencies", "peerDependencies", "optionalDependencies",
    )

    fun isPresent(project: Project): Boolean {
        if (project.isDisposed || DumbService.isDumb(project)) return false
        val cacheManager = CachedValuesManager.getManager(project)
        return cacheManager.getCachedValue(project, PRESENT_KEY, { detect(project) }, false) ||
            cacheManager.getCachedValue(project, IMPORTS_KEY, {
                CachedValueProvider.Result.create(
                    mayImportHono(project),
                    PsiModificationTracker.getInstance(project),
                    ProjectRootModificationTracker.getInstance(project),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DumbService.getInstance(project).modificationTracker,
                )
            }, false)
    }

    private fun mayImportHono(project: Project): Boolean {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        // This index hit is an availability hint only; the analyzer still proves constructor origin.
        return !PsiSearchHelper.getInstance(project).processCandidateFilesForText(
            ProjectScope.getContentScope(project), UsageSearchContext.IN_STRINGS, true, "hono",
        ) { file ->
            ProgressManager.checkCanceled()
            val candidate = file.extension in HonoSymbols.SOURCE_EXTENSIONS &&
                fileIndex.isInContent(file) && !fileIndex.isExcluded(file) && "/node_modules/" !in file.path
            !candidate
        }
    }

    private fun detect(project: Project): CachedValueProvider.Result<Boolean> {
        val dependencies = mutableListOf<Any>(
            ProjectRootModificationTracker.getInstance(project),
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            DumbService.getInstance(project).modificationTracker,
        )
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val psiManager = PsiManager.getInstance(project)
        var present = false

        for (file in FilenameIndex.getVirtualFilesByName("package.json", ProjectScope.getContentScope(project))) {
            ProgressManager.checkCanceled()
            if (!fileIndex.isInContent(file) || fileIndex.isExcluded(file) || "/node_modules/" in file.path) {
                continue
            }
            val manifest = psiManager.findFile(file) ?: continue
            dependencies += manifest
            present = present || dependsOnHono(manifest.text)
        }
        return CachedValueProvider.Result.create(present, *dependencies.toTypedArray())
    }

    internal fun dependsOnHono(packageJsonText: String): Boolean {
        try {
            val manifest = JsonParser.parseString(packageJsonText) as? JsonObject ?: return false
            return DEPENDENCY_SECTIONS.any { section ->
                (manifest.get(section) as? JsonObject)?.has("hono") == true
            }
        } catch (_: JsonParseException) {
            // A manifest can be temporarily incomplete while it is being edited.
            return false
        }
    }
}
