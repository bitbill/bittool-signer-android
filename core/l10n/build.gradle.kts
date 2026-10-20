plugins {
    alias(libs.plugins.bittool.android.library)
}

android {
    namespace = "com.bittool.signer.core.l10n"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
