package io.github.darleywey.honoendpoints.framework

/**
 * Path joining that matches Hono's `mergePath` in `hono/dist/utils/url.js`.
 *
 * Root `/` does not add a second slash. Explicit trailing slashes and parameter
 * segments are preserved rather than being normalized as generic URLs. A missing
 * base is treated as `/`, matching a Hono instance's default `_basePath`.
 */
object HonoPath {
    fun merge(base: String?, sub: String?, vararg rest: String?): String {
        val mergedSub = if (rest.isEmpty()) {
            sub.orEmpty()
        } else {
            merge(sub, rest.first(), *rest.drop(1).toTypedArray())
        }
        return mergePair(base.orEmpty().ifEmpty { "/" }, mergedSub)
    }

    private fun mergePair(base: String, sub: String): String {
        val prefix = if (base.startsWith("/")) "" else "/"
        val extra = if (sub == "/") {
            ""
        } else {
            val separator = if (base.endsWith("/")) "" else "/"
            val suffix = if (sub.startsWith("/")) sub.drop(1) else sub
            "$separator$suffix"
        }
        return "$prefix$base$extra"
    }
}
