import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The black-box end-to-end suite (gaps.md G1–G3, docs/qa/e2e.md): the port of the iOS `BitToolSignerUITests`, the launch
 * smoke test and the screenshot tour, driving the `apkDebug` app from outside with UiAutomator.
 *
 * Self-instrumenting: the test APK instruments itself, so the app runs in its own process and the suite can force-stop
 * and cold-launch it (App Lock's cold-launch rule, the harness reset), as XCUITest does.
 *
 * Device: the suite runs on ONE emulator only, `-Pe2e.serial` (default `emulator-5554`, the emulator without a screen
 * lock). AGP selects devices through the `ANDROID_SERIAL` environment variable (its `--serial` option fails in AGP 9.4.1:
 * it removes from an immutable list), so the connected tasks of this module refuse to start unless `ANDROID_SERIAL` is
 * exactly that serial; without it AGP would run on every connected device.
 *
 *     ANDROID_SERIAL=emulator-5554 tools/gradle-slot.sh ./gradlew :e2e:connectedDebugAndroidTest
 *     ANDROID_SERIAL=emulator-5554 tools/gradle-slot.sh ./gradlew :e2e:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=com.bittool.signer.e2e.ScreenshotTour
 *
 * Screenshots (the tour and the launch smoke) are written on the device to the instrumentation argument `shotDir`
 * (default `/data/local/tmp/bittool-e2e-shots`) and pulled to `<root>/build/e2e-shots` after every run (`pullE2eShots`).
 */
plugins {
    // AGP (and with it com.android.test) is already on the build classpath through the root project's plugins block;
    // a versioned request for it fails ("already on the classpath with an unknown version").
    id("com.android.test")
}

/** The only device the suite may touch (docs/qa/e2e.md §1). */
val e2eSerial: String = providers.gradleProperty("e2e.serial").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "emulator-5554"

/** Where the tests write their PNGs on the device (shell-owned, so it survives the uninstall after the run). */
val deviceShotDir: String = providers.gradleProperty("android.testInstrumentationRunnerArguments.shotDir").orNull
    ?: "/data/local/tmp/bittool-e2e-shots"

android {
    namespace = "com.bittool.signer.e2e"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["shotDir"] = deviceShotDir
        // :app has the `distribution` dimension; the suite drives the direct-download build (apkDebug).
        missingDimensionStrategy("distribution", "apk")
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.self-instrumenting"] = true
    // No lint block: AGP has no lint task for com.android.test modules.
}

androidComponents {
    // The suite drives the debug harness, which exists in debug builds only.
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

// One device only: AGP otherwise runs the suite on every connected device (on the shared machine emulator-5556 and
// emulator-5558 must never be touched). AGP filters devices by ANDROID_SERIAL, so a run without exactly the e2e serial
// there is refused before anything is installed.
tasks.matching { (it.name.startsWith("connected") || it.name.startsWith("device")) && it.name.endsWith("AndroidTest") }
    .configureEach {
        val serial = e2eSerial
        val taskPath = path
        doFirst {
            val environment = System.getenv("ANDROID_SERIAL")?.trim().orEmpty()
            if (environment != serial) {
                throw GradleException(
                    "The e2e suite runs on $serial only: run it as `ANDROID_SERIAL=$serial tools/gradle-slot.sh ./gradlew " +
                        "$taskPath` (ANDROID_SERIAL is '${environment.ifEmpty { "unset" }}'; -Pe2e.serial chooses another device).",
                )
            }
        }
    }

val shotsOut = rootProject.layout.buildDirectory.dir("e2e-shots")
val adbExecutable = androidComponents.sdkComponents.adb

/** Pulls the device's shot directory to `<root>/build/e2e-shots` (runs after every connected run, also a failed one). */
val pullE2eShots = tasks.register<Exec>("pullE2eShots") {
    group = "verification"
    description = "Pulls the e2e screenshots from $e2eSerial:$deviceShotDir to build/e2e-shots."
    val out = shotsOut.get().asFile
    executable = adbExecutable.get().asFile.absolutePath
    args("-s", e2eSerial, "pull", "$deviceShotDir/.", out.absolutePath)
    isIgnoreExitValue = true
    doFirst { out.mkdirs() }
}

tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    finalizedBy(pullE2eShots)
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
}
