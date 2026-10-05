/**
 * TaiXu Root Build Configuration
 * 
 * Centralized configuration for all subprojects including:
 * - Common plugin application
 * - Shared dependencies
 * - Repository configuration
 * - Build quality checks
 */
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.14" apply false
    id("com.google.dagger.hilt") version "2.51.1" apply false
}

allprojects {
    group = "top.wkbin.taixu"
    version = "1.0.0-SNAPSHOT"

    repositories {
        // Repository configuration is centralized in settings.gradle.kts
        // via dependencyResolutionManagement
    }
}

subprojects {
    // Apply common Kotlin/JVM configuration
    if (plugins.hasPlugin("org.jetbrains.kotlin.android") || plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            kotlinOptions {
                jvmTarget = "17"
                freeCompilerArgs += listOf(
                    "-Xopt-in=kotlin.RequiresOptIn",
                    "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi"
                )
            }
        }
    }

    // Configure test tasks
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            exceptionFormat = "full"
            showStandardStreams = true
        }
    }

    // Spotless / formatting would go here
    // tasks.named("spotlessApply") { ... }
}

configure<org.gradle.api.tasks.testing.Test> {
    maxParallelForks = 4
    maxHeapSize = "1g"
}