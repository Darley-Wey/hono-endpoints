package io.github.darleywey.honoendpoints.endpoints;

import com.intellij.microservices.endpoints.EndpointType;
import com.intellij.microservices.endpoints.EndpointsFilter;
import com.intellij.microservices.endpoints.EndpointsProvider;
import com.intellij.microservices.endpoints.FrameworkPresentation;
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiModificationTracker;
import io.github.darleywey.honoendpoints.analysis.HonoProjectScanner;
import io.github.darleywey.honoendpoints.model.HonoEndpoint;
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup;
import org.jetbrains.annotations.NotNull;

import static com.intellij.microservices.endpoints.EndpointTypes.HTTP_SERVER_TYPE;

/**
 * Bridges the Hono route model into the built-in JetBrains Endpoints tool window.
 */
public final class HonoEndpointsProvider implements EndpointsProvider<HonoEndpointGroup, HonoEndpoint> {
    private static final FrameworkPresentation PRESENTATION =
            new FrameworkPresentation("Hono", "Hono", null);

    @Override
    public @NotNull EndpointType getEndpointType() {
        return HTTP_SERVER_TYPE;
    }

    @Override
    public @NotNull FrameworkPresentation getPresentation() {
        return PRESENTATION;
    }

    @Override
    public @NotNull Status getStatus(@NotNull Project project) {
        return HonoProjectScanner.scanProject(project).isEmpty()
                ? Status.AVAILABLE
                : Status.HAS_ENDPOINTS;
    }

    @Override
    public @NotNull Iterable<HonoEndpointGroup> getEndpointGroups(
            @NotNull Project project,
            @NotNull EndpointsFilter filter
    ) {
        return HonoProjectScanner.scanProject(project);
    }

    @Override
    public @NotNull Iterable<HonoEndpoint> getEndpoints(@NotNull HonoEndpointGroup group) {
        return group.endpoints();
    }

    @Override
    public boolean isValidEndpoint(@NotNull HonoEndpointGroup group, @NotNull HonoEndpoint endpoint) {
        return group.file().isValid() && endpoint.source().isValid();
    }

    @Override
    public @NotNull ItemPresentation getEndpointPresentation(
            @NotNull HonoEndpointGroup group,
            @NotNull HonoEndpoint endpoint
    ) {
        return new HttpMethodPresentation(
                endpoint.path(),
                endpoint.method(),
                group.file().getName(),
                null
        );
    }

    @Override
    public @NotNull ModificationTracker getModificationTracker(@NotNull Project project) {
        return PsiModificationTracker.getInstance(project);
    }

    @Override
    public PsiElement getNavigationElement(@NotNull HonoEndpointGroup group, @NotNull HonoEndpoint endpoint) {
        return endpoint.source();
    }

    @Override
    public PsiElement getDocumentationElement(@NotNull HonoEndpointGroup group, @NotNull HonoEndpoint endpoint) {
        return endpoint.source();
    }
}
