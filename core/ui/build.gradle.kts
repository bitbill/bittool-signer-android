plugins {
    alias(libs.plugins.bittool.android.compose)
}

android {
    namespace = "com.bittool.signer.core.ui"

    testOptions {
        unitTests.all { test ->
            // Roborazzi component screenshots (src/test/.../screenshot) record into core/ui/build/outputs/roborazzi/
            // for visual QA, no goldens committed. `-Psigner.screenshots=false` skips them.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    implementation(projects.core.l10n)
    api(projects.core.designsystem)
    // ARCHITECTURE §1.1. Wave 1 references no :core:scanner symbol (parallel compile); QR-bearing composites are
    // assembled in the features.
    api(projects.core.scanner)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // SignerClipboard clears an expired copy on the next foreground (ProcessLifecycleOwner).
    implementation(libs.androidx.lifecycle.process)

    // Not in the version catalogue (DECISIONS §8): record-only component screenshots, the Ownbit 5.0 setup.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
