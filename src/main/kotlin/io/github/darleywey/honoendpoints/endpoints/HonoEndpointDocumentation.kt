package io.github.darleywey.honoendpoints.endpoints

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import io.github.darleywey.honoendpoints.model.HonoEndpoint

/** Keeps the actual method reference as the native documentation input. */
internal object HonoEndpointDocumentation {
    private val ENDPOINT_METHOD = Key.create<Boolean>("hono.documentation.endpoint.method")

    fun element(endpoint: HonoEndpoint): PsiElement =
        (endpoint.source as? JSCallExpression)?.methodExpression as? JSReferenceExpression
            ?: endpoint.source

    /** Mark only references supplied to documentation, without resolving or retaining PSI. */
    fun documentationElement(endpoint: HonoEndpoint): PsiElement = element(endpoint).also {
        if (it is JSReferenceExpression) it.putUserData(ENDPOINT_METHOD, true)
    }

    fun isDocumentationMethod(element: PsiElement): Boolean =
        element.getUserData(ENDPOINT_METHOD) == true
}
