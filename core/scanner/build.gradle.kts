plugins {
    alias(libs.plugins.bittool.android.compose)
    alias(libs.plugins.bittool.hilt)
}

android {
    namespace = "com.bittool.signer.core.scanner"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.l10n)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Robolectric Compose tests: Espresso ≥ 3.6 injects input through InputManagerGlobal on current SDKs
    // (ui-test-junit4 alone resolves 3.5.0, whose InputManager.getInstance() reflection fails).
    testImplementation(libs.androidx.espresso.core)
}
