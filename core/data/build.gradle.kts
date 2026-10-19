plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.data"
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    api(projects.core.engine)
    api(projects.core.crypto)
    implementation(projects.core.l10n)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
}
