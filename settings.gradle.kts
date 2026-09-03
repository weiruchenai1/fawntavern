pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
        maven("https://jitpack.io")
    }
}

rootProject.name = "fawntavern"
include(":app")
include(":core:diagnostics")
include(":core:model")
include(":core:extension")
include(":core:network")
include(":domain:generation")
include(":domain:chat")
include(":data:chat")
include(":data:backup")
include(":data:resources")
include(":data:generation")
include(":data:search")
include(":data:settings")
include(":data:speech")
include(":data:update")
include(":feature:chat")
include(":feature:api")
include(":feature:character")
include(":feature:extension")
include(":feature:diagnostics")
include(":feature:preset")
include(":feature:regex")
include(":feature:settings")
include(":feature:statistics")
include(":feature:translator")
include(":feature:worldbook")
include(":platform:plugin")
include(":platform:diagnostics")
