package top.wkbin.taixu.runtime.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.runtime.ProjectType

class BuildEnvironmentPreflightTest {
    @Test
    fun androidArmPreflightRequiresPinnedArmArtifactsAndDisablesSdkDownload() {
        val command = BuildEnvironmentPreflight.command("/workspace/a project's app", ProjectType.ANDROID)
        assertTrue(command.contains("android.builder.sdkDownload=false"))
        assertTrue(command.contains("TAIXU_NDK_PATH"))
        assertTrue(command.contains("TAIXU_AAPT2_PATH"))
        assertTrue(command.contains("/opt/taixu/toolchains/android/jdk/bin/java"))
        assertTrue(command.contains("\$ANDROID_HOME/build-tools/35.0.0/aapt2"))
        assertTrue(command.contains("/opt/taixu/toolchains/android/ndk"))
        assertTrue(command.contains("-name clang"))
        assertTrue(command.contains("od -An -t x1 -j 18 -N 2"))
        assertTrue(command.contains("= b700"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("readlink -f"))
        // Java launcher must resolve to real ELF: wrapper script loop (script exec symlink,
        // symlink points back to script) in PRoot is zero-output, CPU-saturating infinite
        // exec loop; preflight must intercept via magic bytes before JVM starts,
        // not just check libjvm.so.
        assertTrue(command.contains("7f454c46"))
        assertTrue(command.contains("not_elf"))
        assertFalse(command.contains("-tu2"))
        assertFalse(command.contains("wrapper_incomplete"))
        assertTrue(command.contains("fail aapt2_arch"))
        assertTrue(command.contains("a project's app".replace("'", "'\\\''")))
    }

    @Test
    fun flutterQemuPreflightRequiresX86DartAndAndroidHost() {
        val command = BuildEnvironmentPreflight.command("/workspace/flutter", ProjectType.FLUTTER, qemu = true)
        assertTrue(command.contains("uname -m"))
        assertTrue(command.contains("= 3e00"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("fail dart_arch"))
        assertTrue(command.contains("pubspec.yaml"))
        assertTrue(command.contains("android"))
    }

    @Test
    fun flutterArmPreflightAcceptsOfflineSuiteLayoutWithoutLegacyMarker() {
        val command = BuildEnvironmentPreflight.command("/workspace/flutter", ProjectType.FLUTTER)

        assertTrue(command.contains("/opt/taixu/toolchains/android/jdk/bin/java"))
        assertTrue(command.contains("/opt/flutter/bin/flutter"))
        assertTrue(command.contains("/opt/flutter/bin/cache/dart-sdk/bin/dart"))
        assertFalse(command.contains("flutter_marker"))
        assertTrue(command.contains("java_arch_machine"))
        assertTrue(command.contains("libjvm.so"))
        assertTrue(command.contains("readlink -f"))
    }

    @Test
    fun androidArmPreflightGatesCMakeNinjaBehindNativeDetection() {
        val command = BuildEnvironmentPreflight.command("/workspace/app", ProjectType.ANDROID)
        // CMake/Ninja aligned with taixu-build.sh doctor has_native: only projects
        // with native code require them; pure Kotlin/Java projects doing online
        // assembly (android-core skips CMake/Ninja) must not be falsely flagged
        // as "missing build environment".
        assertTrue(command.contains("has_native=0"))
        assertTrue(command.contains("CMakeLists.txt"))
        assertTrue(command.contains("app/src/main/cpp"))
        assertTrue(command.contains("externalNativeBuild|ndkBuild"))
        assertTrue(command.contains("fail cmake_missing"))
        assertTrue(command.contains("fail ninja_missing"))
        // Checks must be wrapped in has_native condition and accept system path fallback
        val cmakeCheck = command.substringAfter("if [ \"\$has_native\" = 1 ]; then").substringBefore("fi")
        assertTrue(cmakeCheck.contains("fail cmake_missing"))
        assertTrue(cmakeCheck.contains("fail ninja_missing"))
        assertTrue(cmakeCheck.contains("/usr/bin/cmake"))
        assertTrue(cmakeCheck.contains("/usr/bin/ninja"))
    }

    @Test
    fun describeFailureNamesTheActuallyMissingTool() {
        // User feedback "missing gradle" often masks single-item failures like
        // cmake/ninja/java under generic message
        assertEquals("Missing Gradle 8.14.2", BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_FAIL: gradle_missing"))
        assertEquals("Missing CMake (required for projects with native code)", BuildEnvironmentPreflight.describeFailure("some log\nTAIXU_PREFLIGHT_FAIL: cmake_missing"))
        assertEquals("Missing Ninja (required for projects with native code)", BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_FAIL: ninja_missing"))
        assertEquals("Missing JDK 17", BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_FAIL: java_missing"))
        assertEquals(
            "JDK arch mismatch (java_arch path=/usr/bin/java machine=unreadable expected=b700)",
            BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_FAIL: java_arch path=/usr/bin/java machine=unreadable expected=b700"),
        )
        assertEquals("Missing Android Platform 34", BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_FAIL: android_platform"))
        assertNull(BuildEnvironmentPreflight.describeFailure("TAIXU_PREFLIGHT_OK"))
        assertNull(BuildEnvironmentPreflight.describeFailure(""))
    }
}

