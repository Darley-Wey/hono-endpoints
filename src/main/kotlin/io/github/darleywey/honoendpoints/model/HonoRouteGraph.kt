package io.github.darleywey.honoendpoints.model

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression

/** One Hono instance. Identity is the constructor's file and offset, not a PSI wrapper. */
internal class HonoRouter(val constructor: JSNewExpression) {
    private val fileUrl = constructor.containingFile?.virtualFile?.url
    private val offset = constructor.textRange.startOffset

    override fun equals(other: Any?): Boolean =
        other is HonoRouter && fileUrl == other.fileUrl && offset == other.offset

    override fun hashCode(): Int = 31 * (fileUrl?.hashCode() ?: 0) + offset

    override fun toString(): String = "HonoRouter($fileUrl:$offset)"
}

/** A view onto a router: the same route table plus a basePath. */
internal data class HonoRouterView(
    val router: HonoRouter,
    val basePath: String,
)

internal data class HonoRouteDefinition(
    val method: String,
    val localPath: String,
    val view: HonoRouterView,
    val source: JSCallExpression,
    val target: JSLiteralExpression,
)

internal data class HonoMount(
    val parent: HonoRouterView,
    val child: HonoRouter,
    val prefix: String,
    val source: JSCallExpression,
)
