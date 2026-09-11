package io.github.darleywey.honoendpoints.model

import com.intellij.psi.PsiFile

data class HonoEndpointGroup(
    val file: PsiFile,
    val endpoints: List<HonoEndpoint>,
)
