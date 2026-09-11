package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6FromClause
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.frameworks.commonjs.CommonJSUtil
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDefinitionExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.darleywey.honoendpoints.framework.HonoPath
import io.github.darleywey.honoendpoints.framework.HonoSymbols
import io.github.darleywey.honoendpoints.model.HonoRouter
import io.github.darleywey.honoendpoints.model.HonoRouterView
import java.util.IdentityHashMap

/** Follows expressions to a Hono router view without guessing unresolved prefixes. */
internal class HonoRouterResolver {
    private val views = IdentityHashMap<JSExpression, HonoRouterView?>()
    private val visiting = IdentityHashMap<PsiElement, Boolean>()

    fun view(expression: JSExpression?): HonoRouterView? {
        ProgressManager.checkCanceled()
        if (expression == null) return null
        if (views.containsKey(expression)) return views[expression]
        if (visiting.put(expression, true) != null) return null
        val resolved = try {
            resolveView(expression)
        } finally {
            visiting.remove(expression)
        }
        views[expression] = resolved
        return resolved
    }

    fun router(expression: JSExpression?): HonoRouter? = view(expression)?.router

    private fun resolveView(expression: JSExpression): HonoRouterView? = when (expression) {
        is JSNewExpression ->
            if (HonoConstructorResolver.isHonoConstructor(expression.methodExpression)) {
                HonoRouterView(HonoRouter(expression), "/")
            } else {
                null
            }
        is JSReferenceExpression -> followReference(expression)
        is JSCallExpression -> followCall(expression)
        else -> null
    }

    private fun followReference(reference: JSReferenceExpression): HonoRouterView? {
        if (reference.qualifier != null) return null
        val binding = HonoConstructorResolver.resolveLocalBinding(reference) ?: reference.resolve()
        return followBinding(binding)
    }

    private fun followBinding(binding: PsiElement?): HonoRouterView? {
        ProgressManager.checkCanceled()
        if (binding == null || visiting.put(binding, true) != null) return null
        return try {
            when (binding) {
                is JSVariable -> view(binding.initializer)
                is ES6ImportSpecifier -> followImportSpecifier(binding)
                is ES6ImportedBinding -> followDefaultImport(binding)
                is ES6ExportSpecifier -> followExportSpecifier(binding)
                is JSDefinitionExpression -> view(assignmentValue(binding))
                is ES6ExportDefaultAssignment -> view(binding.expression) ?: followBinding(binding.namedElement)
                else -> view(binding as? JSExpression)
            }
        } finally {
            visiting.remove(binding)
        }
    }

    private fun followImportSpecifier(specifier: ES6ImportSpecifier): HonoRouterView? {
        val file = resolveFromClause(specifier.declaration?.fromClause, specifier.containingFile)
        if (file != null) {
            findExportedView(file, specifier.referenceName)?.let { return it }
        }
        return followResolved(specifier.resolveOverAliases(hashSetOf()).mapNotNull { it.element })
    }

    private fun followDefaultImport(binding: ES6ImportedBinding): HonoRouterView? {
        if (binding.isNamespaceImport) return null
        val file = resolveFromClause(binding.declaration?.fromClause, binding.containingFile)
        if (file != null) {
            findExportedView(file, null)?.let { return it }
        }
        return followResolved(binding.findReferencedElements())
    }

    private fun followExportSpecifier(specifier: ES6ExportSpecifier): HonoRouterView? {
        val declaration = specifier.declaration as? ES6ExportDeclaration
        if (declaration != null && declaration.isReExport) {
            val file = resolveFromClause(declaration.fromClause, specifier.containingFile)
            if (file != null) {
                val exportName = if (specifier.isExportDefault) null else specifier.referenceName
                findExportedView(file, exportName)?.let { return it }
            }
        }
        return followResolved(specifier.resolveOverAliases(hashSetOf()).mapNotNull { it.element })
    }

    private fun followResolved(candidates: Collection<PsiElement>): HonoRouterView? {
        val unique = candidates.mapNotNull { followBinding(it) }.distinct()
        return unique.singleOrNull()
    }

    private fun assignmentValue(definition: JSDefinitionExpression): JSExpression? {
        val assignment = definition.parent as? JSAssignmentExpression ?: return null
        return assignment.rOperand
    }

