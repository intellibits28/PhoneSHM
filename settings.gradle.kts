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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "PhoneSHM"

include(":app")
include(":core:device")
include(":core:physics")
include(":core:baseline")
include(":core:sensor")
include(":core:dsp")
include(":core:modal")
include(":core:location")
include(":core:audio")
include(":core:quality")
include(":core:database")
include(":core:storage")
include(":feature:onboarding")
include(":feature:measurement")
include(":feature:analysis")
include(":feature:report")
