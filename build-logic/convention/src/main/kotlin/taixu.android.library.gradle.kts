package taixu.android.library

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByName
import org.gradle.api.tasks.testing.Test

class TaixuAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            configure<com.android.build.api.dsl.LibraryExtension> {
                namespace = "top.wkbin.taixu.${project.name.replace(':', '.')}"

                defaultConfig {
                    minSdk = 29
                    targetSdk = 34
                    versionCode = 1
                    versionName = "1.0.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                kotlinOptions {
                    jvmTarget = "17"
                    freeCompilerArgs += listOf(
                        "-Xopt-in=kotlin.RequiresOptIn",
                        "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                        "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi"
                    )
                }

                buildFeatures {
                    compose = true
                    aidl = true
                }

                composeOptions {
                    kotlinCompilerExtensionVersion = "1.5.14"
                }

                packagingOptions {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
                    }
                    jniLibs {
                        useLegacyPackaging = true
                    }
                }
            }

            dependencies {
                val libs = extensions.getByName<ExtensionAware>("libs")
                add("implementation", libs.findLibrary("androidx-core-ktx")!!)
                add("implementation", libs.findLibrary("kotlinx-coroutines-android")!!)
                add("implementation", libs.findLibrary("kotlinx-serialization-json")!!)
            }

            tasks.withType<Test> {
                useJUnitPlatform()
                maxParallelForks = 4
                maxHeapSize = "512m"
            }
        }
    }
}