plugins {
    alias(libs.plugins.bittool.android.compose)
}

android {
    namespace = "com.bittool.signer.core.designsystem"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshot tests (src/test/.../screenshot) record PNGs of the design catalog into
            // core/designsystem/build/outputs/roborazzi/ for visual QA (no goldens are committed). They run with every
            // testDebugUnitTest; `-Psigner.screenshots=false` skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    // Strings only (LaunchView, navigation icons, dialog defaults): every user-visible text is a :core:l10n resource.
    implementation(projects.core.l10n)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Not in the version catalogue (DECISIONS §8): record-only component screenshots, the Ownbit 5.0 setup.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
