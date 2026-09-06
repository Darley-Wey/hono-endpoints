package io.github.darleywey.honoendpoints.endpoints

import com.intellij.microservices.endpoints.EndpointType
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import io.github.darleywey.honoendpoints.framework.HonoFrameworkDetector
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup
import io.github.darleywey.honoendpoints.project.HonoProjectModel

/** Thin JetBrains adapter. Route discovery lives in [HonoProjectModel], not here. */
class HonoEndpointsProvider : EndpointsProvider<HonoEndpointGroup, HonoEndpoint> {
    override val endpointType: EndpointType = HTTP_SERVER_TYPE
    override val presentation = FrameworkPresentation("Hono", "Hono", null)

    override fun getStatus(project: Project): EndpointsProvider.Status = when {
        project.isDisposed -> EndpointsProvider.Status.UNAVAILABLE
        DumbService.isDumb(project) -> EndpointsProvider.Status.AVAILABLE
        HonoFrameworkDetector.isPresent(project) -> EndpointsProvider.Status.AVAILABLE
        else -> EndpointsProvider.Status.UNAVAILABLE
    }

    override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<HonoEndpointGroup> =
        HonoProjectModel.endpointGroups(project)

    override fun getEndpoints(group: HonoEndpointGroup): Iterable<HonoEndpoint> = group.endpoints

    override fun isValidEndpoint(group: HonoEndpointGroup, endpoint: HonoEndpoint): Boolean =
        group.file.isValid && endpoint.source.isValid

    override fun getEndpointPresentation(group: HonoEndpointGroup, endpoint: HonoEndpoint): ItemPresentation =
        HttpMethodPresentation(endpoint.path, endpoint.method, group.file.name, null)

    override fun getModificationTracker(project: Project): ModificationTracker =
        PsiModificationTracker.getInstance(project)

    override fun getNavigationElement(group: HonoEndpointGroup, endpoint: HonoEndpoint): PsiElement = endpoint.source

    override fun getDocumentationElement(group: HonoEndpointGroup, endpoint: HonoEndpoint): PsiElement = endpoint.source
}
