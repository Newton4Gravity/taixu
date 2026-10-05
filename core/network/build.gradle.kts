plugins {
    alias(libs.plugins.taixu.android.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}