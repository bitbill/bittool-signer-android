plugins {
    alias(libs.plugins.bittool.android.feature)
}

android {
    namespace = "com.bittool.signer.feature.setup"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshots of the setup steps (src/test/.../screenshot) record PNGs into
            // feature/setup/build/outputs/roborazzi/ for visual QA (no goldens committed), like :core:ui.
            // `-Psigner.screenshots=false` skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    // Robolectric Compose tests: Espresso ≥ 3.6 injects input through InputManagerGlobal on current SDKs (the
    // ui-test-junit4 default resolves 3.5.0, whose InputManager reflection fails), as in :core:scanner.
    testImplementation(libs.androidx.espresso.core)

    // Not in the version catalogue (DECISIONS §8): record-only screenshots, the setup :core:ui and :core:designsystem use.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
