plugins {
    alias(libs.plugins.bittool.android.compose)
    alias(libs.plugins.bittool.hilt)
}

android {
    namespace = "com.bittool.signer.core.security"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.l10n)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
