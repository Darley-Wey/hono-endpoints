package io.github.darleywey.honoendpoints.project

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.darleywey.honoendpoints.analysis.HonoProjectScanner
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup

/** Project-level cache; the Endpoints adapter never scans PSI on its own. */
object HonoProjectModel {
    private val ENDPOINTS_KEY = Key.create<CachedValue<List<HonoEndpointGroup>>>("hono.endpoints.groups")

    fun invalidationTrackers(project: Project): Array<ModificationTracker> = arrayOf(
        PsiModificationTracker.getInstance(project),
        ProjectRootModificationTracker.getInstance(project),
        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        DumbService.getInstance(project).modificationTracker,
    )

    fun modificationTracker(project: Project): ModificationTracker = ModificationTracker {
        invalidationTrackers(project).sumOf { it.modificationCount }
    }

    fun endpointGroups(project: Project): List<HonoEndpointGroup> {
        if (project.isDisposed || DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project, ENDPOINTS_KEY, {
            CachedValueProvider.Result.create(
                HonoProjectScanner.scanProject(project),
                *invalidationTrackers(project),
            )
        }, false)
    }
}
