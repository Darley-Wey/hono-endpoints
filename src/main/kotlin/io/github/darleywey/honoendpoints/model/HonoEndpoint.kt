package io.github.darleywey.honoendpoints.model

import com.intellij.psi.PsiElement

/** [source] retains the full call; [target] anchors navigation to its path literal. */
data class HonoEndpoint(
    val method: String,
    val path: String,
    val source: PsiElement,
    val target: PsiElement,
)
