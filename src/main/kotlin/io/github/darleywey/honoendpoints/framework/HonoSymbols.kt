package io.github.darleywey.honoendpoints.framework

/**
 * Framework-level Hono facts that do not depend on PSI.
 *
 * `basePath` returns a new view that shares the original route table. `route(prefix, child)`
 * copies the child's then-registered routes onto the parent and returns the parent view.
 */
object HonoSymbols {
    val SOURCE_EXTENSIONS = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts")
    val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "options", "head")

    /** Supported methods that leave the prefix of subsequent routes unchanged. */
    val TRANSPARENT_CHAIN_METHODS = HTTP_METHODS + setOf("all", "on", "route", "use", "notFound", "onError")

    fun isHonoModule(specifier: String?): Boolean = specifier != null && (
        specifier == "hono" || specifier.startsWith("hono/") ||
            specifier == "jsr:@hono/hono" || specifier.startsWith("jsr:@hono/hono/")
        )

    fun isHttpMethod(method: String?): Boolean = method in HTTP_METHODS

    fun isTransparentChainMethod(method: String?): Boolean = method in TRANSPARENT_CHAIN_METHODS

    fun unquote(value: String?): String? {
        if (value == null || value.length < 2) return value
        val first = value.first()
        return if ((first == '\'' || first == '"' || first == '`') && first == value.last()) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
