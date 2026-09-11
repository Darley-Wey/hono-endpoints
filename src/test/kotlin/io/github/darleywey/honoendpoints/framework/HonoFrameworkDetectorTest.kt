package io.github.darleywey.honoendpoints.framework

import junit.framework.TestCase

class HonoFrameworkDetectorTest : TestCase() {
    fun testDetectsHonoDependency() {
        assertTrue(
            HonoFrameworkDetector.dependsOnHono(
                """
                {
                  "dependencies": {
                    "hono": "^4.0.0"
                  }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            HonoFrameworkDetector.dependsOnHono(
                """
                {
                  "devDependencies": {
                    "hono": "4.7.0"
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    fun testIgnoresUnrelatedManifests() {
        assertFalse(
            HonoFrameworkDetector.dependsOnHono(
                """
                {
                  "name": "demo",
                  "dependencies": {
                    "express": "^5.0.0"
                  }
                }
                """.trimIndent(),
            ),
        )
        assertFalse(HonoFrameworkDetector.dependsOnHono(""))
    }

    fun testLooksLikeHonoImportAcceptsModuleSpecifiers() {
        assertTrue(HonoFrameworkDetector.looksLikeHonoImport("import { Hono } from 'hono'"))
        assertTrue(HonoFrameworkDetector.looksLikeHonoImport("""import Hono from "hono/tiny""""))
        assertTrue(HonoFrameworkDetector.looksLikeHonoImport("const { Hono } = require('hono')"))
        assertTrue(HonoFrameworkDetector.looksLikeHonoImport("const app = await import('hono')"))
        assertTrue(HonoFrameworkDetector.looksLikeHonoImport("""import { Hono } from "jsr:@hono/hono""""))
    }

    fun testLooksLikeHonoImportIgnoresUnrelatedStrings() {
        assertFalse(HonoFrameworkDetector.looksLikeHonoImport("app.get('/hono-smoke', handler)"))
        assertFalse(HonoFrameworkDetector.looksLikeHonoImport("""const name = "hono""""))
        assertFalse(HonoFrameworkDetector.looksLikeHonoImport("""import x from "hono-smoke""""))
        assertFalse(HonoFrameworkDetector.looksLikeHonoImport("require('express')"))
    }
}
