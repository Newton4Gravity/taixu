pluginManagement {
    includeBuild("build-logic")
    // CI (GitHub Actions auto-injects CI=true) is overseas; Aliyun mirror sync is delayed and slow.
    // Local development can also force official repos via -PuseOfficialRepos=true or USE_OFFICIAL_REPOS=true.
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        if (useOfficialRepos) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            // Use Aliyun mirrors for plugins and artifacts
            // gradlePluginPortal() (plugins.gradle.org) is unreachable from this environment
            // Official repos (google(), mavenCentral()) fail: Maven Central 403, Google Maven 404
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useOfficialRepos = System.getenv("CI") == "true" ||
        providers.gradleProperty("useOfficialRepos").orNull == "true" ||
        System.getenv("USE_OFFICIAL_REPOS") == "true"
    repositories {
        // Local repo: hosts third-party prebuilt AARs (e.g., Termux terminal-emulator / terminal-view),
        // avoids AGP build error hasLocalAarDeps when library modules depend on local files(*.aar) directly
        maven {
            url = uri(rootDir.resolve("repo"))
        }
        if (useOfficialRepos) {
            google()
            mavenCentral()
        } else {
            // Official repos (google(), mavenCentral()) fail: Maven Central 403, Google Maven 404
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
                isAllowInsecureProtocol = false
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/central")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        // Kadb's SPAKE2 Android impl only on JitPack; restrict to exact group to avoid widening resolution scope.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "TaiXu"
include(":app")
include(":baselineprofile")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:security")
include(":runtime")
include(":project-template")
include(":tools")
include(":harness")
include(":feature:theme")
include(":feature:components")
include(":feature:home")
include(":feature:chat")
include(":feature:terminal")
include(":feature:workspace")
include(":feature:workflow")
include(":feature:settings")
include(":feature:developer")
include(":feature:custom_iteration")
include(":feature:onboarding")
include(":feature:navigation")
include(":core:browser")
include(":runtime:browser")
include(":feature:browser")
include(":feature:git")
include(":feature:preview")
