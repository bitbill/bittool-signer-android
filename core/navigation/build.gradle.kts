plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.navigation"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
