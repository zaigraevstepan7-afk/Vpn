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
        google()
        mavenCentral()
        // JitPack is only needed if you decide to pull the Xray core from there
        // instead of dropping the .aar into app/libs (see README).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NebulaVPN"
include(":app")
