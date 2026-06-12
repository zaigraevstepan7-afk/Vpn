// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose compiler is bundled with the Kotlin Gradle plugin since Kotlin 2.0
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
