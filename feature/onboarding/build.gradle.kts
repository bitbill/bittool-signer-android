plugins {
    alias(libs.plugins.bittool.android.feature)
}

android {
    namespace = "com.bittool.signer.feature.onboarding"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshots of Welcome and the introduction (src/test/.../screenshot) record PNGs into
            // feature/onboarding/build/outputs/roborazzi/ for visual QA (no goldens are committed).
            // `-Psigner.screenshots=false` skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    // Not in the version catalogue (DECISIONS §8): record-only screenshots, the setup :core:designsystem uses.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
