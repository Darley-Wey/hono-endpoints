package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration.ImportExportPrefixKind
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier.ImportExportSpecifierKind
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSRecursiveWalkingElementVisitor
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.darleywey.honoendpoints.framework.HonoSymbols
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup
import java.util.Locale

/**
 * Discovers static, local Hono routes through PSI references.
 * Composed route paths are left to the future route graph, not guessed from their local paths.
 */
object HonoProjectScanner {
    fun scanProject(project: Project): List<HonoEndpointGroup> {
        if (project.isDisposed || DumbService.isDumb(project)) return emptyList()
        val groups = mutableListOf<HonoEndpointGroup>()
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex

        for (extension in HonoSymbols.SOURCE_EXTENSIONS) {
            for (file in FilenameIndex.getAllFilesByExt(project, extension, scope)) {
                ProgressManager.checkCanceled()
                if (!fileIndex.isInContent(file) || fileIndex.isInLibrary(file) || "/node_modules/" in file.path) {
                    continue
                }
                val psiFile = psiManager.findFile(file) ?: continue
                val endpoints = scanFile(psiFile)
                if (endpoints.isNotEmpty()) {
                    groups += HonoEndpointGroup(psiFile, endpoints)
                }
            }
        }
        return groups.toList()
    }

    fun scanFile(file: PsiFile): List<HonoEndpoint> {
        if (DumbService.isDumb(file.project) || "hono" !in file.viewProvider.contents) return emptyList()
        val endpoints = mutableListOf<HonoEndpoint>()
        file.accept(object : JSRecursiveWalkingElementVisitor() {
            override fun visitJSCallExpression(call: JSCallExpression) {
                ProgressManager.checkCanceled()
                super.visitJSCallExpression(call)

                val methodReference = call.methodExpression as? JSReferenceExpression ?: return
                val method = methodReference.referenceName ?: return
                if (!HonoSymbols.isHttpMethod(method)) return
                if (!isHonoRouterExpression(methodReference.qualifier, mutableSetOf())) return
                val pathLiteral = call.arguments.firstOrNull() as? JSLiteralExpression ?: return
                val path = pathLiteral.stringValue ?: return
                if (path.startsWith("/")) {
                    endpoints += HonoEndpoint(method.uppercase(Locale.ROOT), path, call)
                }
            }
        })
        return endpoints.sortedBy { it.source.textRange.endOffset }
    }

    private fun isHonoRouterExpression(expression: JSExpression?, visited: MutableSet<PsiElement>): Boolean {
        ProgressManager.checkCanceled()
        if (expression == null || !visited.add(expression)) return false
        return when (expression) {
            is JSNewExpression -> isHonoConstructor(expression.methodExpression)
            is JSReferenceExpression -> {
                val variable = expression.resolve() as? JSVariable ?: return false
                visited.add(variable) && isHonoRouterExpression(variable.initializer, visited)
            }
            is JSCallExpression -> {
                val reference = expression.methodExpression as? JSReferenceExpression ?: return false
                HonoSymbols.isTransparentChainMethod(reference.referenceName) &&
                    isHonoRouterExpression(reference.qualifier, visited)
            }
            else -> false
        }
    }

    private fun isHonoConstructor(constructor: JSExpression?): Boolean {
        val resolved = (constructor as? JSReferenceExpression)?.resolve()
        val specifier = when (resolved) {
            is ES6ImportSpecifier -> resolved
            is ES6ImportSpecifierAlias -> resolved.findSpecifierElement() as? ES6ImportSpecifier
            else -> null
        } ?: return false
        if (specifier.referenceName != "Hono" || specifier.specifierKind != ImportExportSpecifierKind.IMPORT) {
            return false
        }
        val declaration = specifier.declaration ?: return false
        if (declaration.importExportPrefixKind != ImportExportPrefixKind.IMPORT) return false
        val fromClause = declaration.fromClause ?: return false
        return HonoSymbols.isHonoModule(HonoSymbols.unquote(fromClause.referenceText))
    }
}
