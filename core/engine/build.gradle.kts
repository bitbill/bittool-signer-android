plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.engine"
    androidResources {
        // signer.js stays uncompressed in the APK so its SHA-256 can be checked against a published build.
        noCompress += "js"
    }
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    implementation(libs.quickjs.android)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
}
