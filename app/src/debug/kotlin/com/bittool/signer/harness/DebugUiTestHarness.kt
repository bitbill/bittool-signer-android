package com.bittool.signer.harness

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.common.harness.UiTestRun
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.data.preferences.DataStoreSignerPreferences
import com.bittool.signer.core.l10n.resolve
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.model.useAndWipe
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The debug-only UI-test harness (gaps.md G3, the Android replacement for iOS `SimulatorUITestContext`,
 * Services/SimulatorUITestContext.swift:1-63). Opt-in through the launch intent's extras, read by
 * `MainActivity.onCreate` **before** `super.onCreate()` (before Hilt injects anything), on an emulator only:
 *
 * | Extra | Effect |
 * |---|---|
 * | `bts.uitest.run=<uuid>` | the run: the store root is `cacheDir/ui-tests/<uuid>/` (Vaults/, PublicWallets/, receipts, FixtureKeys/) and the preferences a DataStore file `ui-tests-<uuid>`; production storage is never touched |
 * | `bts.uitest.reset=true` | deletes that directory and that preferences file first |
 * | `bts.uitest.fixtures=N` | 0…8 wallets "Public fixture 1…N" created through `SignerStore.createWallet` after the first load when none exist (onboarding marked done); a failure shows "UI fixture setup failed: …" |
 * | `bts.uitest.appLock=true` | App Lock preference on |
 * | `bts.uitest.appearance=dark\|light` | the Theme preference |
 * | `bts.uitest.locale=<tag>` | the per-app language (default `en-US`), applied before the first composition |
 * | `bts.uitest.allowCapture=true` | `FLAG_SECURE` stays off for this process (screenshot tour) |
 * | `bts.uitest.loseDeviceKeys=true` | deletes the run's wallet device keys before anything is injected: every wallet of the run (launched again with the same run id) then has a provably lost key, for the lost-key removal of backlog B9 (B24) |
 *
 * While a run is active, `DebugUiTestModule` binds the file-backed [FixtureDeviceKeyStore] (so a cold launch with the
 * same run id opens the same wallets), the file-backed [FixtureJournalCipher] (Activity works on an emulator without a
 * screen lock, QA round 1 sign-01) and the always-failing [FixtureBiometricAuthenticator]. The first configuration
 * of a process wins: extras of a later launch (a `singleTask` relaunch, `onNewIntent`) are ignored, because every
 * singleton is already bound to the first run — the e2e suite force-stops the app before each launch.
 *
 * This class exists only in debug builds; the release check `check<Variant>ReleaseHasNoHarness` fails a release whose
 * R8 output still contains it.
 */
object DebugUiTestHarness : UiTestEnvironment {
    const val EXTRA_RUN = "bts.uitest.run"
    const val EXTRA_RESET = "bts.uitest.reset"
    const val EXTRA_FIXTURES = "bts.uitest.fixtures"
    const val EXTRA_APP_LOCK = "bts.uitest.appLock"
    const val EXTRA_APPEARANCE = "bts.uitest.appearance"
    const val EXTRA_LOCALE = "bts.uitest.locale"
    const val EXTRA_ALLOW_CAPTURE = "bts.uitest.allowCapture"
    const val EXTRA_LOSE_DEVICE_KEYS = "bts.uitest.loseDeviceKeys"

    private const val TAG = "UiTestHarness"
    private const val DEFAULT_LOCALE = "en-US"

    /** The public BIP39 test vector of the iOS suite (UIT:9); fixture #1 has no passphrase. Never a real wallet. */
    private const val FIXTURE_PHRASE =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private const val FIXTURE_PASSWORD = "Public-Test-Password1!"

    @Volatile
    override var run: UiTestRun? = null
        private set

    private var configured = false

    /** Whether this device is an emulator ("simulator only" on iOS). Replaceable for JVM tests. */
    @VisibleForTesting
    internal var isEmulator: () -> Boolean = ::detectEmulator

    /** Whether to start the fixture creation (JVM tests of the parsing switch it off: no Hilt there). */
    @VisibleForTesting
    internal var createsFixtures: Boolean = true

