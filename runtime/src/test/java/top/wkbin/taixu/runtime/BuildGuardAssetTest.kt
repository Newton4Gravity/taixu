package top.wkbin.taixu.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildGuardAssetTest {
    private val assets = File("../app/src/main/assets")
    private val templateAssets = File("../project-template/src/main/assets")
    private val offlineAndroidInstaller =
        File("../assets/plugins/android-suite-offline/payload/scripts/install-android-suite.sh")
    private val offlineAndroidVerifier =
        File("../assets/plugins/android-suite-offline/payload/scripts/verify-android-suite.sh")
    private val offlineAndroidManifest =
        File("../assets/plugins/android-suite-offline/manifest.json")

    @Test
    fun managedBuildEntriesHavePortableShebangs() {
        listOf("gradle", "gradlew", "flutter", "taixu-build", "taixu-build-guard", "adb", "logcat-grabber").forEach { name ->
            val script = File(assets, "bin/$name")
            assertTrue("missing $script", script.isFile)
            assertTrue("$name must use /bin/sh", script.readText().startsWith("#!/bin/sh\n"))
            assertFalse("$name must not contain CRLF", script.readText().contains('\r'))
        }
        listOf("taixu-build.sh", "taixu-build-analyze.sh", "taixu-build-verify.sh").forEach { name ->
            val script = File(assets, "scripts/$name")
            assertTrue("missing $script", script.isFile)
            assertTrue("$name must use /bin/sh", script.readText().startsWith("#!/bin/sh\n"))
            assertFalse("$name must not contain CRLF", script.readText().contains('\r'))
        }
    }

    @Test
    fun androidGuardPinsArmToolsAndDisablesSdkDownloads() {
        val guard = File(assets, "bin/taixu-build-guard").readText()
        assertTrue(guard.contains("android.builder.sdkDownload=false"))
        assertTrue(guard.contains("android.aapt2FromMavenOverride"))
        assertTrue(guard.contains("android-arm64"))
        assertTrue(guard.contains("/opt/taixu/scripts/taixu-build.sh"))
        assertTrue(File(assets, "scripts/taixu-build-verify.sh").isFile)
        assertTrue(File(assets, "scripts/taixu-build-analyze.sh").isFile)
    }

    @Test
    fun qemuFallbackIsExplicitAndNeverPretendsToModifyArmSession() {
        val guard = File(assets, "bin/taixu-build-guard").readText()
        assertTrue(guard.contains("[--qemu]"))
        val engine = File(assets, "scripts/taixu-build.sh").readText()
        assertTrue(engine.contains("Regular ARM64 terminal cannot switch directly"))
        assertTrue(engine.contains("analyze"))
        assertTrue(engine.contains("TAIXU_OFFLINE"))
        assertTrue(engine.contains("keep_project_arm64_only"))
        assertTrue(engine.contains("armeabi-v7a|x86|x86_64"))
        val analyzer = File(assets, "scripts/taixu-build-analyze.sh").readText()
        assertFalse(analyzer.contains("lib/(x86_64|x86)/"))
        assertTrue(analyzer.contains("non_arm64_abi=declared"))
    }

    @Test
    fun managedArmBuildsUseOnlyGradleNdkPathLocator() {
        val androidBuild = File(assets, "scripts/build_android.sh").readText()
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        val ndkSetup = File(assets, "scripts/setup_termux_ndk.sh").readText()
        val managedNdkPolicy = File(assets, "scripts/taixu-android-ndk.gradle").readText()
        val buildEntry = File(assets, "scripts/taixu-build.sh").readText()

        listOf(androidBuild, flutterBuild).forEach { script ->
            assertTrue(script.contains("ndk\\.dir"))
            assertFalse(script.contains("ndk.dir=%s"))
        }
        assertTrue(ndkSetup.contains("androidExtension.ndkPath = taixuNdkPath"))
        assertTrue(managedNdkPolicy.contains("androidExtension.ndkPath = taixuNdkPath"))
        assertTrue(managedNdkPolicy.contains("/opt/taixu/toolchains/android/ndk"))
        // ARM64-only sandbox: AARs (e.g., androidx.graphics.path) bundle x86/x86_64
        // native libs; managed policy must strip x86 ABIs and pin arm64-v8a when unfiltered,
        // else APK rejected by artifact validation due to x86_abi_present.
        assertTrue(managedNdkPolicy.contains("removeAll(['x86', 'x86_64', 'armeabi-v7a'])"))
        assertTrue(managedNdkPolicy.contains("abiFilters.add('arm64-v8a')"))
        assertTrue(managedNdkPolicy.contains("aligning project NDK version"))
        val androidTemplate = File(templateAssets, "templates/android/jetpack-compose/app/build.gradle.kts").readText()
        assertTrue(androidTemplate.contains("abiFilters += \"arm64-v8a\""))
        assertTrue(buildEntry.contains("cp \"${'$'}managed_ndk_policy\" \"${'$'}GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle\""))
        listOf(androidBuild, flutterBuild, buildEntry).forEach { script ->
            assertTrue(script.contains("od -An -t x1 -j 18 -N 2"))
            assertTrue(script.contains("b700"))
            assertFalse(script.contains("-tu2"))
        }
        assertTrue(androidBuild.contains("GRADLE_HOME"))
        assertTrue(androidBuild.contains("TAIXU_CMAKE_HOME"))
        assertTrue(androidBuild.contains("TAIXU_NINJA_HOME"))
        assertTrue(flutterBuild.contains("GRADLE_HOME"))
    }

    @Test
    fun managedGradleConfigurationUsesConsistentMobileLimits() {
        val androidBuild = File(assets, "scripts/build_android.sh").readText()
        val qemuBuild = File(assets, "scripts/build_android_qemu.sh").readText()
        val androidSetup = File(assets, "scripts/setup_android_core.sh").readText()
        val offlineProperties = File("../assets/plugins/android-suite-offline/payload/config/gradle.properties").readText()

        listOf(androidBuild, qemuBuild).forEach { script ->
            assertTrue(script.contains("--info"))
            assertTrue(script.contains("--no-daemon"))
            assertTrue(script.contains("--max-workers=2"))
            assertTrue(script.contains("-Xmx1024m"))
            assertTrue(script.contains("-XX:MaxMetaspaceSize=384m"))
        }
        listOf(androidSetup, offlineProperties).forEach { config ->
            assertTrue(config.contains("org.gradle.daemon=false"))
            assertTrue(config.contains("org.gradle.parallel=false"))
            assertTrue(config.contains("org.gradle.workers.max=2"))
            assertTrue(config.contains("org.gradle.jvmargs=-Xmx1024m"))
        }
    }

    @Test
    fun managedFlutterBuildsExposeDependencyActivity() {
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        val qemuFlutterBuild = File(assets, "scripts/build_flutter_qemu.sh").readText()

        listOf(flutterBuild, qemuFlutterBuild).forEach { script ->
            assertTrue(script.contains("pub get --offline --verbose"))
            assertTrue(script.contains("pub get --verbose"))
            assertTrue(script.contains("--verbose"))
        }
    }

    @Test
    fun offlineAndroidInstallerDoesNotRequireSystemUnzip() {
        val script = offlineAndroidInstaller.readText()
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertFalse(script.contains('\r'))
        assertTrue(offlineAndroidVerifier.readText().startsWith("#!/bin/sh\n"))
        assertFalse(offlineAndroidVerifier.readText().contains('\r'))
        assertTrue(script.contains("extract_zip()"))
        assertTrue(script.contains("\"${'$'}JDK_HOME/bin/jar\" xf \"${'$'}archive\""))
        assertTrue(script.contains("extract_zip \"${'$'}ARCHIVES/gradle-${'$'}GRADLE_VERSION-bin.zip\""))
        assertTrue(script.contains("extract_zip \"${'$'}ARCHIVES/platform-34-ext7_r03.zip\""))
        assertTrue(script.contains("extract_zip \"${'$'}ARCHIVES/build-tools_r35_linux.zip\""))
        assertTrue(script.contains("extract_zip \"${'$'}ARCHIVES/android-sdk-tools-static-aarch64.zip\""))
        assertTrue(script.contains("extract_zip \"${'$'}ARCHIVES/ninja-linux-aarch64.zip\""))
    }

    @Test
    fun flutterBuildSelfHealsMissingUnzipViaJdkJar() {
        // Flutter toolchain extracts engine cache depends on unzip; minimal rootfs lacks it,
        // install-time temp shim not on build PATH. Build script must self-heal before
        // invoking flutter: use JDK jar to create persistent /opt/taixu/bin/unzip (PATH first).
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        assertTrue(flutterBuild.contains("if ! command -v unzip >/dev/null 2>&1"))
        assertTrue(flutterBuild.contains("/opt/taixu/bin/unzip"))
        assertTrue(flutterBuild.contains("Missing unzip tool"))
        // Offline suite install should also persist same compat layer, fresh sandbox has it from start.
        val installer = offlineAndroidInstaller.readText()
        assertTrue(installer.contains("/opt/taixu/bin/unzip"))
        // Build script PATH must include /opt/taixu/bin for self-heal shim to be visible.
        assertTrue(flutterBuild.contains("export PATH=\"/opt/taixu/bin:"))
    }

    @Test
    fun flutterSdkLayoutIncludesPlatformToolsForLocateAndroidSdk() {
        // Flutter locateAndroidSdk only recognizes SDK dir containing platform-tools/adb;
        // missing it reports "No Android SDK found". Installer & build scripts must provide this layout.
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        val installer = offlineAndroidInstaller.readText()
        listOf(flutterBuild, installer).forEach { script ->
            assertTrue(script.contains("platform-tools/adb"))
            assertTrue(script.contains("licenses/android-sdk-license"))
        }
    }

    @Test
    fun flutterEngineLinksNativeArm64GenSnapshotForAndroidAot() {
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        val flutterSetup = File(assets, "scripts/setup_flutter.sh").readText()
        val installer = offlineAndroidInstaller.readText()
        val verifier = offlineAndroidVerifier.readText()
        val buildEntry = File(assets, "scripts/taixu-build.sh").readText()

        listOf(flutterBuild, flutterSetup, installer, buildEntry).forEach { script ->
            assertTrue(script.contains("linux-arm64/gen_snapshot"))
            assertTrue(script.contains("android-arm64"))
        }
        assertTrue(verifier.contains("require_aarch64 /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot"))
        assertTrue(buildEntry.contains("check_elf_machine \"${'$'}native_gen_snapshot\" b700 \"Flutter gen_snapshot\""))
    }

    @Test
    fun offlineAndroidInstallerDoesNotRequireOptionalFileOrXzCommands() {
        val installer = offlineAndroidInstaller.readText()
        val verifier = offlineAndroidVerifier.readText()
        val optionalFileCommand = Regex("""(?m)(^|[;&|]\s*|if\s+)file\s+""")

        listOf(installer, verifier).forEach { script ->
            assertTrue(script.contains("od -An -t x1"))
            assertTrue(script.contains("b700"))
            assertFalse(optionalFileCommand.containsMatchIn(script))
        }
        assertFalse(installer.contains("tar -xJ"))
        assertTrue(installer.contains("android-ndk-r29-aarch64.tar.gz"))
        assertTrue(installer.contains("tar -xzf"))
    }

    @Test
    fun javaLauncherGuardsRejectWrapperScriptExecLoopsBeforeJvmStart() {
        // Forensic finding: JDK bin/java replaced with exec wrapper script, TOOL_DIR bin/java
        // is symlink back to JDK — infinite mutual exec, each round through PRoot ptrace,
        // JVM zero output, CPU saturated. All entry points must reject non-ELF launchers
        // via ELF magic before starting JVM.
        val androidBuild = File(assets, "scripts/build_android.sh").readText()
        val flutterBuild = File(assets, "scripts/build_flutter.sh").readText()
        val buildEntry = File(assets, "scripts/taixu-build.sh").readText()

        listOf(androidBuild, flutterBuild, buildEntry).forEach { script ->
            assertTrue(script.contains("7f454c46"))
        }
        assertTrue(androidBuild.contains("Suspected wrapper script/symlink loop"))
        assertTrue(flutterBuild.contains("Suspected wrapper script/symlink loop"))
        assertTrue(buildEntry.contains("not_elf"))
    }

    @Test
    fun offlineAndroidInstallerLocksToolchainAndAssertsJdkLauncherElf() {
        val installer = offlineAndroidInstaller.readText()
        val verifier = offlineAndroidVerifier.readText()

        // Assembly holds exclusive lock, build holds shared lock: prevent "build while rewriting toolchain".
        assertTrue(installer.contains("flock -x -w 300 9"))
        assertTrue(installer.contains("/opt/taixu/locks/android-toolchain.lock"))
        // JDK launcher static assertion + command link chain terminal verification.
        assertTrue(installer.contains("is_aarch64_elf \"${'$'}JDK_HOME/bin/java\""))
        assertTrue(installer.contains("readlink -f \"${'$'}chain_entry\""))
        assertTrue(installer.contains("does not resolve to the JDK launcher"))
        // Verifier does ELF static assertion before -version, avoids hanging on infinite exec.
        assertTrue(verifier.contains("require_aarch64 /opt/taixu/bin/java"))
        assertTrue(verifier.indexOf("require_aarch64 /opt/taixu/bin/java") <
            verifier.indexOf("require_command /opt/taixu/bin/java -version"))
    }

    @Test
    fun offlineZipExtractionRestoresRequiredExecutableBits() {
        val script = offlineAndroidInstaller.readText()

        assertTrue(script.contains("chmod 755 \"/opt/gradle-${'$'}GRADLE_VERSION/bin/gradle\""))
        assertTrue(script.contains("for executable in aapt aapt2 aidl zipalign d8 apksigner"))
        assertTrue(script.contains("chmod 755 \"${'$'}ANDROID_HOME/build-tools/${'$'}BUILD_TOOLS_VERSION/${'$'}executable\""))
    }

    @Test
    fun offlineNdkDiscoveryAcceptsToolSymlinksAndNamesMissingResources() {
        val script = offlineAndroidInstaller.readText()
        val verifier = offlineAndroidVerifier.readText()

        assertTrue(script.contains("\\( -type f -o -type l \\) -name clang"))
        assertTrue(script.contains("\\( -type f -o -type l \\) -name llvm-strip"))
        assertTrue(script.contains("need \"${'$'}NDK_CLANG\" \"NDK clang\""))
        assertTrue(script.contains("need \"${'$'}NDK_STRIP\" \"NDK llvm-strip\""))
        assertTrue(script.contains("missing offline resource: ${'$'}{resource_name:-unknown}"))
        assertFalse(verifier.contains("test -x /opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt/*"))
        assertTrue(verifier.contains("-name llvm-strip -print -quit"))
        assertTrue(verifier.contains("require_executable \"${'$'}NDK_STRIP\""))
        assertTrue(verifier.contains("require_aarch64 \"${'$'}NDK_STRIP\""))
    }

    @Test
    fun offlineCommandLinksArePublishedBeforeFinalVerification() {
        val manifest = offlineAndroidManifest.readText()
        val installer = offlineAndroidInstaller.readText()
        val commandLinksBlock = Regex("\"commandLinks\"\\s*:\\s*\\[([^]]+)]")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: error("commandLinks is missing from the offline Android manifest")
        val commandLinks = Regex("\"([^\"]+)\"")
            .findAll(commandLinksBlock)
            .map { it.groupValues[1] }
            .toSet()
        val publishedCommands = Regex("""for command in ([^;]+); do""")
            .find(installer)
            ?.groupValues
            ?.get(1)
            ?.split(Regex("""\s+"""))
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: error("global command-link loop is missing from the offline Android installer")

        assertTrue(
            "commandLinks missing from global link loop: ${commandLinks - publishedCommands}",
            publishedCommands.containsAll(commandLinks),
        )
        assertTrue(installer.contains("/bin/sh \"${'$'}PAYLOAD/scripts/verify-android-suite.sh\""))
    }

    @Test
    fun offlineInstallerPublishesStructuredMonotonicProgress() {
        val script = offlineAndroidInstaller.readText()
        val percentages = Regex("""progress (\d{1,3}) """)
            .findAll(script)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertTrue(percentages.size >= 12)
        assertTrue(percentages.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(percentages.first() > 0)
        assertTrue(percentages.last() == 100)
        assertTrue(script.contains("[EXTRACT]"))
        assertTrue(script.contains("[COMMAND]"))
        assertTrue(script.contains("[VERIFY]"))
        assertTrue(script.contains("[TAIXU_PROGRESS:%s]"))
    }

    @Test
    fun offlinePluginCompatibilityShimUsesJdkJar() {
        val installer = File("../tools/src/main/java/top/wkbin/taixu/runtime/tools/GenericRecipeInstaller.kt").readText()
        assertTrue(installer.contains("localUnzipCompatibilityCommand"))
        assertTrue(installer.contains("/opt/taixu/toolchains/android/jdk/bin/jar"))
        assertTrue(installer.contains("jar_bin"))
        assertTrue(installer.contains("TAIXU_TOOL_DIR/bin/unzip"))
    }
}
