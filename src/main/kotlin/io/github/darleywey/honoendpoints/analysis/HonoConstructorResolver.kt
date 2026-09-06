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
import com.intellij.openapi.roots.ProjectRootManager
import io.github.darleywey.honoendpoints.framework.HonoSymbols

/** Proves constructor origin from the resolved binding, not its local spelling. */
internal object HonoConstructorResolver {
    fun isHonoConstructor(constructor: JSExpression?): Boolean {
        val resolved = (constructor as? JSReferenceExpression)?.resolve()
        return when (resolved) {
            is ES6ImportSpecifier -> isHonoImport(resolved)
            is ES6ImportSpecifierAlias -> (resolved.findSpecifierElement() as? ES6ImportSpecifier)
                ?.let(::isHonoImport) == true
            is JSVariable -> isDestructuredHonoRequire(resolved)
            else -> false
        }
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
