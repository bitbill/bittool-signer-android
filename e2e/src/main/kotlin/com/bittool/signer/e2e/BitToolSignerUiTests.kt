package com.bittool.signer.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.bittool.signer.e2e.support.Fixtures.AMBIGUOUS_PHRASE
import com.bittool.signer.e2e.support.Fixtures.APPROVE_KNOWN_SPENDER_REQUEST
import com.bittool.signer.e2e.support.Fixtures.APPROVE_UNLISTED_SPENDER_REQUEST
import com.bittool.signer.e2e.support.Fixtures.BACKUP_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.BIOMETRIC_FAILURE
import com.bittool.signer.e2e.support.Fixtures.FINGERPRINT
import com.bittool.signer.e2e.support.Fixtures.FIXTURE_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.MISMATCH_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.PUBLIC_RECOVERY_PHRASE
import com.bittool.signer.e2e.support.Fixtures.PUBLIC_SIGNING_REQUEST
import com.bittool.signer.e2e.support.Fixtures.RECOVERED_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.SPANISH_PHRASE
import com.bittool.signer.e2e.support.Fixtures.UPDATED_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.WRONG_PASSWORD
import com.bittool.signer.e2e.support.SignerTestCase
import com.bittool.signer.e2e.support.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end flows against isolated emulator fixtures (never production vaults): the port of the iOS
 * `BitToolSignerUITests.swift` (gaps.md G1.1–G1.18), test for test and assertion for assertion.
 *
 * Restore uses a public, unfunded BIP39 test vector. Creation uses fresh random phrases that are read into local memory
 * only: never log them, put them in failure messages or capture screenshots while words or word choices are visible
 * (failure descriptions redact them). iOS `attach(...)` screenshots are not taken: the suite runs with the production
 * window policy (FLAG_SECURE), where captures are black; the screenshot tour covers those screens.
 */
@RunWith(AndroidJUnit4::class)
class BitToolSignerUiTests : SignerTestCase() {

    // MARK: - Welcome and setup

    /** G1.1 (UIT:23-56). */
    @Test
    fun testOnboardingKeepsImportIntentAndIsShownOnce() {
        app.launch()
        assertFalse("No tabs before the first wallet exists.", tabBarExists())
        // The welcome links open the bundled documents in a sheet, not a browser.
        ui.tap(ui.tag("welcome.privacy"))
        ui.waitFor(ui.title("Privacy Policy"), 5_000)
        ui.waitFor(ui.containing("collects nothing"), 5_000)
        ui.click(ui.tag("legal.done"))
        ui.waitFor(ui.tag("welcome.import"), 5_000)
        ui.waitUntil(5_000) { ui.isHittable(ui.tag("welcome.import")) }
        ui.click(ui.tag("welcome.import"))
        ui.waitFor(ui.labeled("Offline by Design"), 5_000)
        assertNotExists(ui.labeled("Skip"), "Safety guidance has no Skip.")
        for (page in 1..3) {
            ui.waitFor(ui.labeled("Page $page of 3"), 3_000)
            ui.click(ui.tag("onboarding.next"))
        }
        assertStep(1, 2)
        assertExists(ui.title("Import Wallet"), "The Import choice must survive the introduction.")

        val input = ui.tag("recovery.input.visible")
        ui.setText(input, "abandon abandon abandon")
        ui.waitUntil(3_000) { ui.label(ui.tag("recovery.status")).contains("3 words") }
        assertLabelContains(ui.tag("recovery.status"), "3 words")
        ui.tap(ui.tag("setup.import"))
        ui.waitFor(ui.containing("Invalid recovery phrase"), 10_000)

        ui.click(ui.tag("setup.cancel"))
        val discard = ui.labeled("Discard")
        ui.waitFor(discard, 3_000, "Typed words must not be dropped without confirmation.")
        ui.click(discard)
        ui.waitFor(ui.tag("welcome.create"), 5_000)
        ui.waitUntil(5_000) { ui.isHittable(ui.tag("welcome.create")) }
        ui.click(ui.tag("welcome.create"))
        assertStep(1, 4)
        assertNotExists(ui.labeled("Offline by Design"), "The introduction is shown once.")
    }

    /** G1.2 (UIT:59-85). */
    @Test
    fun testImportSealsWalletAndOpensWallets() {
        app.launch()
        openSetup(Setup.Restore)
        val input = ui.tag("recovery.input.visible")
        ui.waitFor(input, 5_000)
        ui.setText(input, PUBLIC_RECOVERY_PHRASE)
        assertLabelContains(ui.tag("recovery.status"), "12 words")
        ui.tap(ui.tag("setup.import"))

        assertStep(2, 2)
        val finish = ui.tag("setup.finish")
        ui.scrollToExistence(finish)
        assertDisabled(finish, "Finish waits for matching passwords.")
        ui.type(FIXTURE_PASSWORD, into = ui.tag("Wallet password"))
        ui.type(MISMATCH_PASSWORD, into = ui.tag("Confirm password"))
        ui.scrollToExistence(finish)
        assertDisabled(finish, "Mismatched passwords must not seal the wallet.")
        ui.clear(ui.tag("Confirm password"))
        ui.type(FIXTURE_PASSWORD, into = ui.tag("Confirm password"))
        ui.tap(finish)

        ui.waitFor(ui.labeled("Wallet Imported"), 60_000)
        ui.tap(ui.tag("setup.done"))
        ui.waitFor(firstCard, 30_000)
        assertTrue("The imported wallet keeps its default name.", ui.label(firstCard).contains("My Wallet"))
        assertExists(ui.tab("Scan"), "The tabs appear with the first wallet.")
        assertNotExists(ui.tag("welcome.create"), "Welcome is gone.")
    }

