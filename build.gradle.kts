import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.darleywey"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath")
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            webstorm("2026.2.0.1")
        }
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginConfiguration {
        id = "io.github.darleywey.hono-endpoints"
        name = "Hono Endpoints"
        version = providers.gradleProperty("pluginVersion")

        description = """
            Native Hono support for the JetBrains Endpoints tool window.
            Discover Hono HTTP routes, navigate to source declarations, generate HTTP Client requests,
            and provide OpenAPI, native JavaScript/TypeScript documentation, and client examples.
        """.trimIndent()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }

        vendor {
            name = "Darley-Wey"
            url = "https://github.com/Darley-Wey"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.WebStorm, "2026.2.2")
        }
        // Keep API status notices in the report without treating them as incompatibility.
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        ))
    }
}

tasks {
    publishPlugin {
        providers.gradleProperty("releaseArchive").orNull?.let {
            archiveFile.set(layout.projectDirectory.file(it))
        }
    }

    test {
        useJUnit()
    }
}
