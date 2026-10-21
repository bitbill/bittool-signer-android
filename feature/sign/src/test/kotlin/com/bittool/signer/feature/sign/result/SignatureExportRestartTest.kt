package com.bittool.signer.feature.sign.result

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.feature.sign.SignFixtures
import com.bittool.signer.feature.sign.common.RequestFileReader
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import com.bittool.signer.feature.sign.home.ScanHomeViewModel
import com.bittool.signer.feature.sign.review.RequestReviewViewModel
import com.bittool.signer.feature.sign.tab.ScanTabScreen
import com.bittool.signer.feature.sign.tab.ScanTabViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit A14: "Export as File" opens the system document picker, another app. When Android ends this process while it
 * is open, the activity, the Scan tab and its saved state come back, but the store starts empty (the signature lived in
 * memory only), so the tab shows Scan Home. The picker's answer reaches only a launcher registered again under the
 * same saved key: registered on the result page, it never came back and the new, empty document stayed empty without a
 * word. The Scan tab root registers it now, whatever page it shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1800dp-xhdpi")
class SignatureExportRestartTest {
    @get:Rule
    val compose = createComposeRule()

    private val text = SignFixtures.responseFrames.joinToString("\n")
    private val uri = Uri.parse("content://documents/bittool-signer-signature.txt")
    private val written = mutableListOf<Pair<Uri, String>>()
    private val writer = SignatureFileWriter { uri, value -> written += uri to value }

    /** The activity's result registry: the picker is never started; the test answers for it. */
    private class Registry : ActivityResultRegistry() {
        val launched = mutableListOf<Int>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += requestCode
        }
    }

    /** One process: its store, the Scan tab's ViewModels and the activity's registry. */
    private class Process(val store: FakeSignerStore, val registry: Registry, writer: SignatureFileWriter) {
        val tab = ScanTabViewModel(store, AppRouter())
        val home = ScanHomeViewModel(store, RequestFileReader { error("no file is imported") })
        val review = RequestReviewViewModel(store)
        val result = SignatureResultViewModel(store, writer)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
    }

    private fun record(exportText: String?) = SigningReceipt(
        id = "8f0e2c1a-6b3d-4e5f-9a7b-1c2d3e4f5a6b",
        date = 1_760_000_000_000,
        chain = Chain.Bitcoin,
        title = "Sign Bitcoin transaction",
        requestHash = SignFixtures.REQUEST_HASH,
        resultType = SigningReceipt.RESULT_SIGNATURE,
        responseFrames = exportText?.lines(),
        responseText = exportText,
    )

    /**
     * Opens the picker from the result page of a signed request kept in Activity as [kept], then ends the process while
     * it is open; returns the new process (its store still reading the journal) and the picker's request code.
     */
    private fun exportThenRestart(kept: SigningReceipt): Pair<Process, Int> {
        val signed = FakeSignerStore(
            SignFixtures.walletsState().copy(
                review = SignFixtures.bitcoinSend,
                signatureFrames = SignFixtures.responseFrames,
                signatureText = text,
                signaturePayload = SignFixtures.psbtPayload,
                receipts = listOf(kept),
            ),
        )
        var process = Process(signed, Registry(), writer)
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides process.owner) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    ScanTabScreen(
                        onOpenScanner = {},
                        viewModel = process.tab,
                        homeViewModel = process.home,
                        reviewViewModel = process.review,
                        resultViewModel = process.result,
                    )
                }
            }
        }
        compose.onNodeWithTag(SignTags.SIGNATURE_EXPORT).performClick()
        val requestCode = process.registry.launched.single()

        // The process ends: the activity's saved state (its result registry, the composition's saved values) is all
        // that is left; new ViewModels on a new store that is still reading the journal.
        val activityState = Bundle().also { process.registry.onSaveInstanceState(it) }
        val restarted = FakeSignerStore(SignerState(isLoading = true))
        process = Process(restarted, Registry().also { it.onRestoreInstanceState(activityState) }, writer)
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(SignTags.HOME).assertExists()
        return process to requestCode
    }

    private fun Process.answer(requestCode: Int) {
        registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
        compose.waitForIdle()
    }

    @Test
    fun anExportAnsweredInANewProcessIsWrittenFromTheSignaturesActivityRecord() {
        val kept = record(text)
        val (process, requestCode) = exportThenRestart(kept)
        process.answer(requestCode)
        assertTrue(written.isEmpty(), "written once Activity is read again")
        process.store.setState { it.copy(isLoading = false, receipts = listOf(kept)) }
        compose.waitForIdle()
        assertEquals(listOf(uri to text), written, "the document the person created holds the signature")
        assertEquals(null, process.store.state.value.error)
    }

    @Test
    fun anExportAnsweredInANewProcessWithoutAKeptSignatureSaysTheFileWasNotSaved() {
        val (process, requestCode) = exportThenRestart(record(text))
        process.answer(requestCode)
        // The journal comes back without the signature's text (here: its record had none to keep).
        process.store.setState { it.copy(isLoading = false, receipts = listOf(record(null))) }
        compose.waitForIdle()
        assertTrue(written.isEmpty())
        assertEquals(UiText.Resource(R.string.sign_file_not_saved), process.store.state.value.error, "said in the app's alert")
    }
}
