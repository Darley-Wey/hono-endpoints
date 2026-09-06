package io.github.darleywey.honoendpoints.model;

import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record HonoEndpointGroup(
        @NotNull PsiFile file,
        @NotNull List<HonoEndpoint> endpoints
) {}
