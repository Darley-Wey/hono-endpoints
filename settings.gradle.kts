import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "hono-endpoints"

pluginManagement {
    plugins {
        id("org.jetbrains.intellij.platform") version "2.18.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
