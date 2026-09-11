package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration.ImportExportPrefixKind
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier.ImportExportSpecifierKind
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.frameworks.commonjs.CommonJSUtil
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSDestructuringObject
import com.intellij.lang.javascript.psi.JSDestructuringProperty
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.impl.JSReferenceExpressionImpl
import com.intellij.lang.javascript.psi.resolve.JSResolveResult
import com.intellij.lang.javascript.psi.resolve.ResolveProcessor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import io.github.darleywey.honoendpoints.framework.HonoSymbols

/** Proves constructor origin from the resolved binding, not its local spelling. */
internal object HonoConstructorResolver {
    fun isHonoConstructor(constructor: JSExpression?): Boolean {
        ProgressManager.checkCanceled()
        val reference = constructor as? JSReferenceExpression ?: return false
        if (reference.qualifier != null) return false
        val result = reference.multiResolve(false).singleOrNull()?.takeIf { it.isValidResult } ?: return false
        return when (val resolved = result.element) {
            is ES6ImportSpecifier -> isHonoImport(resolved)
            is ES6ImportSpecifierAlias -> importSpecifier(resolved)?.let(::isHonoImport) == true
            is JSVariable -> isDestructuredHonoRequire(resolved)
            is JSClass -> !resolved.isInterface && isHonoClassTarget(reference, resolved, result)
            else -> false
        }
    }

    private fun importSpecifier(element: PsiElement?): ES6ImportSpecifier? = when (element) {
        is ES6ImportSpecifier -> element
        is ES6ImportSpecifierAlias -> element.findSpecifierElement() as? ES6ImportSpecifier
        else -> null
    }

    private fun isHonoImport(specifier: ES6ImportSpecifier): Boolean {
        if (specifier.referenceName != "Hono" || specifier.specifierKind != ImportExportSpecifierKind.IMPORT) {
            return false
        }
        val declaration = specifier.declaration ?: return false
        if (declaration.importExportPrefixKind != ImportExportPrefixKind.IMPORT) return false
        val fromClause = declaration.fromClause ?: return false
        return HonoSymbols.isHonoModule(HonoSymbols.unquote(fromClause.referenceText))
    }

    fun resolveLocalBinding(reference: JSReferenceExpression): PsiElement? {
        if (reference.qualifier != null) return null
        val name = reference.referenceName ?: return null
        // Keep imports as bindings rather than following them to their exported types.
        val bindings = linkedSetOf<PsiElement>()
        val processor = object : ResolveProcessor(name, reference.element) {
            override fun execute(element: PsiElement, state: ResolveState): Boolean {
                ProgressManager.checkCanceled()
                val binding = importSpecifier(element) ?: element
                val bindingName = (binding as? ES6ImportSpecifier)?.declaredName ?: (binding as? PsiNamedElement)?.name
                if (bindingName != name) return true
                bindings += binding
                return false
            }
        }
        processor.setForceImportsForPlace(true)
        JSReferenceExpressionImpl.doProcessLocalDeclarations(reference.element, null, processor, false, false, false)
        return bindings.singleOrNull()
    }

    private fun isHonoClassTarget(reference: JSReferenceExpression, target: JSClass, result: ResolveResult): Boolean {
        val file = reference.containingFile ?: return false
        if (target.containingFile == file) return false
        // The type service may point past the import. Recover its lexical binding,
        // so local parameters, variables, and classes still shadow the import.
        val specifier = resolveLocalBinding(reference) as? ES6ImportSpecifier ?: return false
        if (!isHonoImport(specifier)) return false
        val origin = (result as? JSResolveResult)?.getES6Import() ?: return true
        if (origin.containingFile != file) return true
        val provenance = importSpecifier(origin) ?: return false
        return reference.manager.areElementsEquivalent(specifier, provenance)
    }

    private fun isDestructuredHonoRequire(variable: JSVariable): Boolean {
        val property = variable.parent as? JSDestructuringProperty ?: return false
        if (property.name != "Hono" || property.computedPropertyName != null ||
            property.destructuringElement != variable || variable.initializer != null) {
            return false
        }
        val target = property.parent as? JSDestructuringObject ?: return false
        val declaration = target.parent as? JSDestructuringElement ?: return false
        val call = declaration.initializer as? JSCallExpression ?: return false
        if (!HonoSymbols.isHonoModule(CommonJSUtil.getModulePathIfRequireCall(call))) return false

        val requireReference = call.methodExpression as? JSReferenceExpression ?: return false
        val requireDeclaration = requireReference.resolve() ?: return true
        val declarationFile = requireDeclaration.containingFile ?: return false
        // A local function, parameter, or imported replacement is not Node's require.
        if (declarationFile == call.containingFile) return false
        val virtualFile = declarationFile.virtualFile ?: return true
        val fileIndex = ProjectRootManager.getInstance(call.project).fileIndex
        return !fileIndex.isInContent(virtualFile) || "/node_modules/" in virtualFile.path
    }
}
