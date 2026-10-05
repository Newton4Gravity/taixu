plugins {
    alias(libs.plugins.taixu.android.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}