    /** G1.3 (UIT:88-184). */
    @Test
    fun testCreateRequiresRevealBackupCheckAndPassword() {
        app.launch()
        openSetup(Setup.Create)
        assertStep(1, 4)
        ui.click(ui.segment("setup.wordCount", "12 words"))
        ui.tap(ui.tag("setup.create"))

        assertStep(2, 4)
        val reveal = ui.tag("recovery.reveal")
        val firstWord = ui.tag("recovery.word.0")
        val written = ui.tag("backup.continue")
        ui.waitFor(reveal, 10_000)
        assertNotExists(firstWord, "A new phrase starts concealed.")
        ui.scrollToExistence(written)
        assertDisabled(written, "The words must be revealed before they can be written down.")

        val windows = appWindowCount()
        val words = revealWords()
        assertEquals("Tap to Reveal shows the words directly, without a confirmation.", windows, appWindowCount())
        ui.scrollToExistence(written)
        assertEnabled(written, "Revealing the words lets them be written down.")
        ui.tap(ui.tag("recovery.hide"))
        ui.waitFor(reveal, 3_000)
        assertNotExists(firstWord, "Hiding conceals the words again.")

        app.backgroundAndReactivate()
        assertStep(2, 4)
        assertNotExists(firstWord, "Returning from background must require fresh consent.")
        assertTrue("Suspending must conceal the draft, not replace it.", revealWords() == words)

        ui.tap(written)
        assertStep(3, 4)
        assertNotExists(firstWord, "The check never shows the phrase.")
        val confirm = ui.tag("verification.continue")
        ui.scrollToExistence(confirm)
        assertDisabled(confirm, "The check starts incomplete.")

        val blanks = blankPositions()
        val chipOrder = chipLabels()
        assertEquals("A 12-word phrase blanks four positions.", 4, blanks.size)
        assertTrue("The chips are exactly the missing words.", chipOrder.sorted() == blanks.map { words[it] }.sorted())
        assertEquals("The first blank is the one to fill.", "Empty, next to fill", firstSlotState(blanks[0]))

        // Words in the wrong order: a clear error, and the same blanks to fill again.
        fill(blanks, words, swapped = true)
        assertEnabled(confirm, "A full board can be checked.")
        ui.tap(confirm)
        ui.waitFor(ui.containing("Some words don"), 3_000)
        assertEquals("A wrong answer keeps the same check.", blanks, blankPositions())
        assertTrue("A wrong answer keeps the chip order.", chipLabels() == chipOrder)
        assertBlanksEmpty(blanks)
        ui.scrollToExistence(confirm)
        assertDisabled(confirm, "The check is incomplete again.")

        // Tapping a filled blank takes its word out again.
        fill(listOf(blanks[0]), words)
        ui.tap(ui.tag("verification.slot.${blanks[0]}"))
        assertBlanksEmpty(blanks)

        fill(blanks, words)
        app.backgroundAndReactivate()
        assertStep(3, 4)
        assertEquals("Backgrounding keeps the blanks.", blanks, blankPositions())
        assertTrue("Backgrounding keeps the chip order.", chipLabels() == chipOrder)
        assertBlanksEmpty(blanks)
        fill(blanks, words)
        ui.tap(confirm)

        assertStep(4, 4)
        val password = ui.tag("Wallet password")
        val confirmation = ui.tag("Confirm password")
        ui.type(BACKUP_PASSWORD, into = password)
        ui.type(BACKUP_PASSWORD, into = confirmation)
        ui.scrollToExistence(ui.tag("setup.finish"))
        assertEnabled(ui.tag("setup.finish"), "Matching passwords can seal the wallet.")
        app.backgroundAndReactivate()
        assertStep(4, 4)
        assertEmptySecureField(password, placeholder = "Wallet password")
        assertEmptySecureField(confirmation, placeholder = "Confirm password")
        ui.scrollToExistence(ui.tag("setup.finish"))
        assertDisabled(ui.tag("setup.finish"), "Backgrounding clears both passwords.")
        ui.type(BACKUP_PASSWORD, into = password)
        ui.type(BACKUP_PASSWORD, into = confirmation)
        ui.tap(ui.tag("setup.finish"))

        ui.waitFor(ui.labeled("Wallet Created"), 60_000)
        ui.tap(ui.tag("setup.connect"))
        ui.waitFor(ui.title("Pair Wallet"), 10_000, "Pair opens on Wallet Level.")
        assertNotExists(ui.anyPasswordField(), "Pairing uses public data only.")
        // The push after closing setup completes: the page is interactive and its QR loads.
        assertTrue("The pairing QR loads once the Connect page has fully appeared.", ui.waitUntilEnabled(ui.tag("connect.copy")))
        assertEquals("Wallet Level", ui.segmentLabel("connect.level", 0))
        ui.click(ui.segment("connect.level", "Coin Level"))
        ui.waitFor(ui.title("Pair Bitcoin"), 10_000)
        assertScrolledTo(ui.tag("pairing.outputMode"), "Bitcoin offers its export forms.")
        assertEquals("Account XPUB", ui.segmentLabel("pairing.outputMode", 0))
        assertTrue("Account XPUB is the default Bitcoin export.", ui.isSelected(ui.segment("pairing.outputMode", "Account XPUB")))
        ui.tap(ui.tag("chain.evm"))
        ui.waitFor(ui.title("Pair EVM"), 5_000)
        assertTrue("EVM is selected.", ui.isSelected(ui.tag("chain.evm")))
        assertFalse("Bitcoin is no longer selected.", ui.isSelected(ui.tag("chain.bitcoin")))
    }

    /** G1.4 (UIT:187-207). */
    @Test
    fun testShowPhraseAgainReturnsToTheSameConcealedDraft() {
        app.launch()
        openSetup(Setup.Create)
        ui.click(ui.segment("setup.wordCount", "12 words"))
        ui.tap(ui.tag("setup.create"))
        val words = revealWords()
        ui.tap(ui.tag("backup.continue"))
        assertStep(3, 4)

        ui.tap(ui.tag("verification.showPhrase"))
        assertStep(2, 4)
        assertNotExists(ui.tag("recovery.word.0"), "Reviewing again starts concealed.")
        assertTrue("Reviewing again must keep the same phrase.", revealWords() == words)
        ui.tap(ui.tag("recovery.hide"))

        ui.click(ui.tag("setup.cancel"))
        val discard = ui.labeled("Discard")
        ui.waitFor(discard, 3_000, "A generated phrase is only discarded after confirmation.")
        ui.click(discard)
        ui.waitFor(ui.tag("welcome.create"), 5_000)
    }

