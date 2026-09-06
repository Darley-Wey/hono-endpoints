import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

group = "io.github.darleywey"
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    intellijPlatform {
        webstorm("2026.2.0.1")
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
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
        useJUnitPlatform()
    }
}
