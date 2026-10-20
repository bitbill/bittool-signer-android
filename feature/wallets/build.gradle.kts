plugins {
    alias(libs.plugins.bittool.android.feature)
}

android {
    namespace = "com.bittool.signer.feature.wallets"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshots (src/test/.../screenshot) record the main screens in light and dark into
            // feature/wallets/build/outputs/roborazzi/ for visual QA (no goldens are committed). They run with every
            // testDebugUnitTest; `-Psigner.screenshots=false` skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    // Robolectric Compose tests: Espresso ≥ 3.6 injects input through InputManagerGlobal on current SDKs
    // (ui-test-junit4 alone resolves 3.5.0, whose InputManager.getInstance() reflection fails).
    testImplementation(libs.androidx.espresso.core)
    // Not in the version catalogue (DECISIONS §8): record-only screen screenshots, the Ownbit 5.0 / :core:ui setup.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
