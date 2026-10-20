plugins {
    alias(libs.plugins.bittool.android.feature)
}

android {
    namespace = "com.bittool.signer.feature.sign"

    testOptions {
        unitTests.all { test ->
            // Roborazzi screenshots of the Scan tab pages (src/test/.../screenshot) record into
            // feature/sign/build/outputs/roborazzi/ for visual QA (no goldens committed). `-Psigner.screenshots=false`
            // skips them on a slow machine.
            test.systemProperty("roborazzi.test.record", "true")
            if (project.findProperty("signer.screenshots")?.toString() == "false") test.exclude("**/screenshot/**")
        }
    }
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric Compose tests: Espresso ≥ 3.6 injects input through InputManagerGlobal on current SDKs.
    testImplementation(libs.androidx.espresso.core)
    // Not in the version catalogue (DECISIONS §8): record-only screenshots, the setup of :core:ui.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
