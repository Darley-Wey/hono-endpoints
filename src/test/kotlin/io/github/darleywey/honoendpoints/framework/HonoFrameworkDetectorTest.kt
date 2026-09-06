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
}
