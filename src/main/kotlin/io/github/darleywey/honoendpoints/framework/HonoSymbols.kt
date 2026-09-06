package io.github.darleywey.honoendpoints.framework

/**
 * Framework-level Hono facts that do not depend on PSI.
 *
 * Composition methods are deliberately not followed by the bootstrap scanner.
 * In particular, basePath changes the prefix of subsequent routes. Resolving composed
 * paths requires the future route graph; external framework mounts remain out of scope.
 */
object HonoSymbols {
    val SOURCE_EXTENSIONS = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts")
    val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "options", "head")

    /** Supported methods that leave the prefix of subsequent routes unchanged. */
    val TRANSPARENT_CHAIN_METHODS = HTTP_METHODS + setOf("all", "on", "use", "notFound", "onError")

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
