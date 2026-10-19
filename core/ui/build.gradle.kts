plugins {
    alias(libs.plugins.bittool.android.compose)
}

android {
    namespace = "com.bittool.signer.core.ui"
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    implementation(projects.core.l10n)
    api(projects.core.designsystem)
    api(projects.core.scanner)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
