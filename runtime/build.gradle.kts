plugins {
    alias(libs.plugins.taixu.android.library)
}

android {
    namespace = "top.wkbin.taixu.runtime"

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":project-template"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.xz)
    implementation(libs.okhttp)
    implementation(libs.bundles.shizuku)
    implementation(libs.hiddenapi.bypass)
    implementation(libs.kadb)
    // Termux VT100 emulator + PTY JNI (GPL-3.0). Exported so feature/terminal can attach TerminalView.
    api(libs.termux.terminal.emulator)
    testImplementation(libs.junit)
}
