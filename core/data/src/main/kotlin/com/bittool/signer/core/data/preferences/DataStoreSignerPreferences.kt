package com.bittool.signer.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.common.harness.UiTestRun
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.ONBOARDING_VERSION
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SignerPreferences] in one DataStore Preferences file (app-shell.md §2.2, §15.2; activity-settings.md §13), with the
 * iOS UserDefaults key names and values: `appearance` (`system|light|dark`), `appLockEnabled.v1`,
 * `onboardingVersionCompleted`. Nothing secret, never an "unlocked" flag.
 *
 * Lifecycle:
 * - Nothing is opened at construction (gaps.md G3: the debug harness configures the run after `Application.onCreate`).
 *   The file is chosen and read by the **first** [load] — or the first subscriber of [values] — so the active
 *   [UiTestRun] is known by then.
 * - In a UI-test run the file is `UiTestRun.preferencesName` and the run's overrides are written inside that first load,
 *   before [values] is published: App Lock (resolving until [values] is non-null) never sees the pre-override value.
 * - A corrupt file is replaced by an empty one and a first read that fails with an I/O error reads as the defaults (like
 *   Ownbit `AppSettings`); iOS UserDefaults have no failure mode of their own. Later read errors keep the last values.
 *
 * Setters await the write and publish the written values before returning.
 */
@Singleton
class DataStoreSignerPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
    private val uiTests: Optional<UiTestEnvironment>,
) : SignerPreferences {

    private val _values = MutableStateFlow<PreferenceValues?>(null)
    override val values: StateFlow<PreferenceValues?> = _values.asStateFlow()

    private val loading = Mutex()
    private var dataStore: DataStore<Preferences>? = null

    init {
        // A subscriber (App Lock, the theme) starts the first read; reading `values.value` alone does not. A failure is
        // logged and leaves `values` null: App Lock stays resolving (fail closed) instead of the process crashing.
        scope.launch {
            _values.subscriptionCount.first { it > 0 }
            runCatchingCancellable { load() }.onFailure { Log.e(TAG, it) { "The preferences could not be read" } }
        }
    }

    override suspend fun load(): PreferenceValues = _values.value ?: loading.withLock { _values.value ?: firstLoad() }

    override suspend fun setAppearance(appearance: Appearance) = write { it[APPEARANCE] = appearance.wire }

    override suspend fun setAppLockEnabled(enabled: Boolean) = write { it[APP_LOCK_ENABLED] = enabled }

    override suspend fun setOnboardingVersionCompleted(version: Int) = write { it[ONBOARDING_VERSION_COMPLETED] = version }

    /** Called under [loading]. The DataStore is created once, even when a first read has to be retried. */
    private suspend fun firstLoad(): PreferenceValues {
        val run = uiTests.getOrNull()?.run
        val store = dataStore ?: PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            // A child of the process scope (never cancelled in the app; a test ends it to open the file again).
            scope = CoroutineScope(scope.coroutineContext + io + SupervisorJob(scope.coroutineContext[Job])),
            produceFile = { preferencesFile(context, run?.preferencesName ?: FILE_NAME) },
        ).also { dataStore = it }
        var preferences = try {
            store.data.first()
        } catch (e: IOException) {
            emptyPreferences()
        }
        if (run != null) {
            val overridden = preferences.toMutablePreferences().apply { applyOverrides(run) }
            if (overridden != preferences) {
                // Written before anything is published; an unwritable file still applies the run's values in memory.
                preferences = try {
                    store.edit { it.applyOverrides(run) }
                } catch (e: IOException) {
                    overridden
                }
            }
        }
        val first = preferences.toValues()
        _values.value = first
        // Keep following the file (DataStore emits after every edit; StateFlow drops equal values). A later read error
        // keeps the last published values, so a transient failure never switches App Lock off mid-session.
        scope.launch {
            store.data
                .catch { Log.w(TAG, it) { "The preferences stopped updating" } }
                .collect { _values.value = it.toValues() }
        }
        return first
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        load()
        val store = checkNotNull(dataStore)
        _values.value = store.edit(block).toValues()
    }

    companion object {
        /** The production preferences file (`files/datastore/bittool_signer_preferences.preferences_pb`). */
        const val FILE_NAME = "bittool_signer_preferences"
        private const val TAG = "Preferences"

        val APPEARANCE = stringPreferencesKey("appearance")
        val APP_LOCK_ENABLED = booleanPreferencesKey("appLockEnabled.v1")
        val ONBOARDING_VERSION_COMPLETED = intPreferencesKey("onboardingVersionCompleted")

        /** Where the preferences named [name] live; the debug harness deletes a run's file with this (gaps.md G3). */
        fun preferencesFile(context: Context, name: String): File = context.preferencesDataStoreFile(name)

        /** iOS `SimulatorUITestContext` (`:25-43`): App Lock on, a forced appearance, onboarding done with fixtures. */
        internal fun MutablePreferences.applyOverrides(run: UiTestRun) {
            run.appLock?.let { this[APP_LOCK_ENABLED] = it }
            Appearance.entries.firstOrNull { it != Appearance.System && it.wire == run.appearance }?.let { this[APPEARANCE] = it.wire }
            if (run.fixtures > 0) this[ONBOARDING_VERSION_COMPLETED] = ONBOARDING_VERSION
        }

        internal fun Preferences.toValues() = PreferenceValues(
            appearance = Appearance.fromWire(this[APPEARANCE]),
            appLockEnabled = this[APP_LOCK_ENABLED] ?: false,
            onboardingVersionCompleted = this[ONBOARDING_VERSION_COMPLETED] ?: 0,
        )
    }
}
