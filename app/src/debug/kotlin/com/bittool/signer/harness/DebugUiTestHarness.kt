package com.bittool.signer.harness

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.common.harness.UiTestRun
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.crypto.vault.SignerFiles
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
import java.io.IOException
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
 * | `bts.uitest.locale=<tag>` | the per-app language (default `en-US`), applied before the first composition and winning over the language an earlier run or the Language page stored; a change relaunches the activity once (API 33+: `LocaleManager` does; below, [recreateForRunLocale]) |
 * | `bts.uitest.allowCapture=true` | `FLAG_SECURE` stays off for this process (screenshot tour) |
 * | `bts.uitest.loseDeviceKeys=true` | deletes the run's wallet device keys before anything is injected: every wallet of the run (launched again with the same run id) then has a provably lost key, for the lost-key removal of backlog B9 (B24) |
 * | `bts.uitest.unreadableJournal=true` | replaces the run's Activity journal (`receipts.bin`) with one no key opens before anything is injected, so Activity shows its unreadable state ("Activity Unavailable"; QA round 2); the journal key is not lost, so later launches of the run keep it (iOS behaviour), only a reset run starts a new journal |
 * | `bts.uitest.loseJournalKey=true` | deletes the run's Activity journal key for good before anything is injected, as removing the screen lock does (QA L1, round 3 activity-keyboard-07): the store proves the loss, starts a fresh journal and Activity shows "Activity Started Again" until "OK"; a run without a journal gets one the lost key sealed. Wins over `unreadableJournal` |
 *
 * While a run is active, `DebugUiTestModule` binds the file-backed [FixtureDeviceKeyStore] (so a cold launch with the
 * same run id opens the same wallets), the file-backed [FixtureJournalCipher] (Activity works on an emulator without a
 * screen lock, QA round 1 sign-01) and the always-failing [FixtureBiometricAuthenticator]. The first configuration
 * of a process wins: extras of a later launch (a `singleTask` relaunch, `onNewIntent`) are ignored, because every
 * singleton is already bound to the first run — the e2e suite force-stops the app before each launch.
 *
 * This class exists only in debug builds; the release check `check<Variant>HasNoHarness` (e.g.
 * `checkApkReleaseHasNoHarness`) fails a release whose R8 output still contains it.
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
    const val EXTRA_UNREADABLE_JOURNAL = "bts.uitest.unreadableJournal"
    const val EXTRA_LOSE_JOURNAL_KEY = "bts.uitest.loseJournalKey"

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

    /** Below API 33: whether the activity of this process's configuration still has to be checked for its language. */
    private var localeCheckPending = false

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
        if (extras.flag(EXTRA_UNREADABLE_JOURNAL) == true) {
            try {
                FixtureJournalCipher.makeUnreadable(File(parsed.root, SignerFiles.RECEIPTS_FILE))
                Log.i(TAG) { "UI-test run: Activity journal made unreadable" }
            } catch (e: IOException) {
                Log.w(TAG) { "UI-test run: the Activity journal could not be replaced (${e.javaClass.simpleName})" }
            }
        }
        if (extras.flag(EXTRA_LOSE_JOURNAL_KEY) == true) {
            try {
                FixtureJournalCipher.loseKey(
                    File(parsed.root, "${FixtureDeviceKeyStore.DIRECTORY}/${FixtureJournalCipher.KEY_FILE}"),
                    File(parsed.root, SignerFiles.RECEIPTS_FILE),
                )
                Log.i(TAG) { "UI-test run: Activity journal key lost" }
            } catch (e: IOException) {
                Log.w(TAG) { "UI-test run: the Activity journal key could not be lost (${e.javaClass.simpleName})" }
            }
        }
        run = parsed
        applyLocale(context, parsed.localeTag)
        localeCheckPending = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
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

    /**
     * Below API 33 (QA round 3 activity-keyboard-06): recreates [activity] once when it was attached in another language
     * than the run's, and returns whether it did. Call from `MainActivity.onCreate` **before** `super.onCreate`, after
     * [configure].
     *
     * With `autoStoreLocales`, AppCompat applies the language it stored (an earlier run's `bts.uitest.locale`, or the
     * Language page's choice) to the activity's base context in `attachBaseContext`, before the launch extras can be
     * read. The run's `setApplicationLocales` in [configure] reaches that activity only through `super.onCreate`'s
     * in-place resources update, and AppCompat stores a language requested while no activity is live only when the
     * next activity is attached. So
     * the first frame showed the run's language, but the first configuration change the activity handles itself (a
     * rotation, a night-mode switch) rebuilt its resources from the base context, back in the stored language, and the
     * next launch was attached in it again. The new instance is attached in the run's language and AppCompat stores it,
     * so a later launch of the same language needs no recreation. Before `super.onCreate` the resources still show what
     * the base context was attached with (afterwards the in-place update hides the difference).
     *
     * Only the activity of this process's configuration is checked, once: a language chosen later on the Language page
     * recreates the activity itself. API 33+ needs nothing: setting `LocaleManager` in [configure] relaunches it.
     */
    @MainThread
    fun recreateForRunLocale(activity: Activity): Boolean {
        if (!localeCheckPending) return false
        localeCheckPending = false
        val run = run ?: return false
        val attached = ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.stripExtensions()
        val wanted = LocaleListCompat.forLanguageTags(run.localeTag)[0]?.stripExtensions()
        if (attached == wanted) return false
        Log.i(TAG) { "UI-test run: recreating the activity in the run's language" }
        // Posted: during onCreate the activity is not registered with ActivityThread yet, and API 31 drops the call.
        Handler(Looper.getMainLooper()).post {
            if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
        }
        return true
    }

    /** Test hook: forget the configuration of this process. */
    @VisibleForTesting
    internal fun resetForTest() {
        configured = false
        localeCheckPending = false
        run = null
        isEmulator = ::detectEmulator
        createsFixtures = true
    }

    /**
     * The run's per-app language, before `super.onCreate`. On API 33+ AppCompat reaches Android's own list only through
     * a live activity delegate, which `super.onCreate` registers (final audit A11): it would read an empty list and
     * set nothing, so the framework `LocaleManager` is used directly there (a change makes the system relaunch the
     * activity once). Below API 33 AppCompat keeps the list itself and the delegate's onCreate applies it in place.
     * Already applied means the same locales apart from Unicode extensions: `MainActivity` turns `ar` into
     * `ar-u-nu-latn`, and asking for `ar` again on every launch would only relaunch the activity again. Below API 33 the
     * activity was attached before this runs, in the language AppCompat stored: [recreateForRunLocale] then makes the
     * run's language hold past the first configuration change (QA round 3 activity-keyboard-06).
     */
    private fun applyLocale(context: Context, tag: String) {
        val wanted = LocaleListCompat.forLanguageTags(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            if (!sameLocales(LocaleListCompat.wrap(manager.applicationLocales), wanted)) {
                manager.applicationLocales = LocaleList.forLanguageTags(wanted.toLanguageTags())
            }
        } else if (!sameLocales(AppCompatDelegate.getApplicationLocales(), wanted)) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }

    private fun sameLocales(current: LocaleListCompat, wanted: LocaleListCompat): Boolean =
        current.size() == wanted.size() &&
            (0 until wanted.size()).all { current[it]?.stripExtensions() == wanted[it]?.stripExtensions() }

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
