import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
        webstorm("2026.2.0.1")
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.darleywey.hono-endpoints"
        name = "Hono Endpoints"
        version = providers.gradleProperty("pluginVersion")

        description = """
            Native Hono route discovery for the JetBrains Endpoints tool window.
            Discover Hono HTTP routes, navigate to their source declarations, and resolve composed route paths.
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
}

tasks {
    test {
        useJUnit()
    }
}
