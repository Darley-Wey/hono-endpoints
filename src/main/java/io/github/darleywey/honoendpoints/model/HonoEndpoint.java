package io.github.darleywey.honoendpoints.model;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public record HonoEndpoint(
        @NotNull String method,
        @NotNull String path,
        @NotNull PsiElement source
) {}
