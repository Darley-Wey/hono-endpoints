package io.github.darleywey.honoendpoints.endpoints

import com.intellij.microservices.url.Authority
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.psi.PsiElement
import io.github.darleywey.honoendpoints.model.HonoEndpoint

class HonoUrlTargetInfo(
    private val endpoint: HonoEndpoint,
    private val host: String = "localhost",
    private val port: Int = 3000,
) : UrlTargetInfo {
    override val schemes: List<String> = listOf("http", "https")
    override val authorities: List<Authority> = listOf(Authority.Exact("$host:$port"))
    override val path: UrlPath = UrlPath.fromExactString(endpoint.path)
    override val methods: Set<String> = setOf(endpoint.method.lowercase())

    override fun resolveToPsiElement(): PsiElement = endpoint.target
}
