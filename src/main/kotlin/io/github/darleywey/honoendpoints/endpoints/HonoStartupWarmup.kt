package io.github.darleywey.honoendpoints.endpoints

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiElement
import io.github.darleywey.honoendpoints.project.HonoProjectModel
import org.jetbrains.concurrency.CancellablePromise

/** Prepares native documentation without holding a blocking read lock during project startup. */
class HonoStartupWarmup : ProjectActivity {
    override suspend fun execute(project: Project) {
        schedule(project)
    }

    internal fun schedule(project: Project): CancellablePromise<List<PsiElement>> =
        HonoWarmupReadAction.submit(project, read = {
            // Route discovery can resolve TypeScript references on cold caches. The
            // cancellable read action must release its lock when the EDT needs a write.
            HonoProjectModel.endpointGroups(project)
                .flatMap { group -> group.endpoints }
                .map { HonoEndpointDocumentation.element(it) }
        }, consume = { elements ->
            HonoTsServiceWarmup.warmRouteElements(project, elements)
        })
}
