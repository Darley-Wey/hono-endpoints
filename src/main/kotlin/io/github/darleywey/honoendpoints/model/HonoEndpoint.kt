package io.github.darleywey.honoendpoints.model

import com.intellij.psi.PsiElement

data class HonoEndpoint(
    val method: String,
    val path: String,
    val source: PsiElement,
)
