package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.JSElement
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.resolve.JSResolveResult
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HonoConstructorResolverTest : BasePlatformTestCase() {
    fun testClassResultWithImportProvenance() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nnew Hono()")
        val specifier = importSpecifier(reference)
        val result = JSResolveResult(target, specifier, null)
        assertSame(specifier, result.getES6Import())
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, result)))
    }

    fun testClassResultWithoutImportProvenance() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nnew Hono()")
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testAliasedClassResultWithoutImportProvenance() {
        val target = installPackage()
        val reference = constructor("import { Hono as App } from 'hono'\nnew App()")
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testAliasedClassResultWithImportProvenance() {
        val target = installPackage()
        val reference = constructor("import { Hono as App } from 'hono'\nnew App()")
        val alias = importSpecifier(reference).alias!!
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, JSResolveResult(target, alias, null))))
    }

    fun testEnclosingFunctionDoesNotHideImport() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nfunction install() { return new Hono() }")
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testSiblingScopeDoesNotShadowImport() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nfunction install(Hono) {}\nnew Hono()")
        assertTrue(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testLocalClassDoesNotBecomeHonoBecauseOfSameNameImport() {
        installPackage()
        val reference = constructor("import { Hono } from 'hono'\nfunction install() { class Hono {} return new Hono() }")
        val localClass = PsiTreeUtil.findChildOfType(reference.containingFile, TypeScriptClass::class.java)!!
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(localClass))))
    }

    fun testParameterShadowsImportEvenIfServiceReturnsItsType() {
        assertShadowed("function install(Hono) { return new Hono() }")
    }

    fun testVariableShadowsImportEvenIfServiceReturnsItsType() {
        assertShadowed("function install() { const Hono = factory(); return new Hono() }")
    }

    fun testBlockVariableShadowsImport() {
        assertShadowed("{ const Hono = factory(); new Hono() }")
    }

    fun testCatchParameterShadowsImport() {
        assertShadowed("try {} catch (Hono) { new Hono() }")
    }

    fun testLoopVariableShadowsImport() {
        assertShadowed("for (const Hono of constructors) { new Hono() }")
    }

    fun testConflictingImportBindingsAreRejected() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nimport { Hono } from 'other'\nnew Hono()")
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testInterfaceTargetIsNotAConstructor() {
        val declaration = myFixture.addFileToProject("node_modules/hono/index.d.ts", "export interface Hono {}")
        val target = PsiTreeUtil.findChildOfType(declaration, JSClass::class.java)!!
        assertTrue(target.isInterface)
        val reference = constructor("import { Hono } from 'hono'\nnew Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testOtherPackageClassIsRejected() {
        val target = installPackage("another-framework")
        val reference = constructor("import { Hono } from 'another-framework'\nnew Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testOtherExportAliasedToHonoIsRejected() {
        val target = installPackage(className = "Other")
        val reference = constructor("import { Other as Hono } from 'hono'\nnew Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testTypeOnlyDeclarationIsRejectedForClassTargets() {
        val target = installPackage()
        val reference = constructor("import type { Hono } from 'hono'\nnew Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testTypeOnlySpecifierIsRejectedForClassTargets() {
        val target = installPackage()
        val reference = constructor("import { type Hono } from 'hono'\nnew Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testDefaultImportIsNotEnabledByClassResolution() {
        val target = installPackage()
        val reference = constructor("import Hono from 'hono'\nnew Hono()")
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testNamespaceImportIsNotEnabledByClassResolution() {
        val target = installPackage()
        val reference = constructor("import * as framework from 'hono'\nnew framework.Hono()")
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
    }

    fun testQualifiedReferenceCannotBorrowUnqualifiedImport() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nnew other.Hono()")
        assertRejected(reference, target, importSpecifier(reference))
    }

    fun testImportProvenanceMustMatchTheLocalBinding() {
        val target = installPackage()
        val reference = constructor("import { Hono as App, Hono as OtherApp } from 'hono'\nnew App()")
        val otherImport = PsiTreeUtil.findChildrenOfType(reference.containingFile, ES6ImportSpecifier::class.java)
            .single { it.declaredName == "OtherApp" }
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, JSResolveResult(target, otherImport, null))))
    }

    fun testInvalidAndAmbiguousResultsAreRejected() {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\nnew Hono()")
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference)))
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target, false))))
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(
            reference, PsiElementResolveResult(target), PsiElementResolveResult(target),
        )))
    }

    private fun assertShadowed(statement: String) {
        val target = installPackage()
        val reference = constructor("import { Hono } from 'hono'\n$statement")
        assertRejected(reference, target, importSpecifier(reference))
    }

    private fun assertRejected(reference: JSReferenceExpression, target: JSClass, provenance: JSElement) {
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, PsiElementResolveResult(target))))
        assertFalse(HonoConstructorResolver.isHonoConstructor(withResults(reference, JSResolveResult(target, provenance, null))))
    }

    private fun installPackage(name: String = "hono", className: String = "Hono"): TypeScriptClass {
        myFixture.addFileToProject("node_modules/$name/package.json", """
            { "name": "$name", "types": "index.d.ts" }
        """.trimIndent())
        myFixture.addFileToProject("node_modules/$name/index.d.ts", "export { $className } from './hono'")
        val file = myFixture.addFileToProject("node_modules/$name/hono.d.ts", "export declare class $className {}")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val target = PsiTreeUtil.findChildOfType(file, TypeScriptClass::class.java)!!
        assertEquals("TypeScriptClassImpl", target.javaClass.simpleName)
        return target
    }

    private fun constructor(source: String): JSReferenceExpression {
        val file = myFixture.addFileToProject("src/app.mts", source)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return PsiTreeUtil.findChildOfType(file, JSNewExpression::class.java)!!.methodExpression as JSReferenceExpression
    }

    private fun importSpecifier(reference: JSReferenceExpression): ES6ImportSpecifier =
        PsiTreeUtil.findChildOfType(reference.containingFile, ES6ImportSpecifier::class.java)!!

    // Model the language service's result shape using real PSI declarations.
    private fun withResults(reference: JSReferenceExpression, vararg results: ResolveResult): JSReferenceExpression {
        assertTrue(results.all { it.element is JSClass })
        return object : JSReferenceExpression by reference {
            override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> = results
            override fun resolve(): PsiElement? = results.singleOrNull()?.takeIf { it.isValidResult }?.element
        }
    }
}
