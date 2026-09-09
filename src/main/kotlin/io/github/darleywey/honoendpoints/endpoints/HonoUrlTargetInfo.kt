package io.github.darleywey.honoendpoints.endpoints

import com.intellij.microservices.url.Authority
import com.intellij.microservices.url.HTTP_SCHEMES
import com.intellij.microservices.url.LOCALHOST
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.psi.PsiElement
import io.github.darleywey.honoendpoints.model.HonoEndpoint

class HonoUrlTargetInfo(private val endpoint: HonoEndpoint) : UrlTargetInfo {
    override val schemes: List<String> = HTTP_SCHEMES
    override val authorities: List<Authority> = listOf(Authority.Exact("$LOCALHOST:3000"))
    override val path: UrlPath = UrlPath.fromExactString(endpoint.path)
    override val methods: Set<String> = setOf(endpoint.method)
    override val source: String = endpoint.source.containingFile?.name.orEmpty()
    override val documentationPsiElement: PsiElement?
        get() = if (endpoint.source.isValid && endpoint.target.isValid) {
            HonoEndpointDocumentation.documentationElement(endpoint).takeIf { it.isValid }
        } else {
            null
        }

    override fun resolveToPsiElement(): PsiElement? =
        endpoint.target.takeIf { it.isValid }
}
