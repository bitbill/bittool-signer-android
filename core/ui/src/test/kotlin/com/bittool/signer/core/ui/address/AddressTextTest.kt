package com.bittool.signer.core.ui.address

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.ui.catalog.CatalogSamples
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS `AddressText`, `HashText`, `CopyableAddress` and `CopyableText` (`Design/AddressText.swift`). */
@RunWith(RobolectricTestRunner::class)
class AddressTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    @Test
    fun firstAndLastGroupsAreEmphasisedAndTheMiddleIsQuieter() {
        val groups = AddressFormatter.groups(CatalogSamples.EVM_ADDRESS)
        assertEquals("0x9858", groups.first())
        val text = groupedAddress(groups, Color.Black)
        assertEquals(groups.joinToString(" "), text.text)
        val spans = text.spanStyles
        assertEquals(groups.size, spans.size)
        assertEquals(FontWeight.SemiBold, spans.first().item.fontWeight)
        assertEquals(FontWeight.SemiBold, spans.last().item.fontWeight)
        val middle = spans[1].item
        assertEquals(FontWeight.Normal, middle.fontWeight)
        assertEquals(0.78f, middle.color.alpha, 0.001f)
    }

    @Test
    fun talkBackReadsTheGroupsOneByOne() {
        compose.setContent { SignerTheme(false, false) { AddressText(CatalogSamples.BITCOIN_ADDRESS) } }
        val spoken = AddressFormatter.groups(CatalogSamples.BITCOIN_ADDRESS).joinToString(", ")
        compose.onNodeWithContentDescription(spoken).assertExists()
    }

    @Test
    fun hashesAreUpperCasedInGroupsOfEight() {
        assertEquals("3F2A91C4 0B77D2E8", hashGroups("3f2a91c40b77d2e8"))
        assertEquals("ABC", hashGroups("abc"))
    }

    @Test
    fun copyingAnAddressShowsACheckMarkForOneAndAHalfSeconds() {
        compose.setContent { SignerTheme(false, false) { Column { CopyableAddress(CatalogSamples.EVM_ADDRESS) } } }
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithTag(COPY_BUTTON_TAG)
        button.assertContentDescriptionEquals("Copy address")
        button.performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        button.assertContentDescriptionEquals("Copied")
        val clip = clipboard.primaryClip!!
        assertEquals(CatalogSamples.EVM_ADDRESS, clip.getItemAt(0).text.toString())
        assertEquals(SignerClipboard.LABEL, clip.description.label)
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        repeat((SignerMotion.COPIED_MILLIS / 100).toInt() + 2) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
        button.assertContentDescriptionEquals("Copy address")
    }

    @Test
    fun copyableTextKeepsItsCheckMark() {
        compose.setContent { SignerTheme(false, false) { CopyableText("support@bittool.com", copyLabel = "Copy address") } }
        val button = compose.onNodeWithTag(COPY_BUTTON_TAG)
        button.assertContentDescriptionEquals("Copy address").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        button.assertContentDescriptionEquals("Copied")
        assertEquals("support@bittool.com", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
