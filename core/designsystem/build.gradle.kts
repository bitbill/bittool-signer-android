plugins {
    alias(libs.plugins.bittool.android.compose)
}

android {
    namespace = "com.bittool.signer.core.designsystem"
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
}
