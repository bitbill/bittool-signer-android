package com.bittool.signer.feature.onboarding

import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeLegalDocumentRepository
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Welcome's legal sheet (iOS WelcomeView.swift:54: "Terms of Use" / "Privacy Policy" open the bundled texts). */
class WelcomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val terms = FakeLegalDocumentRepository.parsed(
        LegalDocument.Terms,
        "Terms of Use\nVersion 1.1 · Effective 16 October 2026\n\n## 1. This agreement\nThese terms are an agreement.",
    )

    @Test
    fun theSheetShowsTheChosenDocumentOnceItIsRead() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(documents = mapOf(LegalDocument.Terms to terms)), InMemoryDeviceKeyStore())
        assertNull(viewModel.legalSheet.value)

        viewModel.showLegal(LegalDocument.Terms)
        assertEquals(LegalSheet(LegalDocument.Terms, blocks = null), viewModel.legalSheet.value)
        advanceUntilIdle()
        assertEquals(LegalSheet(LegalDocument.Terms, terms), viewModel.legalSheet.value)
        assertEquals(LegalBlock.Kind.Version, viewModel.legalSheet.value?.blocks?.first()?.kind)
    }

    @Test
    fun aMissingDocumentShowsTheMissingParagraph() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(), InMemoryDeviceKeyStore())
        viewModel.showLegal(LegalDocument.Privacy)
        advanceUntilIdle()
        assertEquals(listOf(LegalDocumentParser.MISSING), viewModel.legalSheet.value?.blocks?.map { it.text })
    }

    @Test
    fun doneClosesTheSheetAndALateReadDoesNotReopenIt() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(documents = mapOf(LegalDocument.Terms to terms)), InMemoryDeviceKeyStore())
        viewModel.showLegal(LegalDocument.Terms)
        viewModel.dismissLegal()
        advanceUntilIdle()
        assertNull(viewModel.legalSheet.value)
    }

    @Test
    fun switchingDocumentsShowsOnlyTheLatest() = runTest(dispatcher) {
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(documents = mapOf(LegalDocument.Terms to terms)), InMemoryDeviceKeyStore())
        viewModel.showLegal(LegalDocument.Terms)
        viewModel.showLegal(LegalDocument.Privacy)
        advanceUntilIdle()
        assertEquals(LegalDocument.Privacy, viewModel.legalSheet.value?.document)
        assertEquals(listOf(LegalDocumentParser.MISSING), viewModel.legalSheet.value?.blocks?.map { it.text })
    }

    /** QA wallets-04: without a screen lock, Create / Import ask for one before the introduction and before any typing. */
    @Test
    fun setupStartsOnlyOnADeviceWithAScreenLock() = runTest(dispatcher) {
        val keys = InMemoryDeviceKeyStore(deviceSecure = false)
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(), keys)
        assertFalse(viewModel.lockRequired.value)

        assertFalse(viewModel.mayStartSetup())
        assertTrue(viewModel.lockRequired.value)
        viewModel.dismissLockRequired()
        assertFalse(viewModel.lockRequired.value)

        // A lock set in Settings meanwhile counts at the next tap.
        keys.deviceSecure = true
        assertTrue(viewModel.mayStartSetup())
        assertFalse(viewModel.lockRequired.value)
    }
}
