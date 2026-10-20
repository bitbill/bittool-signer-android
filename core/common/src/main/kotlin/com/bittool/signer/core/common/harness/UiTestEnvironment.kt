package com.bittool.signer.core.common.harness

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier

/**
 * One debug UI-test run (gaps.md G3, port of iOS `SimulatorUITestContext`), parsed from the launch-intent extras
 * `bts.uitest.*` by `app/src/debug` `DebugUiTestHarness.configure(intent)` **before** `super.onCreate()` of
 * `MainActivity`, on an emulator only. Extras delivered later through `onNewIntent` are ignored.
 */
data class UiTestRun(
    /** `bts.uitest.run`: a UUID string. */
    val id: String,
    /** `cacheDir/ui-tests/<id>/`: replaces `@SignerDataDir` (Vaults/, PublicWallets/, receipts.bin, FixtureKeys/). */
    val root: File,
    /** DataStore file name `ui-tests-<id>` replacing the production preferences file. */
    val preferencesName: String,
    /** `bts.uitest.fixtures`, clamped to 0…8; > 0 also marks onboarding completed. */
    val fixtures: Int,
    /** `bts.uitest.appLock=true` → App Lock preference on; null = leave unchanged. */
    val appLock: Boolean?,
    /** `bts.uitest.appearance` = `dark` | `light`, or null. */
    val appearance: String?,
    /** `bts.uitest.locale`, default `en-US`, applied with `AppCompatDelegate.setApplicationLocales` before composition. */
    val localeTag: String,
    /** `bts.uitest.allowCapture=true` → `FLAG_SECURE` stays off for this process (screenshot tour). */
    val allowCapture: Boolean,
) {
    init {
        require(fixtures in 0..MAX_FIXTURES)
    }

    companion object {
        const val MAX_FIXTURES = 8
    }
}

/**
 * The active run, if any. **Bound only by `app/src/debug`**; release builds bind nothing, so every production provider
 * injects `Optional<UiTestEnvironment>` (declared below) and must behave exactly as in production when it is empty or
 * [run] is null. Nothing that opens storage may run in `SignerApplication.onCreate` (the harness configures later).
 */
interface UiTestEnvironment {
    val run: UiTestRun?
}

/**
 * Qualifies a debug-only replacement of a production binding (`@UiTestOverride DeviceKeyStore`,
 * `@UiTestOverride BiometricAuthenticator`). The owning module declares `@BindsOptionalOf @UiTestOverride T` and binds
 * `T` to `override.orElse(production)`; `app/src/debug` provides the override, returning the production instance when
 * no run is active.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class UiTestOverride

@Module
@InstallIn(SingletonComponent::class)
abstract class UiTestEnvironmentModule {
    @BindsOptionalOf
    abstract fun uiTestEnvironment(): UiTestEnvironment
}