    private fun followCall(call: JSCallExpression): HonoRouterView? {
        val module = HonoSymbols.unquote(CommonJSUtil.getModulePathIfRequireCall(call))
        if (module != null) {
            val file = resolveModuleFile(call.containingFile, module) ?: return null
            return findExportedView(file, null)
        }
        val reference = call.methodExpression as? JSReferenceExpression ?: return null
        val name = reference.referenceName ?: return null
        val qualifier = view(reference.qualifier) ?: return null
        return when {
            name == "basePath" -> {
                val prefix = staticPath(call.arguments.firstOrNull()) ?: return null
                HonoRouterView(qualifier.router, HonoPath.merge(qualifier.basePath, prefix))
            }
            name == "route" -> if (call.arguments.size >= 2) qualifier else null
            HonoSymbols.isTransparentChainMethod(name) -> qualifier
            else -> null
        }
    }

    private fun resolveFromClause(fromClause: ES6FromClause?, from: PsiFile?): PsiFile? {
        if (fromClause == null || from == null) return null
        val specifier = HonoSymbols.unquote(fromClause.referenceText)
        resolveModuleFile(from, specifier)?.let { return it }
        val referenced = fromClause.resolveReferencedElements().mapNotNull { element ->
            (element as? PsiFile ?: element.containingFile)?.let(::canonicalFile)
        }.distinct()
        return referenced.singleOrNull()
    }

    private fun resolveModuleFile(from: PsiFile, specifier: String?): PsiFile? {
        if (specifier == null || !(specifier.startsWith("./") || specifier.startsWith("../"))) return null
        var directory = from.originalFile.containingDirectory ?: return null
        val parts = specifier.split('/').filter { it.isNotEmpty() && it != "." }
        for ((index, part) in parts.withIndex()) {
            when {
                part == ".." -> directory = directory.parentDirectory ?: return null
                index == parts.lastIndex -> {
                    val matches = moduleFileNames(part).mapNotNull(directory::findFile).distinct()
                    return matches.singleOrNull()
                }
                else -> directory = directory.findSubdirectory(part) ?: return null
            }
        }
        return null
    }

    private fun moduleFileNames(last: String): List<String> {
        val withoutJs = last.removeSuffix(".js").removeSuffix(".mjs").removeSuffix(".cjs")
        return listOf(
            last,
            "$withoutJs.ts",
            "$withoutJs.tsx",
            "$withoutJs.mts",
            "$withoutJs.cts",
            "$withoutJs.js",
            "$withoutJs.mjs",
            "$withoutJs.cjs",
        ).distinct()
    }

    private fun canonicalFile(file: PsiFile): PsiFile =
        file.virtualFile?.let { PsiManager.getInstance(file.project).findFile(it) } ?: file

    private fun findExportedView(file: PsiFile, exportName: String?): HonoRouterView? {
        ProgressManager.checkCanceled()
        val psiFile = canonicalFile(file)
        if (exportName == null) {
            PsiTreeUtil.findChildrenOfType(psiFile, ES6ExportDefaultAssignment::class.java).forEach { assignment ->
                view(assignment.expression)?.let { return it }
                followBinding(assignment.namedElement)?.let { return it }
            }
            return findCommonJsDefault(psiFile)
        }
        PsiTreeUtil.findChildrenOfType(psiFile, JSVariable::class.java).forEach { variable ->
            if (variable.name == exportName && isModuleExport(variable)) {
                view(variable.initializer)?.let { return it }
            }
        }
        PsiTreeUtil.findChildrenOfType(psiFile, ES6ExportSpecifier::class.java).forEach { specifier ->
            if (specifier.declaredName == exportName) {
                followExportSpecifier(specifier)?.let { return it }
            }
        }
        return null
    }

    private fun isModuleExport(element: PsiElement): Boolean {
        generateSequence(element) { it.parent }.takeWhile { it !is PsiFile }.forEach { current ->
            if (current is ES6ExportDeclaration || current is ES6ExportDefaultAssignment) return true
            val attributes = (current as? JSAttributeListOwner)?.attributeList
            if (attributes?.hasModifier(JSAttributeList.ModifierType.EXPORT) == true) return true
        }
        return false
    }

    private fun findCommonJsDefault(file: PsiFile): HonoRouterView? {
        PsiTreeUtil.findChildrenOfType(file, JSAssignmentExpression::class.java).forEach { assignment ->
            val left = assignment.lOperand
            val target = when (left) {
                is JSReferenceExpression -> left
                is JSDefinitionExpression -> left.expression as? JSReferenceExpression
                else -> null
            } ?: return@forEach
            if (target.text == "module.exports" || target.text == "exports") {
                view(assignment.rOperand)?.let { return it }
            }
        }
        return null
    }

    companion object {
        fun staticPath(expression: JSExpression?): String? {
            val literal = expression as? JSLiteralExpression ?: return null
            return literal.stringValue
        }
    }
}
