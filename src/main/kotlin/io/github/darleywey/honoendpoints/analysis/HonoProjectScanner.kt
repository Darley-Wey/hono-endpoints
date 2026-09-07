package io.github.darleywey.honoendpoints.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import io.github.darleywey.honoendpoints.framework.HonoSymbols
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup

/**
 * Discovers Hono routes through PSI references and composes mounted child paths.
 */
object HonoProjectScanner {
    fun scanProject(project: Project): List<HonoEndpointGroup> {
        if (project.isDisposed || DumbService.isDumb(project)) return emptyList()
        val resolver = HonoRouterResolver()
        val builder = HonoRouteGraphBuilder(resolver)
        for (file in candidateFiles(project)) {
            ProgressManager.checkCanceled()
            builder.collect(file)
        }
        return builder.endpoints()
            .groupBy { it.source.containingFile }
            .map { (file, endpoints) ->
                HonoEndpointGroup(file, endpoints.sortedBy { it.source.textRange.endOffset })
            }
            .sortedBy { it.file.virtualFile?.path.orEmpty() }
    }

    fun scanFile(file: PsiFile): List<HonoEndpoint> {
        if (DumbService.isDumb(file.project)) return emptyList()
        val resolver = HonoRouterResolver()
        val builder = HonoRouteGraphBuilder(resolver)
        builder.collect(file)
        return builder.endpoints()
            .filter { it.source.containingFile == file }
            .sortedBy { it.source.textRange.endOffset }
    }

    private fun candidateFiles(project: Project): List<PsiFile> {
        val psiManager = PsiManager.getInstance(project)
        val scope = ProjectScope.getContentScope(project)
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val files = linkedSetOf<PsiFile>()
        for (extension in HonoSymbols.SOURCE_EXTENSIONS) {
            for (file in FilenameIndex.getAllFilesByExt(project, extension, scope)) {
                ProgressManager.checkCanceled()
                if (!fileIndex.isInContent(file) || fileIndex.isExcluded(file) || "/node_modules/" in file.path) {
                    continue
                }
                files += psiManager.findFile(file) ?: continue
            }
        }
        return files.toList()
    }
}