    /** G1.5 (UIT:210-264). */
    @Test
    fun testBackupOffersRecoveryCardsTipsAndAConcealedQR() {
        app.launch()
        openSetup(Setup.Create)
        ui.waitFor(ui.segment("setup.wordCount", "12 words"), 10_000)
        ui.click(ui.segment("setup.wordCount", "12 words"))
        ui.tap(ui.tag("setup.create"))
        assertStep(2, 4)

        // Recovery cards (2 of 3) start concealed and show with one tap.
        ui.tap(ui.tag("backup.cards"))
        ui.waitFor(ui.title("Recovery Cards"), 5_000)
        val cardsReveal = ui.tag("cards.reveal")
        ui.waitFor(cardsReveal, 3_000)
        val firstCard = ui.tag("cards.card.0")
        assertNotExists(firstCard, "Cards start concealed.")
        assertExists(ui.containing("12-word phrase"), "Short phrases warn that one card reveals too much.")
        val windows = appWindowCount()
        ui.tap(cardsReveal)
        ui.waitFor(firstCard, 3_000)
        assertEquals("Revealing the cards asks nothing.", windows, appWindowCount())
        // Word 1 is left out of card 1 and present on cards 2 and 3.
        assertEquals("Word 1, not on this card", ui.label(ui.tag("cards.card.0.word.0")))
        if (ui.scrollToExistence(ui.tag("cards.card.1.word.0"))) {
            assertTrue("Word 1 is on card 2.", ui.label(ui.tag("cards.card.1.word.0")) != "Word 1, not on this card")
        } else {
            ui.failWithScreen("Card 2 is missing.")
        }
        app.backgroundAndReactivate()
        assertNotExists(firstCard, "Backgrounding conceals the cards.")
        if (ui.exists(ui.title("Recovery Cards"))) ui.click(ui.tag("cards.done"))
        ui.waitFor(ui.tag("recovery.reveal"), 5_000)
        ui.scrollToExistence(ui.tag("backup.continue"))
        assertEnabled(ui.tag("backup.continue"), "Showing the words on the cards counts as revealing them.")

        ui.tap(ui.tag("backup.tips"))
        ui.waitFor(ui.title("Security Tips"), 3_000)
        ui.click(ui.tag("tips.done"))

        // The QR code holds the plain phrase: concealed, shown with one tap, never shared.
        ui.waitForNonExistence(ui.title("Security Tips"), 3_000)
        ui.tap(ui.segment("backup.format", "QR Code"))
        assertNotExists(ui.tag("recovery.word.0"), "The QR tab shows no words.")
        val qrReveal = ui.tag("backup.qr.reveal")
        ui.waitFor(qrReveal, 3_000)
        assertNotExists(ui.tag("backup.qr"), "The QR code starts concealed.")
        assertExists(ui.containing("plain text"), "The QR tab says it holds the plain phrase.")
        val before = appWindowCount()
        ui.tap(qrReveal)
        ui.waitFor(ui.tag("backup.qr"), 5_000)
        assertEquals("Revealing the QR code asks nothing.", before, appWindowCount())
        assertNotExists(ui.labeled("Save or Print"), "The plain QR code is never offered for sharing.")
        // Content scrolls under the bottom bar.
        ui.scrollClear(ui.tag("backup.continue"), ui.tag("backup.qr.hide"))
        ui.tap(ui.tag("backup.qr.hide"))
        ui.waitFor(qrReveal, 3_000)

        ui.click(ui.tag("setup.cancel"))
        val discard = ui.labeled("Discard")
        ui.waitFor(discard, 3_000)
        ui.click(discard)
    }

    /** G1.6 (UIT:267-298). */
    @Test
    fun testImportAcceptsOtherLanguagesAndAsksWhenAmbiguous() {
        app.launch()
        openSetup(Setup.Restore)
        val input = ui.tag("recovery.input.visible")
        ui.waitFor(input, 5_000)
        // A Spanish phrase typed without accents ("dragon" for "dragón").
        ui.setText(input, SPANISH_PHRASE)
        val status = ui.tag("recovery.status")
        ui.waitUntil(3_000) { ui.label(status).contains("Español") }
        assertTrue("The words are counted.", ui.label(status).contains("12 words"))
        assertTrue("The language is detected while typing.", ui.label(status).contains("Español"))
        ui.tap(ui.tag("setup.import"))
        assertStep(2, 2)
        ui.click(ui.tag("setup.cancel"))
        val discard = ui.labeled("Discard")
        ui.waitFor(discard, 3_000)
        ui.click(discard)

        // Valid Italian, and valid Spanish once accents are added: two wallets, so ask.
        openSetup(Setup.Restore)
        ui.waitFor(input, 5_000)
        ui.setText(input, AMBIGUOUS_PHRASE)
        ui.tap(ui.tag("setup.import"))
        val spanish = ui.startingWith("Español")
        ui.waitFor(spanish, 5_000, "The person chooses the language of their backup.")
        assertExists(ui.startingWith("Italiano"), "Both readings are offered.")
        ui.click(spanish)
        assertStep(2, 2)
        ui.type(FIXTURE_PASSWORD, into = ui.tag("Wallet password"))
        ui.type(FIXTURE_PASSWORD, into = ui.tag("Confirm password"))
        ui.tap(ui.tag("setup.finish"))
        ui.waitFor(ui.labeled("Wallet Imported"), 60_000)
    }

    /** G1.7 (UIT:301-328). */
    @Test
    fun testImportDraftSurvivesBackground() {
        app.launch()
        openSetup(Setup.Restore)
        val name = ui.tag("setup.name")
        ui.waitFor(name, 5_000)
        ui.clear(name)
        ui.setText(name, "Travel Signer")
        val input = ui.tag("recovery.input.visible")
        ui.setText(input, PUBLIC_RECOVERY_PHRASE)

        val windows = appWindowCount()
        app.backgroundAndReactivate()
        assertStep(1, 2)
        assertEquals("Travel Signer", ui.textOf(name))
        assertEquals(PUBLIC_RECOVERY_PHRASE, ui.textOf(input))
        assertEquals("Coming back shows no dialog.", windows, appWindowCount())

        ui.tap(ui.tag("setup.import"))
        assertStep(2, 2)
        val password = ui.tag("Wallet password")
        ui.type(FIXTURE_PASSWORD, into = password)
        app.backgroundAndReactivate()
        assertEmptySecureField(password, placeholder = "Wallet password")

        ui.goBack()
        assertStep(1, 2)
        ui.waitFor(input, 5_000)
        assertEquals(PUBLIC_RECOVERY_PHRASE, ui.textOf(input))
        ui.scrollToExistence(name)
        assertEquals("Travel Signer", ui.textOf(name))
    }

    // MARK: - Signing

