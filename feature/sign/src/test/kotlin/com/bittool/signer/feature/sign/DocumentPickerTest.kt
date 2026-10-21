package com.bittool.signer.feature.sign

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.sign.common.RequestFileReader
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import com.bittool.signer.feature.sign.home.ScanHomeScreen
import com.bittool.signer.feature.sign.home.ScanHomeViewModel
import com.bittool.signer.feature.sign.result.SignatureResultScreen
import com.bittool.signer.feature.sign.result.SignatureResultViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Import File and Export as File on a device where no app opens the system document picker (some work profiles,
 * stripped builds): the launcher throws `ActivityNotFoundException`. The page says the file could not be read / saved
 * in place, as the Activity Signature page does, instead of the app stopping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1800dp-xhdpi")
class DocumentPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun noDocumentPicker() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
    }

    @Test
    fun importFileWithoutADocumentPickerSaysTheFileCouldNotBeRead() {
        val viewModel = ScanHomeViewModel(FakeSignerStore(SignFixtures.walletsState()), RequestFileReader { error("no file was picked") })
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { ScanHomeScreen(viewModel = viewModel, onOpenScanner = {}) }
        }
        compose.onNodeWithTag(SignTags.FILE).performClick()
        compose.onNodeWithText("The file could not be read.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun exportWithoutADocumentPickerSaysTheFileCouldNotBeSaved() {
        val store = FakeSignerStore(
            SignFixtures.walletsState().copy(
                review = SignFixtures.bitcoinSend,
                signatureFrames = SignFixtures.responseFrames,
                signatureText = SignFixtures.responseFrames.joinToString("\n"),
                signaturePayload = SignFixtures.psbtPayload,
            ),
        )
        val viewModel = SignatureResultViewModel(store, SignatureFileWriter { _, _ -> error("nothing to write to") })
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { SignatureResultScreen(viewModel = viewModel) }
        }
        compose.onNodeWithText("Export as File").performClick()
        compose.onNodeWithText("The file could not be saved.", useUnmergedTree = true).assertExists()
    }
}
