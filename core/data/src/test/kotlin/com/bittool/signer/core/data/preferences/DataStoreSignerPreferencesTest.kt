package com.bittool.signer.core.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.common.harness.UiTestRun
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.model.Appearance
import java.io.File
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** app-shell.md §2.2 / §15.2 and gaps.md G3 (overrides before the first publish), over a real DataStore file. */
@RunWith(RobolectricTestRunner::class)
class DataStoreSignerPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        File(context.filesDir, "datastore").deleteRecursively()
    }

    private fun preferences(run: UiTestRun? = null): DataStoreSignerPreferences {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val environment = run?.let { active -> object : UiTestEnvironment { override val run: UiTestRun = active } }
        return DataStoreSignerPreferences(context, Dispatchers.IO, scope, Optional.ofNullable(environment))
    }

    private suspend fun relaunch() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    private fun run(appLock: Boolean? = null, appearance: String? = null, fixtures: Int = 0) = UiTestRun(
        id = UUID.randomUUID().toString(),
        root = File(context.cacheDir, "ui-tests"),
        preferencesName = "ui-tests-" + UUID.randomUUID(),
        fixtures = fixtures,
        appLock = appLock,
        appearance = appearance,
        localeTag = "en-US",
        allowCapture = false,
    )

    @Test
    fun nothingIsReadUntilSomebodyAsksThenTheDefaultsApply() = runBlocking {
        val preferences = preferences()
        assertNull(preferences.values.value, "App Lock treats null as locked: fail closed while loading.")
        val values = preferences.load()
        assertEquals(PreferenceValues(Appearance.System, appLockEnabled = false, onboardingVersionCompleted = 0), values)
        assertFalse(values.introductionCompleted)
        assertEquals(values, preferences.values.value)
    }

    @Test
    fun aSubscriberStartsTheFirstRead() = runBlocking {
        val preferences = preferences()
        val first = withTimeout(10_000) { preferences.values.filterNotNull().first() }
        assertEquals(PreferenceValues(), first)
    }

    @Test
    fun writtenValuesArePublishedAtOnceAndSurviveARelaunch() = runBlocking {
        val preferences = preferences()
        preferences.setAppearance(Appearance.Dark)
        assertEquals(Appearance.Dark, preferences.values.value?.appearance)
        preferences.setAppLockEnabled(true)
        preferences.setOnboardingVersionCompleted(1)
        assertEquals(PreferenceValues(Appearance.Dark, appLockEnabled = true, onboardingVersionCompleted = 1), preferences.values.value)
        assertTrue(preferences.values.value!!.introductionCompleted)
        relaunch()
        assertEquals(PreferenceValues(Appearance.Dark, appLockEnabled = true, onboardingVersionCompleted = 1), preferences().load())
        assertTrue(DataStoreSignerPreferences.preferencesFile(context, DataStoreSignerPreferences.FILE_NAME).exists())
    }

    @Test
    fun uiTestOverridesAreInPlaceBeforeTheFirstValuesArePublished() = runBlocking {
        val active = run(appLock = true, appearance = "dark", fixtures = 2)
        val preferences = preferences(active)
        val first = withTimeout(10_000) { preferences.values.filterNotNull().first() }
        assertEquals(PreferenceValues(Appearance.Dark, appLockEnabled = true, onboardingVersionCompleted = 1), first)
        assertTrue(DataStoreSignerPreferences.preferencesFile(context, active.preferencesName).exists())
        assertFalse(
            DataStoreSignerPreferences.preferencesFile(context, DataStoreSignerPreferences.FILE_NAME).exists(),
            "A UI-test run never touches the production file.",
        )
    }

    @Test
    fun onlyTheRunsExplicitValuesOverride() = runBlocking {
        val first = preferences(run(appLock = null, appearance = "sepia", fixtures = 0)).load()
        assertEquals(PreferenceValues(), first)
        relaunch()
        assertEquals(Appearance.Light, preferences(run(appearance = "light")).load().appearance)
    }

    @Test
    fun aCorruptFileReadsAsTheDefaults() = runBlocking {
        val file = DataStoreSignerPreferences.preferencesFile(context, DataStoreSignerPreferences.FILE_NAME)
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0x7F, 0x01, 0x02, 0x03))
        assertEquals(PreferenceValues(), preferences().load())
    }
}
