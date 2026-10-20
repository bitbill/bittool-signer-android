package com.bittool.signer.legal

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.bittool.signer.BuildConfig
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android Terms of Use and Privacy Policy of both distributions (activity-settings.md §10, DECISIONS.md §9.5;
 * `app/legal/LEGAL_REVIEW.md`). Port of iOS `LegalDocumentTests.privacyPolicyDescribesWhatTheAppActuallyDoes`
 * (BitToolSignerTests/LegalDocumentTests.swift:42-48) with the claims adapted to Android, plus the structure the
 * screens and the UI tests rely on, per flavor.
 */
@RunWith(RobolectricTestRunner::class)
class LegalTextsTest {
    private val flavors = listOf("apk", "play")

    private fun text(flavor: String, document: LegalDocument) = File("src/$flavor/assets/${document.assetPath}").readText()

    private fun blocks(flavor: String, document: LegalDocument) = LegalDocumentParser.parse(text(flavor, document), document.englishTitle)

    @Test
    fun privacyPolicyDescribesWhatTheAppActuallyDoes() {
        for (flavor in flavors) {
            val text = text(flavor, LegalDocument.Privacy)
            for (claim in listOf("collects nothing", "no networking code", "Internet permission", "Clear Activity", "two minutes", "Android Keystore", "excluded from cloud backups")) {
                assertTrue(text.contains(claim), "$flavor: the policy should still say: $claim")
            }
        }
        assertTrue(text("play", LegalDocument.Privacy).contains("## 10. Google Play"))
        assertTrue(text("apk", LegalDocument.Privacy).contains("## 10. Direct download"))
    }

    @Test
    fun noIosOnlyWordingIsLeft() {
        val ios = listOf("iOS", "iPhone", "iPad", "Apple", "App Store", "iCloud", "Keychain", "Face ID", "Touch ID", "Secure Enclave", "Files app", "Handoff", "jailbr")
        for (flavor in flavors) for (document in LegalDocument.entries) {
            val text = text(flavor, document)
            for (word in ios) assertFalse(text.contains(word), "$flavor ${document.name} still says \"$word\"")
        }
    }

    @Test
    fun theOnlyAddressIsTheProductPage() {
        for (flavor in flavors) for (document in LegalDocument.entries) {
            val urls = Regex("""https?://\S+""").findAll(text(flavor, document)).map { it.value.trimEnd('.', ',', ')') }.toSet()
            assertEquals(setOf("https://bittool.com/signer"), urls, "$flavor ${document.name}")
        }
    }

    @Test
    fun bothDocumentsKeepTheIosStructure() {
        for (flavor in flavors) for (document in LegalDocument.entries) {
            val blocks = blocks(flavor, document)
            assertEquals(LegalBlock.Kind.Version, blocks.first().kind, "$flavor ${document.name}")
            assertTrue(blocks.first().text.startsWith("Version 1.1"), "$flavor ${document.name}: the UI tests look for Version 1.1")
            // The contact block ends both documents: three separate blocks after the Contact heading.
            val contact = blocks.indexOfLast { it.kind == LegalBlock.Kind.Heading && it.text.endsWith("Contact") }
            assertEquals(
                listOf("BITTOOL PTE. LTD.", "support@bittool.com", "https://bittool.com/signer"),
                blocks.drop(contact + 1).take(3).map { it.text },
                "$flavor ${document.name}",
            )
        }
        val terms = blocks("apk", LegalDocument.Terms).filter { it.kind == LegalBlock.Kind.Heading }.map { it.text }
        assertEquals(16, terms.size)
        assertTrue(terms.last() == "16. Contact")
    }

    /** iOS `aWrappedParagraphStaysOneBlock` on the Android texts: section 7's first paragraph is one block. */
    @Test
    fun aWrappedParagraphStaysOneBlock() {
        for (flavor in flavors) {
            val blocks = blocks(flavor, LegalDocument.Privacy)
            val heading = blocks.indexOfFirst { it.kind == LegalBlock.Kind.Heading && it.text.startsWith("7.") }
            val paragraph = blocks[heading + 1]
            assertEquals(LegalBlock.Kind.Paragraph, paragraph.kind)
            assertTrue(paragraph.text.contains("never your recovery phrase"))
        }
    }

    /** The flavors differ only in their distribution clause (Terms §15, Privacy §10); everything else is shared. */
    @Test
    fun theFlavorsDifferOnlyInTheDistributionClause() {
        for (document in LegalDocument.entries) {
            val clause = if (document == LegalDocument.Terms) "15." else "10."
            fun shared(flavor: String) = sections(text(flavor, document)).filterKeys { !it.startsWith(clause) }
            assertEquals(shared("apk"), shared("play"), document.name)
        }
        assertTrue(text("apk", LegalDocument.Terms).contains("apksigner verify --print-certs"), "the APK verification steps")
        assertTrue(text("play", LegalDocument.Terms).contains("## 15. Google Play"))
        assertFalse(text("play", LegalDocument.Terms).contains("apksigner"))
    }

    /** The merged assets of this variant are the flavor's texts (app assets override `:core:data`'s iOS copies). */
    @Test
    fun theBuiltVariantBundlesItsFlavorsTexts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (document in LegalDocument.entries) {
            val bundled = context.assets.open(document.assetPath).bufferedReader().use { it.readText() }
            assertEquals(text(BuildConfig.DISTRIBUTION, document), bundled, document.name)
        }
    }

    private fun sections(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        var key = "head"
        val current = StringBuilder()
        text.lines().forEach { line ->
            if (line.startsWith("## ")) {
                map[key] = current.toString()
                current.clear()
                key = line.removePrefix("## ")
            } else {
                current.appendLine(line)
            }
        }
        map[key] = current.toString()
        return map
    }
}
