// Toolchain versions are coupled and cannot be bumped independently:
// compileSdk 36 needs AGP 8.9+, AGP 8.9 needs Gradle 8.11+, and AGP 8.9 needs a
// Kotlin plugin newer than 1.9.x. Kotlin 2.x in turn moved the Compose compiler
// out of `composeOptions` and into its own plugin, which is why every module now
// applies `kotlin.plugin.compose` instead of pinning a compiler extension version.
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
