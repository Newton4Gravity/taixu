plugins {
    alias(libs.plugins.taixu.android.library)
}

android {
    namespace = "top.wkbin.taixu.tools"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
