package com.bittool.signer.core.data.reference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * iOS LegalDocumentTests `documentIsBundledAndRendersWithoutLinks` (both documents) and `aWrappedParagraphStaysOneBlock`
 * on the bundled default texts, plus the Third-Party Notices files (gaps.md G8).
 */
@RunWith(RobolectricTestRunner::class)
class LegalDocumentRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Filled in before release; the test only checks that no new ones appear. */
    private val knownPlaceholders = setOf("[Publisher legal name]", "[Governing law jurisdiction]")

    private fun repository(test: TestScope) = AssetLegalDocumentRepository(context, StandardTestDispatcher(test.testScheduler))

    private fun text(document: LegalDocument) = context.assets.open(document.assetPath).use { it.readBytes() }.decodeToString()

    @Test
    fun theTermsAreBundledAndRenderWithoutLinks() = documentIsBundledAndRendersWithoutLinks(LegalDocument.Terms)

    @Test
    fun thePrivacyPolicyIsBundledAndRendersWithoutLinks() = documentIsBundledAndRendersWithoutLinks(LegalDocument.Privacy)

    private fun documentIsBundledAndRendersWithoutLinks(document: LegalDocument) = runTest {
        val text = text(document)
        assertTrue(text.startsWith(document.englishTitle + "\nVersion 1.1 · Effective "))
        val addresses = Regex("https?://[^\\s]+").findAll(text).map { it.value }.toSet()
        assertTrue(addresses.all { it == "https://bittool.com/signer" }, "Only our own contact address may appear: $addresses")
        assertTrue(text.contains("BITTOOL PTE. LTD."), "${document.englishTitle} must name the publisher.")
        // Publisher, address and site each stand alone, so the page shows them on their own lines.
        val blocks = repository(this).blocks(document)
        val contact = blocks.indexOfFirst { it.text == "https://bittool.com/signer" }
        assertTrue(contact >= 3, "${document.englishTitle} must show the site as its own block.")
        assertEquals("support@bittool.com", blocks[contact - 1].text)
        assertEquals("BITTOOL PTE. LTD.", blocks[contact - 2].text)
        assertEquals(LegalBlock.Kind.Heading, blocks[contact - 3].kind)
        assertTrue(blocks[contact - 3].text.endsWith("Contact"))
        assertTrue(blocks.size > 20)
        assertTrue(blocks.count { it.kind == LegalBlock.Kind.Heading } >= 10)
        assertTrue(blocks.all { it.text.isNotEmpty() && !it.text.startsWith("## ") && !it.text.startsWith("- ") })
        // The title belongs to the top bar; the version line leads the page.
        assertTrue(blocks.none { it.text == document.englishTitle })
        assertEquals(LegalBlock.Kind.Version, blocks.first().kind)
        assertTrue(blocks.first().text.startsWith("Version 1.1 "))
        assertEquals(1, blocks.count { it.kind == LegalBlock.Kind.Version })
        val placeholders = Regex("\\[[^\\]]+\\]").findAll(text).map { it.value }.toSet()
        assertTrue(knownPlaceholders.containsAll(placeholders), "Unexpected placeholder: $placeholders")
    }

    @Test
    fun aWrappedParagraphStaysOneBlock() = runTest {
        val blocks = repository(this).blocks(LegalDocument.Privacy)
        val heading = blocks.indexOfFirst { it.kind == LegalBlock.Kind.Heading && it.text.startsWith("7.") }
        assertTrue(heading >= 0)
        val paragraph = blocks[heading + 1]
        assertEquals(LegalBlock.Kind.Paragraph, paragraph.kind)
        assertTrue(paragraph.text.contains("never your recovery phrase"))
    }

    @Test
    fun theNoticesListTheEngineThenTheAndroidAppDependencies() = runTest {
        val sections = repository(this).thirdPartyNotices()
        assertEquals(2, sections.size)
        assertEquals("BitTool Signer JavaScript engine — third-party notices", sections[0].title)
        assertEquals("BitTool Signer for Android — third-party notices", sections[1].title)
        for (section in sections) {
            assertTrue(section.paragraphs.size > 10, section.title)
            assertTrue(section.paragraphs.none { it.isEmpty() || it.startsWith("\n") || it.endsWith("\n") }, section.title)
        }
        val android = sections[1].paragraphs.joinToString("\n\n")
        for (component in listOf("Jetpack Compose", "Hilt", "Kotlin", "kotlinx.coroutines", "kotlinx.serialization", "QuickJS", "ZXing", "CameraX", "Biometric", "DataStore")) {
            assertTrue(android.contains(component), "The Android notices name $component.")
        }
        assertTrue(android.contains("Apache License") && android.contains("Fabrice Bellard"), "Full license texts are reproduced.")
    }

    @Test
    fun aNoticesFileIsSplitOnBlankLinesAfterItsTitle() {
        assertEquals(NoticeSection("Title", listOf("first\nline", "second")), AssetLegalDocumentRepository.section("Title\n\nfirst\nline\n\nsecond\n"))
        assertEquals(NoticeSection("Only a title", emptyList()), AssetLegalDocumentRepository.section("Only a title\n"))
        assertEquals(NoticeSection("CRLF", listOf("a", "b")), AssetLegalDocumentRepository.section("CRLF\r\n\r\na\r\n\r\nb"))
    }
}
