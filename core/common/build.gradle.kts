plugins {
    alias(libs.plugins.taixu.android.library)
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.xz)
}