    /**
     * Configures the run from [intent] once per process and returns it, or null when this launch is not a UI-test run
     * (no or malformed run id, or not an emulator). Main thread, before `super.onCreate()`.
     */
    @MainThread
    fun configure(context: Context, intent: Intent?): UiTestRun? {
        if (configured) return run
        configured = true
        val extras = intent?.extras ?: return null
        val parsed = parse(context, extras) ?: return null
        if (!isEmulator()) {
            Log.w(TAG) { "UI-test extras ignored: not an emulator" }
            return null
        }
        if (extras.flag(EXTRA_RESET) == true) reset(context, parsed)
        parsed.root.mkdirs()
        if (extras.flag(EXTRA_LOSE_DEVICE_KEYS) == true) {
            val lost = FixtureDeviceKeyStore.loseWalletKeys(File(parsed.root, FixtureDeviceKeyStore.DIRECTORY))
            Log.i(TAG) { "UI-test run: $lost wallet device keys deleted (lost)" }
        }
        run = parsed
        applyLocale(parsed.localeTag)
        if (createsFixtures && parsed.fixtures > 0) startFixtures(context, parsed)
        Log.i(TAG) { "UI-test run configured (${parsed.fixtures} fixtures)" }
        return parsed
    }

    /** The run described by [extras] (pure apart from the directory names), or null without a valid run id. */
    @VisibleForTesting
    internal fun parse(context: Context, extras: Bundle): UiTestRun? {
        val id = extras.text(EXTRA_RUN)?.trim()?.takeIf(::isUuid)?.lowercase() ?: return null
        val fixtures = extras.number(EXTRA_FIXTURES)?.coerceIn(0, UiTestRun.MAX_FIXTURES) ?: 0
        val appearance = extras.text(EXTRA_APPEARANCE)?.trim()?.lowercase()?.takeIf { it == "dark" || it == "light" }
        return UiTestRun(
            id = id,
            root = File(context.cacheDir, "ui-tests/$id"),
            preferencesName = "ui-tests-$id",
            fixtures = fixtures,
            appLock = extras.flag(EXTRA_APP_LOCK),
            appearance = appearance,
            localeTag = extras.text(EXTRA_LOCALE)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCALE,
            allowCapture = extras.flag(EXTRA_ALLOW_CAPTURE) == true,
        )
    }

    /** iOS `--reset-ui-fixtures`: the run's directory and its preferences file. */
    @VisibleForTesting
    internal fun reset(context: Context, run: UiTestRun) {
        run.root.deleteRecursively()
        DataStoreSignerPreferences.preferencesFile(context, run.preferencesName).delete()
    }

    /** Test hook: forget the configuration of this process. */
    @VisibleForTesting
    internal fun resetForTest() {
        configured = false
        run = null
        isEmulator = ::detectEmulator
        createsFixtures = true
    }

    private fun applyLocale(tag: String) {
        val wanted = LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }

    /**
     * iOS fixtures (:29-43): once the first load finished and only if no wallet exists, "Public fixture 1…N" from the
     * public phrase, passphrase "" for #1 and "Public fixture <n>" for the others, through the real create path.
     */
    private fun startFixtures(context: Context, run: UiTestRun) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, HarnessEntryPoint::class.java)
        val store = entry.signerStore()
        val resources = context.applicationContext.resources
        entry.applicationScope().launch(Dispatchers.Main.immediate) {
            val loaded = store.state.first { !it.isLoading }
            if (loaded.wallets.isNotEmpty()) return@launch
            try {
                for (index in 0 until run.fixtures) {
                    val number = index + 1
                    SecretText.of(FIXTURE_PHRASE).useAndWipe { phrase ->
                        SecretText.of(if (index == 0) "" else "Public fixture $number").useAndWipe { passphrase ->
                            SecretText.of(FIXTURE_PASSWORD).useAndWipe { password ->
                                store.createWallet("Public fixture $number", phrase, passphrase, password)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.toUiText()?.resolve(resources) ?: e.javaClass.simpleName
                store.setError(UiText.Verbatim("UI fixture setup failed: $message"))
            }
        }
    }

    private fun detectEmulator(): Boolean =
        systemProperty("ro.kernel.qemu") == "1" || systemProperty("ro.boot.qemu") == "1" ||
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun isUuid(text: String): Boolean = text.length == 36 && runCatching { UUID.fromString(text) }.isSuccess

    /** `am start --es` gives a String, `--ei` an Int; accept both for numbers. */
    private fun Bundle.number(key: String): Int? = when (@Suppress("DEPRECATION") val value = get(key)) {
        is Int -> value
        is Long -> value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /** `--ez` gives a Boolean, `--es` a String; null when absent. */
    private fun Bundle.flag(key: String): Boolean? = when (@Suppress("DEPRECATION") val value = get(key)) {
        is Boolean -> value
        is String -> value.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }

    private fun Bundle.text(key: String): String? = when (@Suppress("DEPRECATION") val value = get(key)) {
        is String -> value
        null -> null
        else -> value.toString()
    }
}

/** What the harness needs from Hilt before the activity is injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HarnessEntryPoint {
    fun signerStore(): SignerStore

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
