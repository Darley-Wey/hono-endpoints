package io.github.darleywey.honoendpoints.diagnostics

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.resolve.JSResolveResult
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.darleywey.honoendpoints.analysis.HonoConstructorResolver
import io.github.darleywey.honoendpoints.analysis.HonoProjectScanner
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.project.HonoProjectModel
import java.util.concurrent.CancellationException

/** Read-only diagnostics: flags, counts, and PSI kinds, never source text or handler bodies. */
internal object HonoFileDiagnostics {
    fun collect(project: Project, file: VirtualFile): String {
        if (project.isDisposed) return "Project is disposed."
        if (DumbService.isDumb(project)) return "Indexing is in progress; retry after indexing finishes."
        val index = ProjectRootManager.getInstance(project).fileIndex
        val scope = ProjectScope.getContentScope(project)
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val version = PluginManagerCore.getPlugin(PluginId.getId("io.github.darleywey.hono-endpoints"))?.version
        return buildString {
            appendLine("Hono Endpoints: $version")
            appendLine("File: ${file.path}")
            appendLine("File type: ${file.fileType.name}")
            appendLine("In project content: ${index.isInContent(file)}")
            appendLine("Excluded: ${index.isExcluded(file)}")
            appendLine("Library classes: ${index.isInLibraryClasses(file)}")
            appendLine("Library sources: ${index.isInLibrarySource(file)}")
            appendLine("Content scope contains file: ${scope.contains(file)}")
            val indexed = file.extension?.let { file in FilenameIndex.getAllFilesByExt(project, it, scope) } ?: false
            appendLine("Found by filename index: $indexed")
            appendLine("PSI: ${psiFile?.javaClass?.simpleName}")
            if (psiFile == null) return@buildString
            appendLine("Language: ${psiFile.language.id}")
            var endpoints = emptyList<HonoEndpoint>()
            val fileCount = countOrError {
                endpoints = HonoProjectScanner.scanFile(psiFile)
                endpoints.size
            }
            appendLine("File analysis routes: $fileCount")
            appendLine("Project model routes in file: ${countOrError {
                HonoProjectModel.endpointGroups(project).filter { it.file.virtualFile == file }.sumOf { it.endpoints.size }
            }}")
            appendLine("Mounted routes in file: ${countOrError {
                HonoProjectModel.endpointGroups(project)
                    .filter { it.file.virtualFile == file }
                    .sumOf { group -> group.endpoints.count { it.mounted } }
            }}")
            appendLine("Constructors (up to 10):")
            PsiTreeUtil.findChildrenOfType(psiFile, JSNewExpression::class.java).take(10).forEach { expression ->
                val reference = expression.methodExpression as? JSReferenceExpression ?: return@forEach
                val results = reference.multiResolve(false)
                val result = results.singleOrNull()?.takeIf { it.isValidResult }
                val resolved = result?.element
                val origin = (result as? JSResolveResult)?.getES6Import()
                val binding = HonoConstructorResolver.resolveLocalBinding(reference)
                appendLine("  ${reference.referenceName}: ${resolved?.javaClass?.simpleName ?: "unresolved"}, " +
                    "Hono=${HonoConstructorResolver.isHonoConstructor(reference)}")
                appendLine("    Resolve results: ${results.size}; result type: ${result?.javaClass?.simpleName ?: "none"}")
                appendLine("    Import provenance: ${origin?.javaClass?.simpleName ?: "none"}")
                appendLine("    Local binding: ${binding?.javaClass?.simpleName ?: "unresolved"}")
            }
            appendLine("Navigation targets (source order, up to 10):")
            endpoints.take(10).forEachIndexed { ordinal, endpoint ->
                appendLine("  #${ordinal + 1} ${endpoint.method}: source=${endpoint.source.javaClass.simpleName} ${endpoint.source.textRange}, " +
                    "target=${endpoint.target.javaClass.simpleName} ${endpoint.target.textRange}, offset=${endpoint.target.textOffset}")
            }
        }
    }

    private fun countOrError(block: () -> Int): String = try {
        block().toString()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        "ERROR (${error.javaClass.simpleName})"
    }
}
