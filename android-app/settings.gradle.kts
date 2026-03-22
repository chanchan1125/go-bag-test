pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GoBag"
include(
    ":app",
    ":core:common",
    ":core:model",
    ":data:local",
    ":data:remote",
    ":data:repository",
    ":domain",
    ":feature:inventory",
    ":feature:checkmode",
    ":feature:sync",
    ":feature:pairing"
)