    private fun pasteRequest(request: String) {
        ui.tap(ui.tag("signing.paste"))
        val payload = ui.tag("signing.payload")
        ui.waitFor(payload, 3_000)
        ui.typePayload(request, into = payload)
        ui.tap(ui.tag("signing.decode"))
    }

    /** G1.8 (UIT:333-431). */
    @Test
    fun testPastedRequestIsMatchedReviewedAndSignedWithPassword() {
        app.launch(fixtures = 1)
        ui.selectTab("Scan")
        ui.waitFor(ui.tag("signing.scan"), 5_000)

        ui.tap(ui.tag("signing.paste"))
        val payload = ui.tag("signing.payload")
        ui.waitFor(payload, 3_000)
        assertDisabled(ui.tag("signing.decode"), "Nothing to decode yet.")
        ui.typePayload("unsupported-qr-test-fixture", into = payload)
        ui.tap(ui.tag("signing.decode"))
        ui.waitFor(ui.containing("Unsupported"), 10_000)
        ui.click(ui.labeled("Cancel"))
        ui.waitForNonExistence(ui.title("Paste Request"), 5_000)

        pasteRequest(PUBLIC_SIGNING_REQUEST)

        ui.waitFor(ui.title("Review Request"), 20_000)
        val sign = ui.tag("signing.authorize")
        ui.waitFor(sign, 20_000)
        assertTrue("The request must match the fixture wallet by its keys.", ui.label(sign).contains("Public fixture 1"))
        val risk = ui.tag("signing.risk")
        assertScrolledTo(risk, "The review carries the risk acknowledgement.")
        assertDisabled(sign, "Signing waits for the risk acknowledgement.")
        ui.tap(risk)
        assertTrue("The acknowledgement is checked.", ui.waitUntil(3_000) { ui.isSelected(risk) })
        assertEnabled(sign, "The acknowledgement enables signing.")

        ui.tap(sign)
        val password = ui.tag("Wallet password")
        ui.waitFor(password, 5_000)
        ui.type(FIXTURE_PASSWORD, into = password)
        app.backgroundAndReactivate()
        assertTrue(
            "Backgrounding closes authorization and discards the typed password.",
            ui.waitForNonExistence(ui.title("Authorize Signature"), 5_000),
        )
        assertScrolledTo(risk, "The acknowledgement is still offered.")
        assertFalse("Consent is per visit.", ui.isSelected(risk))
        assertDisabled(sign, "Signing waits for a new acknowledgement.")

        ui.tap(risk)
        ui.tap(sign)
        ui.waitFor(password, 5_000)
        assertEmptySecureField(password, placeholder = "Wallet password")
        ui.type(WRONG_PASSWORD, into = password)
        ui.tap(ui.tag("authorize.sign"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000)
        ui.type(FIXTURE_PASSWORD, into = password)
        ui.tap(ui.tag("authorize.sign"))

        ui.waitFor(ui.tag("signature.title"), 30_000)
        // Done is the only primary action; the payload and file export sit in the result card (scrolled to: the card
        // is below the fold on a phone, and the pinned bar with Done stays on screen).
        assertScrolledTo(ui.tag("signature.export"), "The result card offers the file export.")
        assertEquals("One Export as File.", 1, countLabeled("Export as File"))
        val signatureToggle = ui.tag("signature.payloadToggle")
        ui.scrollClear(ui.tag("signature.done"), signatureToggle)
        ui.tap(signatureToggle)
        val signaturePayload = ui.tag("signature.payload")
        ui.waitFor(signaturePayload, 5_000)
        assertLabelContains(signaturePayload, "request-id", "signature")
        assertExists(ui.tag("signature.export"), "The signature can be exported as a file.")
        ui.tap(ui.tag("signature.done"))
        ui.waitFor(ui.tag("signing.scan"), 5_000)

        ui.selectTab("Activity")
        val row = ui.tag("activity.row")
        ui.waitFor(row, 5_000)
        assertTrue("The record names the wallet.", ui.label(row).contains("Public fixture 1"))
        ui.tap(row)
        ui.waitFor(ui.title("Signature"), 3_000)
        assertExists(ui.containing("Request fingerprint"), "The record shows the request fingerprint.")
        // The record keeps what went in and what came out. The request is reviewed again on device, so the decoded
        // fields are the real ones.
        val decoded = ui.tag("activity.decoded")
        assertScrolledTo(decoded, "The stored request decodes again.")
        ui.tap(decoded)
        ui.waitFor(ui.title("Signed Request"), 10_000)
        ui.waitFor(ui.containing("Details"), 5_000)
        assertScrolledTo(ui.containing("Derivation path"), "The decoded request lists its derivation path.")
        ui.goBack()
        ui.waitFor(ui.title("Signature"), 5_000)
        // The signature can be shown again; doing so creates nothing new.
        val showQR = ui.tag("activity.showQR")
        assertScrolledTo(showQR, "The signature QR can be shown again.")
        ui.tap(showQR)
        ui.waitFor(ui.containing("Scanning it again signs nothing new"), 5_000)
        ui.click(ui.labeled("Done"))
        ui.waitFor(ui.title("Signature"), 5_000)
        assertScrolledTo(ui.tag("activity.export"), "The record can be exported.")
        val storedPayload = ui.tag("activity.signatureToggle")
        assertScrolledTo(storedPayload, "The stored signature can be shown.")
        ui.tap(storedPayload)
        ui.waitFor(ui.containing("ur:eth-signature"), 5_000)
        ui.goBack()
        ui.tap(ui.tag("activity.clear"))
        val clear = ui.labeled("Clear Activity")
        ui.waitFor(clear, 3_000)
        ui.click(clear)
        ui.waitFor(ui.labeled("No Signatures Yet"), 3_000)
    }

    /** G1.9 (UIT:434-446). */
    @Test
    fun testRejectClearsTheReviewedRequest() {
        app.launch(fixtures = 1)
        ui.selectTab("Scan")
        pasteRequest(PUBLIC_SIGNING_REQUEST)
        ui.waitFor(ui.tag("signing.authorize"), 20_000)
        ui.click(ui.tag("signing.reject"))
        ui.waitFor(ui.tag("signing.scan"), 5_000)
        assertNotExists(ui.title("Review Request"), "Reject leaves the review.")
    }

    /** G1.10 (UIT:451-485): a dApp approval names the spender when the bundled library knows it, and says so plainly when it does not. */
    @Test
    fun testApprovalNamesAKnownSpenderAndFlagsAnUnlistedOne() {
        app.launch(fixtures = 1)
        ui.selectTab("Scan")
        pasteRequest(APPROVE_KNOWN_SPENDER_REQUEST)

        ui.waitFor(ui.title("Review Request"), 20_000)
        val notice = ui.tag("review.contractNotice")
        ui.waitFor(notice, 20_000, "The review carries the known-contract notice.")
        val known = ui.label(notice)
        assertTrue("The notice names the deployment: $known", known.contains("Permit2") && known.contains("Uniswap"))
        assertTrue(known, known.contains("Known public contract"))
        assertScrolledTo(ui.containing("Spender (known contract)"), "Details repeat the identification as a field.")
        assertNotExists(ui.containing("not in the bundled list"), "A listed spender is never also called unlisted.")
        ui.click(ui.tag("signing.reject"))

        ui.waitFor(ui.tag("signing.scan"), 5_000)
        pasteRequest(APPROVE_UNLISTED_SPENDER_REQUEST)

        ui.waitFor(ui.title("Review Request"), 20_000)
        val unlisted = ui.tag("review.contractNotice")
        ui.waitFor(unlisted, 20_000)
        val label = ui.label(unlisted)
        assertTrue(label, label.contains("Spender is not a known contract"))
        assertFalse(label, label.contains("Permit2"))
        assertScrolledTo(
            ui.containing("not in the bundled list of known public contracts"),
            "The warning says what to check before signing.",
        )
    }

    /** Answers the camera permission dialog (Allow while using the app) while waiting for the scanner's Close. */
    private fun openScannerAnsweringPermission() {
        val close = ui.labeled("Close")
        val allow = Target("camera permission Allow") { u ->
            PERMISSION_ALLOW_IDS.firstNotNullOfOrNull { id -> u.device.findObject(By.res(id)) }
        }
        ui.waitUntil(20_000) {
            allow.locate(ui)?.click()
            ui.exists(close)
        }
        if (!ui.exists(close)) ui.failWithScreen("The camera opens once the permission is answered.")
    }

    /** G1.11 (UIT:490-510): the scan button on the wallet list opens the camera itself. */
    @Test
    fun testWalletsScanButtonOpensTheCameraDirectly() {
        app.launch(fixtures = 1, camera = false)
        ui.waitFor(ui.tag("wallets.scan"), 10_000)
        ui.click(ui.tag("wallets.scan"))
        openScannerAnsweringPermission()
        assertExists(ui.title("Scan Request"), "The scanner is open.")
        ui.click(ui.labeled("Close"))
        // Closing it leaves the person on Scan, where the request would be reviewed.
        ui.waitFor(ui.tag("signing.scan"), 5_000)
    }

    /** G1.12 (UIT:513-534). */
    @Test
    fun testScanCardOpensCameraWithoutPassword() {
        app.launch(fixtures = 1, camera = true)
        ui.selectTab("Scan")
        val card = ui.tag("signing.scan")
        ui.tap(card)
        openScannerAnsweringPermission()
        assertExists(ui.title("Scan Request"), "The scanner sheet opens.")
        assertNotExists(ui.anyPasswordField(), "Scanning asks for no password.")
        ui.click(ui.labeled("Close"))
        ui.waitFor(card, 5_000)
    }

    // MARK: - Wallets

    /** G1.13 (UIT:539-625). */
    @Test
    fun testConnectAndAddressesUsePublicDataOnly() {
        app.launch(fixtures = 3)
        assertEquals(3, walletCards().size)
        // Wallets are labelled by their master fingerprint (public test phrase: 73C5DA0A).
        ui.waitFor(ui.containing("Fingerprint $FINGERPRINT"), 10_000)
        ui.click(ui.tagPrefix("wallet.connect."))
        // Wallet level: where the page opens, one animated QR for every chain, in a single form (no Simple/Full choice).
        ui.waitFor(ui.title("Pair Wallet"), 5_000)
        assertNotExists(ui.tag("connect.walletMode"), "Wallet level has one form only.")
        ui.waitFor(ui.containing("bc1q, bc1p, 3, 1"), 10_000, "The page says it carries every Bitcoin address type.")
        assertExists(ui.containing("all three Solana addresses"), "The page names the Solana addresses.")
        ui.waitFor(ui.tag("connect.walletQR"), 20_000)
        assertTrue("The wallet-level QR can be copied.", ui.waitUntilEnabled(ui.tag("connect.copy")))
        assertScrolledTo(ui.containing("bittool-signer-wallet v1"), "The format is named.")
        assertScrolledTo(ui.containing("BitTool Wallet"), "BitTool Wallet is listed for wallet-level pairing.")
        repeat(4) { ui.swipeDown() }
        ui.tap(ui.segment("connect.level", "Coin Level"))
        ui.waitFor(ui.title("Pair Bitcoin"), 5_000)
        // Each coin-level QR shows what it carries, collapsed until tapped.
        val payloadToggle = ui.tag("connect.payloadToggle")
        assertScrolledTo(payloadToggle, "The payload can be shown.")
        ui.tap(payloadToggle)
        val payloadText = ui.tag("connect.payload")
        ui.waitFor(payloadText, 5_000)
        assertLabelContains(payloadText, "master-fingerprint")
        assertExists(ui.containing("1942346250 is fingerprint $FINGERPRINT"), "The fingerprint is explained.")
        repeat(3) { ui.swipeDown() }
        for ((chain, title) in listOf("evm" to "Pair EVM", "tron" to "Pair TRON", "solana" to "Pair Solana", "bitcoin" to "Pair Bitcoin")) {
            ui.tap(ui.tag("chain.$chain"))
            ui.waitFor(ui.title(title), 5_000)
        }
        // TRON exports any address index, not just 0–99.
        ui.tap(ui.tag("chain.tron"))
        val index = ui.tag("connect.tronIndex.field")
        ui.waitFor(index, 5_000)
        ui.setText(index, "123456")
        ui.dismissKeyboard()
        ui.waitFor(ui.containing("m/44'/195'/0'/0/123456"), 10_000)
        assertTrue("The address for index 123456 is exported.", ui.waitUntilEnabled(ui.tag("connect.copy")))
        // TRON wallets can only watch an address; none is listed as a QR signer, and Ownbit is gone.
        assertScrolledTo(ui.containing("Watch only"), "TRON wallets watch an address.")
        assertNotExists(ui.containing("Watch and sign by QR"), "No TRON wallet signs by QR.")
        assertScrolledTo(ui.containing("TokenPocket"), "TokenPocket is listed.")
        assertNotExists(ui.containing("Ownbit"), "Ownbit is not listed.")
        repeat(4) { ui.swipeDown() }
        ui.tap(ui.tag("chain.bitcoin"))
        ui.waitFor(ui.title("Pair Bitcoin"), 5_000)
        assertScrolledTo(ui.containing("Watch and sign by QR"), "Bitcoin wallets sign by QR.")
        assertScrolledTo(ui.containing("Sparrow"), "Sparrow is listed.")
        assertScrolledTo(ui.containing("BlueWallet"), "BlueWallet is listed.")
        assertScrolledTo(ui.segment("pairing.outputMode", "Account XPUB"), "Bitcoin offers its export forms.")
        assertNotExists(ui.segment("pairing.outputMode", "Public Key"), "The non-standard leaf-key export is gone.")
        repeat(4) { ui.swipeDown() }
        // Switching levels keeps each side as it was.
        ui.tap(ui.segment("connect.level", "Wallet Level"))
        ui.waitFor(ui.title("Pair Wallet"), 5_000)
        ui.tap(ui.segment("connect.level", "Coin Level"))
        ui.waitFor(ui.title("Pair Bitcoin"), 5_000, "Coin level is kept as it was.")
        assertNotExists(ui.anyPasswordField(), "Pairing never asks for a password.")
        app.backgroundAndReactivate()
        ui.waitFor(ui.title("Pair Bitcoin"), 5_000)
        ui.goBack()

        ui.click(ui.tagPrefix("wallet.addresses."))
        ui.waitFor(ui.title("Addresses"), 5_000)
        val first = ui.tag("address.row.0")
        ui.waitFor(first, 20_000)
        assertTrue("Bitcoin lists Native SegWit first.", ui.label(first).replace(" ", "").contains("bc1q"))
        ui.tap(ui.tag("chain.evm"))
        val evmRow = Target("an address row containing 0x") { u -> u.all("address.row.").firstOrNull { u.label(it).contains("0x") } }
        ui.waitFor(evmRow, 20_000)
        // TRON lists plain addresses under a plain-language header, with no derivation path.
        ui.tap(ui.tag("chain.tron"))
        ui.waitFor(ui.containing("TRX and TRC-20"), 20_000)
        ui.waitFor(ui.tag("address.row.0"), 20_000)
        assertNotExists(ui.containing("195'"), "TRON shows no derivation path.")
        ui.tap(ui.tag("chain.solana"))
        for (profile in listOf("Standard", "Trust", "Legacy")) {
            assertScrolledTo(ui.startingWith(profile), "Solana lists every supported derivation path.")
        }
        repeat(4) { ui.swipeDown() }
        ui.tap(ui.startingWith("Standard"))
        ui.waitFor(ui.containing("Derivation path"), 5_000)
        ui.click(ui.labeled("Done"))
        assertNotExists(ui.anyPasswordField(), "Addresses never ask for a password.")
    }

    /** G1.14 (UIT:628-663). */
    @Test
    fun testPasswordChangeProtectsRecoveryPhrase() {
        app.launch(fixtures = 1)
        ui.click(firstCard)
        ui.tap(ui.tag("detail.changePassword"))
        val current = ui.tag("Current wallet password")
        ui.waitFor(current, 5_000)
        ui.type(WRONG_PASSWORD, into = current)
        ui.type(UPDATED_PASSWORD, into = ui.tag("New password"))
        ui.type(UPDATED_PASSWORD, into = ui.tag("Confirm new password"))
        ui.tap(ui.tag("vaultManagement.submit"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000, "A wrong current password must be reported, not silently ignored.")
        ui.clear(current)
        ui.type(FIXTURE_PASSWORD, into = current)
        ui.tap(ui.tag("vaultManagement.submit"))
        assertTrue("Change Password closes.", ui.waitForNonExistence(ui.title("Change Password"), 60_000))
        ui.waitFor(ui.tag("detail.recoveryPhrase"), 5_000, "Changing the password keeps the wallet page open.")

        ui.tap(ui.tag("detail.recoveryPhrase"))
        val password = ui.tag("Wallet password")
        ui.waitFor(password, 5_000)
        ui.type(FIXTURE_PASSWORD, into = password)
        ui.tap(ui.tag("vaultManagement.submit"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000, "The previous password no longer opens the wallet.")
        ui.type(UPDATED_PASSWORD, into = password)
        ui.tap(ui.tag("vaultManagement.submit"))
        ui.waitFor(ui.tag("recovery.reveal"), 30_000)
        assertNotExists(ui.tag("recovery.word.0"), "The phrase starts concealed.")

        app.backgroundAndReactivate()
        ui.waitFor(password, 5_000, "Backgrounding discards the decrypted phrase.")
        assertNotExists(ui.tag("recovery.reveal"), "The decrypted phrase is gone.")
        ui.click(ui.labeled("Cancel"))
        ui.waitFor(ui.tag("detail.recoveryPhrase"), 5_000)
    }

    /**
     * G1.15 (UIT:669-717): forgetting the password is recoverable with the recovery phrase: importing the same phrase
     * again offers to reset that wallet's password rather than refusing a duplicate, and the wallet keeps its identity.
     */
    @Test
    fun testImportingAnExistingPhraseResetsItsPasswordInPlace() {
        app.launch(fixtures = 1)
        val name = ui.label(firstCard)

        ui.click(ui.tag("wallets.add"))
        ui.tap(ui.labeled("Import Recovery Phrase"))
        skipOnboardingIfShown()
        val input = ui.tag("recovery.input.visible")
        ui.waitFor(input, 10_000)
        // The fixture wallet's own phrase, with no BIP39 passphrase.
        ui.setText(input, PUBLIC_RECOVERY_PHRASE)
        ui.tap(ui.tag("setup.import"))

        val finish = ui.tag("setup.finish")
        ui.waitFor(ui.title("Reset Password"), 30_000, "An identical phrase offers to reset that wallet's password.")
        assertScrolledTo(finish, "The reset page has its button.")
        assertNotExists(ui.containing("If you forget this password"), "The hint describes what this screen is already doing.")

        ui.type(RECOVERED_PASSWORD, into = ui.tag("Wallet password"))
        ui.type(RECOVERED_PASSWORD, into = ui.tag("Confirm password"))
        ui.tap(finish)
        ui.waitFor(ui.labeled("Password Reset"), 60_000)
        ui.tap(ui.tag("setup.done"))

        // One wallet, the same one: a reset is not an import.
        ui.waitFor(firstCard, 20_000)
        assertEquals("The wallet is reset in place, never duplicated.", 1, walletCards().size)
        assertEquals("It keeps its name and identity.", name, ui.label(firstCard))

        // Only the new password opens it now.
        ui.click(firstCard)
        ui.tap(ui.tag("detail.recoveryPhrase"))
        val password = ui.tag("Wallet password")
        ui.waitFor(password, 5_000)
        ui.type(FIXTURE_PASSWORD, into = password)
        ui.tap(ui.tag("vaultManagement.submit"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000, "The forgotten password no longer opens the wallet.")
        ui.type(RECOVERED_PASSWORD, into = password)
        ui.tap(ui.tag("vaultManagement.submit"))
        ui.waitFor(ui.tag("recovery.reveal"), 30_000, "The new password opens the same recovery phrase.")
    }

    /** G1.16 (UIT:720-769). */
    @Test
    fun testRenameThenDeleteLastWalletReturnsToWelcome() {
        app.launch(fixtures = 1)
        ui.click(firstCard)
        // The wallet page also opens Connect on its wallet-level QR.
        ui.tap(ui.tag("detail.connect.wallet"))
        ui.waitFor(ui.title("Pair Wallet"), 10_000)
        ui.waitFor(ui.tag("connect.walletQR"), 20_000)
        ui.goBack()
        ui.waitFor(ui.title("Public fixture 1"), 10_000, "Back returns to the wallet page.")
        // Picking a chain is a coin-level choice, so that page opens on Coin Level for that chain.
        for ((chain, title) in listOf("bitcoin" to "Pair Bitcoin", "tron" to "Pair TRON")) {
            ui.tap(ui.tag("detail.connect.$chain"))
            ui.waitFor(ui.title(title), 10_000, "A chain row opens on Coin Level.")
            assertTrue("Coin Level is selected.", ui.isSelected(ui.segment("connect.level", "Coin Level")))
            ui.goBack()
            ui.waitFor(ui.title("Public fixture 1"), 10_000)
        }
        ui.tap(ui.tag("detail.rename"))
        ui.waitFor(ui.dialog("Rename Wallet"), 3_000)
        val field = ui.tag("rename.name")
        ui.waitFor(field, 3_000)
        ui.setText(field, "Travel Signer")
        ui.click(ui.labeled("Save"))
        ui.waitFor(ui.title("Travel Signer"), 5_000)

        ui.tap(ui.tag("detail.delete"))
        val submit = ui.tag("vaultManagement.submit")
        ui.waitFor(submit, 5_000)
        ui.scrollToExistence(submit)
        assertDisabled(submit, "Deleting requires confirming the deletion itself.")
        ui.waitFor(ui.tag("delete.backupConfirmed"), 10_000)
        ui.setSwitch(ui.tag("delete.backupConfirmed"), on = true)
        // The password is asked for in its own prompt (App Lock is off, so no biometric prompt first), and nothing is
        // deleted until it is right.
        ui.tap(submit)
        val prompt = ui.dialog("Wallet Password")
        ui.waitFor(prompt, 20_000, "Deleting asks for the wallet password.")
        ui.setText(ui.tag("delete.password"), WRONG_PASSWORD)
        ui.click(ui.labeled("Delete"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000, "A wrong password must be reported and must not delete anything.")
        assertExists(ui.title("Delete Wallet"), "The wallet is still there.")
        ui.tap(submit)
        ui.waitFor(prompt, 20_000)
        ui.setText(ui.tag("delete.password"), FIXTURE_PASSWORD)
        ui.click(ui.labeled("Delete"))
        ui.waitFor(ui.tag("welcome.create"), 30_000)
    }

    // MARK: - App Lock and Settings

    /** G1.17 (UIT:774-803). */
    @Test
    fun testAppLockColdLaunchPasswordFallbackAndShortBackground() {
        app.launch(fixtures = 1, dark = true)
        val run = app.runId
        app.forceStop()
        app.launch(run = run, reset = false, appLock = true, dark = true, waitUntilReady = false)

        ui.waitFor(ui.labeled("BitTool Signer Is Locked"), 15_000)
        ui.waitFor(ui.containing(BIOMETRIC_FAILURE), 5_000)
        assertFalse("Nothing behind the lock is reachable.", ui.exists(ui.tab("Scan")) && ui.isHittable(ui.tab("Scan")))
        assertNotExists(ui.tab("Scan"), "The tabs behind the lock are not in the accessibility tree (app-shell §15.7).")
        ui.click(ui.tag("appLock.usePassword"))
        val password = ui.tag("Wallet password")
        ui.waitFor(password, 3_000)
        ui.type(WRONG_PASSWORD, into = password)
        ui.tap(ui.tag("appLock.unlock"))
        ui.waitFor(ui.containing("password is incorrect"), 30_000)
        ui.type(FIXTURE_PASSWORD, into = password)
        ui.tap(ui.tag("appLock.unlock"))
        assertTrue("The lock goes once the password is right.", ui.waitForNonExistence(ui.labeled("BitTool Signer Is Locked"), 30_000))

        ui.waitFor(ui.tagPrefix("wallet.addresses."), 10_000)
        ui.click(ui.tagPrefix("wallet.addresses."))
        ui.waitFor(ui.title("Addresses"), 5_000)
        app.backgroundAndReactivate()
        ui.waitFor(ui.title("Addresses"), 5_000, "A short visit elsewhere keeps the page.")
        assertNotExists(ui.labeled("BitTool Signer Is Locked"), "The grace period applies to short background visits.")
    }

    /** G1.18 (UIT:806-916). */
    @Test
    fun testSettingsReferencePagesAndGuide() {
        app.launch(fixtures = 1)
        ui.selectTab("Settings")
        val lock = ui.tag("settings.appLock")
        ui.waitFor(lock, 5_000)
        assertFalse("App Lock starts off.", ui.find(lock).isChecked)
        ui.click(lock)
        ui.waitFor(ui.containing(BIOMETRIC_FAILURE), 5_000)
        assertFalse("App Lock only turns on after successful biometrics.", ui.find(lock).isChecked)

        val directory = "Networks, Tokens & Contracts"
        ui.tap(ui.labeled(directory))
        ui.waitFor(ui.title(directory), 5_000)
        ui.tap(ui.tag("network.solana"))
        ui.waitFor(ui.title("Solana"), 5_000)
        ui.waitFor(ui.tag("reference.token"), 5_000, "The bundled registry lists Solana tokens.")
        ui.goBack()
        ui.tap(ui.tag("network.ethereum"))
        ui.waitFor(ui.title("Ethereum"), 5_000)
        val search = ui.tag("reference.search")
        ui.waitFor(search, 5_000)
        ui.setText(search, "tether")
        ui.waitFor(ui.containing("USDT"), 3_000)
        assertTrue("Search filters the list.", ui.waitForNonExistence(ui.containing("USD Coin"), 3_000))
        // The same page browses the known-contract library for this network.
        ui.clear(search)
        val contracts = ui.tag("reference.contracts")
        ui.waitFor(contracts, 5_000, "Ethereum has both tokens and contracts.")
        ui.click(contracts)
        ui.waitFor(ui.tag("reference.contract"), 5_000, "The bundled library lists Ethereum contracts.")
        ui.setText(search, "permit2")
        ui.waitFor(ui.containing("Permit2"), 3_000)
        assertTrue("Search filters the contract list.", ui.waitForNonExistence(ui.containing("Seaport"), 3_000))
        ui.goBack()
        if (!ui.waitForExistence(ui.title(directory), 2_000)) ui.goBack()
        ui.waitFor(ui.title(directory), 5_000)
        // Deterministic deployments are reachable as their own row.
        ui.tap(ui.tag("contracts.everyChain"))
        ui.waitFor(ui.title("All EVM networks"), 5_000)
        ui.waitFor(ui.tag("reference.contract"), 5_000)
        assertNotExists(ui.tag("reference.picker"), "A contracts-only page carries no picker.")
        ui.goBack()
        ui.waitFor(ui.title(directory), 5_000)
        ui.goBack()

        ui.tap(ui.labeled("Compatibility"))
        ui.waitFor(ui.title("Compatibility"), 3_000)
        app.backgroundAndReactivate()
        ui.waitFor(ui.title("Compatibility"), 3_000)

        // The Wallets tab lists every wallet with a site to copy, behind a notice that none of them is verified or endorsed.
        ui.click(ui.segment("compatibility.tab", "Wallets"))
        ui.waitFor(ui.containing("says it supports"), 5_000, "The directory says the claims are unverified.")
        assertExists(ui.containing("Anyone can submit a wallet"), "Anyone can submit a wallet.")
        assertExists(ui.containing("BitTool is not responsible"), "BitTool is not responsible.")
        assertScrolledTo(ui.tag("directory.wallet.Sparrow"), "Every listed wallet appears in the directory.")
        assertExists(ui.containing("sparrowwallet.com"), "The site is shown as copyable text.")
        assertFalse("No tappable links: the app has no network.", hasLinks())

        // Submitting a wallet is explained, not mailed: no mail client opens.
        val submit = ui.tag("directory.submit")
        assertScrolledTo(submit, "Submitting a wallet is offered.")
        ui.tap(submit)
        ui.waitFor(ui.title("Submit a Wallet"), 5_000)
        assertExists(ui.containing("support@bittool.com"), "The address is shown.")
        assertExists(ui.containing("Add supported wallet"), "The subject is shown.")
        for (required in listOf("Wallet name", "Official website", "App icon", "Source code", "QR formats supported")) {
            assertScrolledTo(ui.containing(required), required)
        }
        ui.goBack()
        ui.goBack()

        // Contact Us: the publisher and an address to copy, no mail client.
        ui.tap(ui.labeled("Contact Us"))
        ui.waitFor(ui.title("Contact Us"), 5_000)
        assertExists(ui.containing("BITTOOL PTE. LTD."), "The publisher is named.")
        assertExists(ui.containing("support@bittool.com"), "The address is shown.")
        assertFalse("The address is text, not a mailto link.", hasLinks())
        ui.goBack()

        // Open Source: the addresses are text, and the build is described on device. Android shows its own repository
        // as this app's source and the iOS repository as the engine's (DECISIONS §9.1).
        ui.tap(ui.tag("settings.openSource"))
        ui.waitFor(ui.title("Open Source"), 5_000)
        assertScrolledTo(ui.containing("https://github.com/bitbill/bittool-signer-android"), "The app's repository is named.")
        assertScrolledTo(ui.containing("https://github.com/bitbill/bittool-signer-ios"), "The engine's repository is named.")
        assertScrolledTo(ui.containing("Signing engine"), "The engine is described.")
        // The digest is read from the bundled engine, so it is 64 hex characters, never a placeholder.
        val digest = Target("a 64-hex-digit digest") { u -> u.device.findObject(By.text(java.util.regex.Pattern.compile("[0-9a-f]{64}"))) }
        assertScrolledTo(digest, "The engine digest is computed on device.")
        assertScrolledTo(ui.tag("openSource.notices"), "The notices are offered.")
        ui.tap(ui.tag("openSource.notices"))
        ui.waitFor(ui.title("Third-Party Notices"), 5_000)
        ui.goBack()
        ui.goBack()

        // Legal documents are part of the app: no browser, no network.
        ui.tap(ui.labeled("Terms of Use"))
        ui.waitFor(ui.title("Terms of Use"), 5_000)
        ui.waitFor(ui.containing("Version 1.1"), 5_000)
        ui.goBack()
        ui.tap(ui.labeled("Privacy Policy"))
        ui.waitFor(ui.title("Privacy Policy"), 5_000)
        ui.waitFor(ui.containing("collects nothing"), 5_000)
        ui.goBack()
        assertNotExists(ui.containing("open in your browser"), "Nothing opens a browser.")

        assertFalse("The guide is part of onboarding, not of Settings.", ui.scrollToExistence(ui.labeled("Offline Setup Guide")))
    }

    private companion object {
        /** The permission controller's "While using the app" / "Only this time" (AOSP and Google builds). */
        val PERMISSION_ALLOW_IDS = listOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.google.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.google.android.permissioncontroller:id/permission_allow_button",
        )
    }
}
