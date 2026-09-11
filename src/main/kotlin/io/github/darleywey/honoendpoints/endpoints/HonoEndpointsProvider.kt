package io.github.darleywey.honoendpoints.endpoints

import com.intellij.microservices.endpoints.EndpointType
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.EndpointsUrlTargetProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.SearchScopeEndpointsFilter
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.microservices.oas.OasComponents
import com.intellij.microservices.oas.OasEndpointPath
import com.intellij.microservices.oas.OasHttpMethod
import com.intellij.microservices.oas.OasOperation
import com.intellij.microservices.oas.OasParameter
import com.intellij.microservices.oas.OasParameterIn
import com.intellij.microservices.oas.OasParameterStyle
import com.intellij.microservices.oas.OasResponse
import com.intellij.microservices.oas.OasSchema
import com.intellij.microservices.oas.OasSchemaType
import com.intellij.microservices.oas.OpenApiSpecification
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import io.github.darleywey.honoendpoints.framework.HonoFrameworkDetector
import io.github.darleywey.honoendpoints.framework.HonoPath
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup
import io.github.darleywey.honoendpoints.project.HonoProjectModel

/** Thin JetBrains adapter. Route discovery lives in [HonoProjectModel], not here. */
class HonoEndpointsProvider :
    EndpointsUrlTargetProvider<HonoEndpointGroup, HonoEndpoint> {

    override val endpointType: EndpointType = HTTP_SERVER_TYPE
    override val presentation = FrameworkPresentation("Hono", "Hono", null)

    override fun getStatus(project: Project): EndpointsProvider.Status = when {
        project.isDisposed -> EndpointsProvider.Status.UNAVAILABLE
        DumbService.isDumb(project) -> EndpointsProvider.Status.AVAILABLE
        HonoFrameworkDetector.isPresent(project) -> EndpointsProvider.Status.AVAILABLE
        else -> EndpointsProvider.Status.UNAVAILABLE
    }

    override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<HonoEndpointGroup> {
        val groups = HonoProjectModel.endpointGroups(project)
        return if (filter is SearchScopeEndpointsFilter) {
            groups.filter { group -> group.file.virtualFile?.let(filter.contentSearchScope::contains) == true }
        } else {
            groups
        }
    }

    override fun getEndpoints(group: HonoEndpointGroup): Iterable<HonoEndpoint> = group.endpoints

    override fun isValidEndpoint(group: HonoEndpointGroup, endpoint: HonoEndpoint): Boolean =
        group.file.isValid && endpoint.source.isValid && endpoint.target.isValid

    override fun getEndpointPresentation(group: HonoEndpointGroup, endpoint: HonoEndpoint): ItemPresentation =
        HttpMethodPresentation(endpoint.path, endpoint.method, group.file.name, null)

    override fun getModificationTracker(project: Project): ModificationTracker =
        HonoProjectModel.modificationTracker(project)

    override fun getNavigationElement(group: HonoEndpointGroup, endpoint: HonoEndpoint): PsiElement = endpoint.target

    override fun getDocumentationElement(group: HonoEndpointGroup, endpoint: HonoEndpoint): PsiElement =
        HonoEndpointDocumentation.documentationElement(endpoint)

    override fun getUrlTargetInfo(
        group: HonoEndpointGroup,
        endpoint: HonoEndpoint,
    ): Iterable<UrlTargetInfo> = listOf(HonoUrlTargetInfo(endpoint))

    override fun getOpenApiSpecification(
        group: HonoEndpointGroup,
        endpoint: HonoEndpoint,
    ): OpenApiSpecification {
        val method = OasHttpMethod.entries.first { it.methodName.equals(endpoint.method, ignoreCase = true) }
        val docs = HonoOpenApiMetadata.jsDoc(endpoint)
        val parameters = HonoPath.parameterNames(endpoint.path).map { name ->
            OasParameter(
                name,
                OasParameterIn.PATH,
                null,
                true,
                false,
                OasSchema(OasSchemaType.STRING),
                OasParameterStyle.SIMPLE,
            )
        }
        val tags = docs?.tags
            ?.filter { it.first.equals("tag", ignoreCase = true) }
            ?.map { it.second }
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .ifEmpty { listOf(group.file.name) }
        val operation = OasOperation(
            method = method,
            tags = tags,
            summary = docs?.summary ?: "${endpoint.method} ${endpoint.path}",
            description = docs?.description,
            operationId = null,
            isDeprecated = false,
            parameters = parameters,
            requestBody = null,
            responses = listOf(OasResponse("200", "OK", emptyMap(), emptyList())),
        )
        val path = OasEndpointPath(HonoPath.toOpenApiPath(endpoint.path), null, listOf(operation))
        return OpenApiSpecification(listOf(path), OasComponents(emptyMap()), emptyList())
    }
}
