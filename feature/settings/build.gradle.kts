plugins {
    alias(libs.plugins.bittool.android.feature)
}

android {
    namespace = "com.bittool.signer.feature.settings"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screen screenshots (src/test/.../screenshot) record into feature/settings/build/outputs/roborazzi/
            // for visual QA, no goldens committed. `-Psigner.screenshots=false` skips them.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    // Not in the version catalogue (DECISIONS §8): record-only screen screenshots, the :core:ui setup.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
