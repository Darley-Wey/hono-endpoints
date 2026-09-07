package io.github.darleywey.honoendpoints.analysis

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSRecursiveWalkingElementVisitor
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.github.darleywey.honoendpoints.framework.HonoPath
import io.github.darleywey.honoendpoints.framework.HonoSymbols
import io.github.darleywey.honoendpoints.model.HonoEndpoint
import io.github.darleywey.honoendpoints.model.HonoMount
import io.github.darleywey.honoendpoints.model.HonoRouteDefinition
import io.github.darleywey.honoendpoints.model.HonoRouter
import java.util.Locale

internal class HonoRouteGraphBuilder(private val resolver: HonoRouterResolver) {
    private val definitions = mutableListOf<HonoRouteDefinition>()
    private val mounts = mutableListOf<HonoMount>()

    fun collect(file: PsiFile) {
        file.accept(object : JSRecursiveWalkingElementVisitor() {
            override fun visitJSCallExpression(call: JSCallExpression) {
                ProgressManager.checkCanceled()
                super.visitJSCallExpression(call)
                val reference = call.methodExpression as? JSReferenceExpression ?: return
                val name = reference.referenceName ?: return
                val qualifier = resolver.view(reference.qualifier) ?: return
                when {
                    HonoSymbols.isHttpMethod(name) -> {
                        val pathLiteral = call.arguments.firstOrNull() as? JSLiteralExpression ?: return
                        val path = pathLiteral.stringValue ?: return
                        if (path.startsWith("/")) {
                            definitions += HonoRouteDefinition(
                                name.uppercase(Locale.ROOT),
                                path,
                                qualifier,
                                call,
                                pathLiteral,
                            )
                        }
                    }
                    name == "route" && call.arguments.size >= 2 -> {
                        val prefix = HonoRouterResolver.staticPath(call.arguments[0]) ?: return
                        val child = resolver.router(call.arguments[1]) ?: return
                        mounts += HonoMount(qualifier, child, prefix, call)
                    }
                }
            }
        })
    }

    fun endpoints(): List<HonoEndpoint> {
        val byRouter = definitions.groupBy { it.view.router }
        val mountsByParent = mounts.groupBy { it.parent.router }
        val mountedChildren = mounts.map { it.child }.toSet()
        val allRouters = byRouter.keys + mountsByParent.keys
        val roots = allRouters.filter { it !in mountedChildren }.ifEmpty { allRouters }
        val results = linkedMapOf<EndpointKey, HonoEndpoint>()
        for (root in roots) {
            expand(root, "/", null, null, byRouter, mountsByParent, mutableSetOf(), results)
        }
        return results.values.toList()
    }

    private fun expand(
        router: HonoRouter,
        inherited: String,
        snapshotFile: PsiFile?,
        snapshotOffset: Int?,
        byRouter: Map<HonoRouter, List<HonoRouteDefinition>>,
        mountsByParent: Map<HonoRouter, List<HonoMount>>,
        visiting: MutableSet<ExpandKey>,
        results: MutableMap<EndpointKey, HonoEndpoint>,
    ) {
        ProgressManager.checkCanceled()
        val key = ExpandKey(router, snapshotFile, snapshotOffset)
        if (!visiting.add(key)) return
        try {
            for (definition in byRouter[router].orEmpty()) {
                if (afterSnapshot(definition.source.containingFile, definition.source.textRange.endOffset, snapshotFile, snapshotOffset)) {
                    continue
                }
                val path = HonoPath.merge(inherited, definition.view.basePath, definition.localPath)
                results.putIfAbsent(
                    EndpointKey(definition.method, path, definition.source),
                    HonoEndpoint(definition.method, path, definition.source, definition.target),
                )
            }
            for (mount in mountsByParent[router].orEmpty()) {
                if (afterSnapshot(mount.source.containingFile, mount.source.textRange.endOffset, snapshotFile, snapshotOffset)) {
                    continue
                }
                expand(
                    mount.child,
                    HonoPath.merge(inherited, mount.parent.basePath, mount.prefix),
                    mount.source.containingFile,
                    mount.source.textRange.endOffset,
                    byRouter,
                    mountsByParent,
                    visiting,
                    results,
                )
            }
        } finally {
            visiting.remove(key)
        }
    }

    private fun afterSnapshot(
        file: PsiFile?,
        offset: Int,
        snapshotFile: PsiFile?,
        snapshotOffset: Int?,
    ): Boolean = snapshotOffset != null && file != null && file == snapshotFile && offset > snapshotOffset

    private data class EndpointKey(val method: String, val path: String, val source: JSCallExpression)

    private data class ExpandKey(val router: HonoRouter, val snapshotFile: PsiFile?, val snapshotOffset: Int?)
}
