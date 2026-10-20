package com.bittool.signer.feature.settings.about

import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.data.testing.FakeLegalDocumentRepository
import com.bittool.signer.core.data.testing.FakeReferenceDataRepository
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import com.bittool.signer.feature.settings.MainDispatcherRule
import com.bittool.signer.feature.settings.SettingsTestData
import com.bittool.signer.feature.settings.compatibility.CompatibilityTab
import com.bittool.signer.feature.settings.compatibility.CompatibilityViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Open Source, the legal pages, Third-Party Notices and Compatibility (activity-settings.md §6, §9, §10). */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun thisBuildIsMeasuredOnTheDevice() = runTest {
        val viewModel = OpenSourceViewModel(FakeReferenceDataRepository(), SettingsTestData.buildInfo)
        val before = viewModel.uiState.value
        assertEquals(Measured.Reading, before.engineDigest)
        assertEquals("1.0.0 (1)", before.version)
        assertEquals("https://github.com/bitbill/bittool-signer-android", before.repository)
        assertEquals(Distribution.DirectApk, before.distribution)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(Measured.Value("6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077"), state.engineDigest)
        assertEquals(Measured.Value(1_181_026), state.engineBytes)
        assertEquals(Measured.Value("2026-09-22.1"), state.registryVersion)
    }

    @Test
    fun whatCannotBeReadSaysUnavailable() = runTest {
        // iOS leaves the engine size at "Reading…" forever when signer.js cannot be read; Android says Unavailable.
        val viewModel = OpenSourceViewModel(
            FakeReferenceDataRepository(version = null, digest = null),
            SettingsTestData.buildInfo.copy(signingCertificateSha256 = null, distribution = Distribution.GooglePlay),
        )
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(Measured.Unavailable, state.engineDigest)
        assertEquals(Measured.Unavailable, state.engineBytes)
        assertEquals(Measured.Unavailable, state.registryVersion)
        assertNull(state.signingCertificate)
        assertEquals(Distribution.GooglePlay, state.distribution)
    }

    @Test
    fun aLegalPageReadsItsDocument() = runTest {
        val blocks = FakeLegalDocumentRepository.parsed(LegalDocument.Privacy, "Privacy Policy\nVersion 1.1 · Effective 16 October 2026\n\n## In short\nBitTool Signer collects nothing.")
        val repository = FakeLegalDocumentRepository(documents = mapOf(LegalDocument.Privacy to blocks))
        val viewModel = LegalDocumentViewModel(SavedStateHandle(mapOf("document" to LegalDocument.Privacy.name)), repository)
        assertNull(viewModel.uiState.value.blocks)
        advanceUntilIdle()
        assertEquals(LegalDocument.Privacy, viewModel.uiState.value.document)
        assertEquals(blocks, viewModel.uiState.value.blocks)
    }

    @Test
    fun aMissingDocumentIsOneMissingParagraph() = runTest {
        val viewModel = LegalDocumentViewModel(SavedStateHandle(mapOf("document" to "Terms")), FakeLegalDocumentRepository())
        advanceUntilIdle()
        assertEquals(listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING)), viewModel.uiState.value.blocks)
    }

    @Test
    fun noticesComeInFileOrder() = runTest {
        val sections = listOf(
            NoticeSection("BitTool Signer JavaScript engine — third-party notices", listOf("@noble/curves 1.9.7 MIT")),
            NoticeSection("BitTool Signer for Android — third-party notices", listOf("AndroidX Apache-2.0")),
        )
        val viewModel = ThirdPartyNoticesViewModel(FakeLegalDocumentRepository(notices = sections))
        advanceUntilIdle()
        assertEquals(sections, viewModel.uiState.value.sections)

        val missing = ThirdPartyNoticesViewModel(FakeLegalDocumentRepository())
        advanceUntilIdle()
        assertEquals(listOf(LegalDocumentRepository.NOTICES_MISSING), missing.uiState.value.sections!!.single().paragraphs)
    }

    @Test
    fun compatibilityStartsOnFormatsAndKeepsTheSegment() = runTest {
        // CompatibleWalletsTests: per-chain lists unique with signing first; Wallet Level is BitTool Wallet only.
        val handle = SavedStateHandle()
        val viewModel = CompatibilityViewModel(handle)
        assertEquals(CompatibilityTab.Formats, viewModel.tab.value)
        viewModel.onTabSelected(CompatibilityTab.Wallets)
        assertEquals(CompatibilityTab.Wallets, CompatibilityViewModel(handle).tab.value)

        val bitcoin = viewModel.chains.first()
        assertEquals(listOf("Sparrow", "BlueWallet", "Nunchuk", "Bitcoin Keeper", "BULL Bitcoin", "Specter", "Bitcoin Safe", "ZEUS"), bitcoin.signers.map { it.name })
        assertEquals(listOf("Electrum"), bitcoin.watchers.map { it.name })
        val tron = viewModel.chains.first { it.chain == com.bittool.signer.core.model.Chain.Tron }
        assertTrue(tron.signers.isEmpty())
        assertEquals(listOf("BitTool Wallet"), viewModel.walletLevel.map { it.name })
        assertEquals(24, viewModel.directory.size)
        assertTrue(viewModel.directory.none { it.name.contains("Ownbit") })
    }
}
