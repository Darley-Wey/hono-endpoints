package io.github.darleywey.honoendpoints.framework

import junit.framework.TestCase

class HonoPathTest : TestCase() {
    fun testMergesRootAndChild() {
        assertEquals("/users", HonoPath.merge("/", "/users"))
        assertEquals("/users", HonoPath.merge("/", "users"))
        assertEquals("/api/users", HonoPath.merge("/api", "/users"))
        assertEquals("/api/users", HonoPath.merge("/api/", "/users"))
        assertEquals("/api/users", HonoPath.merge("/api/", "users"))
    }

    fun testPreservesRootAndTrailingSlash() {
        assertEquals("/", HonoPath.merge("/", "/"))
        assertEquals("/api", HonoPath.merge("/api", "/"))
        assertEquals("/api/", HonoPath.merge("/api/", "/"))
        assertEquals("/api/users/", HonoPath.merge("/api", "users/"))
        assertEquals("/api/users/", HonoPath.merge("/api", "/users/"))
        assertEquals("/api/users/", HonoPath.merge("/api/", "/users/"))
    }

    fun testPreservesParametersAndWildcards() {
        assertEquals("/train/:cityCode", HonoPath.merge("/train", "/:cityCode"))
        assertEquals("/iap/apple/verify", HonoPath.merge("/iap/apple", "/verify"))
        assertEquals("/api/*", HonoPath.merge("/api", "*"))
        assertEquals("/api/*", HonoPath.merge("/api", "/*"))
        assertEquals("/foo/*", HonoPath.merge("/", "foo/*"))
    }

    fun testMergesNestedSegments() {
        assertEquals("/api/users/:id", HonoPath.merge("/api", "/users", "/:id"))
        assertEquals("/v1/train/:cityCode", HonoPath.merge("/v1", "/train", "/:cityCode"))
        assertEquals("/health", HonoPath.merge("/", "/health"))
    }

    fun testAddsLeadingSlashWhenMissing() {
        assertEquals("/api/users", HonoPath.merge("api", "users"))
        assertEquals("/users", HonoPath.merge(null, "/users"))
        assertEquals("/", HonoPath.merge(null, "/"))
        assertEquals("/", HonoPath.merge("", ""))
        assertEquals("/", HonoPath.merge(null, null))
    }

    fun testExtractsParameterNames() {
        assertEquals(listOf("id"), HonoPath.parameterNames("/users/:id"))
        assertEquals(listOf("cityCode"), HonoPath.parameterNames("/train/:cityCode"))
        assertEquals(listOf("id", "item"), HonoPath.parameterNames("/users/:id/items/:item"))
        assertEquals(listOf("*"), HonoPath.parameterNames("/api/*"))
        assertEquals(emptyList<String>(), HonoPath.parameterNames("/health"))
    }

    fun testConvertsHonoParamsToOpenApiPath() {
        assertEquals("/users/{id}", HonoPath.toOpenApiPath("/users/:id"))
        assertEquals("/train/{cityCode}", HonoPath.toOpenApiPath("/train/:cityCode"))
        assertEquals("/api/{*}", HonoPath.toOpenApiPath("/api/*"))
        assertEquals("/health", HonoPath.toOpenApiPath("/health"))
    }
}
