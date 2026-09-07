package io.github.darleywey.honoendpoints.framework

import junit.framework.TestCase

class HonoSymbolsTest : TestCase() {
    fun testAcceptsHonoPackageSpecifiers() {
        assertTrue(HonoSymbols.isHonoModule("hono"))
        assertTrue(HonoSymbols.isHonoModule("hono/tiny"))
        assertTrue(HonoSymbols.isHonoModule("hono/quick"))
        assertTrue(HonoSymbols.isHonoModule("jsr:@hono/hono"))
        assertTrue(HonoSymbols.isHonoModule("jsr:@hono/hono/tiny"))
    }

    fun testRejectsOtherPackages() {
        assertFalse(HonoSymbols.isHonoModule(null))
        assertFalse(HonoSymbols.isHonoModule(""))
        assertFalse(HonoSymbols.isHonoModule("hono-foo"))
        assertFalse(HonoSymbols.isHonoModule("@hono/zod-openapi"))
        assertFalse(HonoSymbols.isHonoModule("express"))
    }

    fun testCompositionMethods() {
        assertFalse(HonoSymbols.isTransparentChainMethod("basePath"))
        assertTrue(HonoSymbols.isTransparentChainMethod("route"))
        assertFalse(HonoSymbols.isTransparentChainMethod("mount"))
    }

    fun testHttpMethodsRemainTransparentChains() {
        assertTrue(HonoSymbols.isHttpMethod("get"))
        assertTrue(HonoSymbols.isTransparentChainMethod("get"))
        assertTrue(HonoSymbols.isTransparentChainMethod("use"))
        assertTrue(HonoSymbols.isTransparentChainMethod("onError"))
    }

    fun testUnquotesModuleSpecifiers() {
        assertEquals("hono", HonoSymbols.unquote("'hono'"))
        assertEquals("hono/tiny", HonoSymbols.unquote("\"hono/tiny\""))
        assertEquals("hono", HonoSymbols.unquote("hono"))
    }
}
