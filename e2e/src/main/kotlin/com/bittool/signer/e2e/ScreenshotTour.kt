package com.bittool.signer.e2e

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.bittool.signer.e2e.support.Args
import com.bittool.signer.e2e.support.DeviceSetupRule
import com.bittool.signer.e2e.support.Fixtures.APPROVE_KNOWN_SPENDER_REQUEST
import com.bittool.signer.e2e.support.Fixtures.FIXTURE_PASSWORD
import com.bittool.signer.e2e.support.Fixtures.PUBLIC_RECOVERY_PHRASE
import com.bittool.signer.e2e.support.Fixtures.PUBLIC_SIGNING_REQUEST
import com.bittool.signer.e2e.support.Fixtures.SPANISH_PHRASE
import com.bittool.signer.e2e.support.Shots
import com.bittool.signer.e2e.support.SignerApp
import com.bittool.signer.e2e.support.Target
import com.bittool.signer.e2e.support.Ui
import org.junit.Assert.fail
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures every main screen for visual review, light and dark (gaps.md G2; port of `ScreenshotTour.swift`, same shot
 * names so iOS and Android shots pair up). Uses isolated public fixtures only. The run allows capture
 * (`bts.uitest.allowCapture`, FLAG_SECURE off in debug builds only); PNGs are written to the instrumentation argument
 * `shotDir` as `<light|dark>-<name>.png` and pulled to `build/e2e-shots`. Shots marked sensitive hold throwaway words:
 * they go to that folder only, never into test reports.
 *
 * Like iOS (`continueAfterFailure = true`), a screen that does not appear is recorded and the tour goes on; the test
 * fails at the end with every problem it met. `extraArgs` (`locale=ar`, `fontScale=2.0`) gives the other variants.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    private val ui = Ui()
    private val app = SignerApp(ui)
    private var prefix = "light"
    private val problems = mutableListOf<String>()

    @Test
    fun tourLight() = tour(dark = false)

    @Test
    fun tourDark() = tour(dark = true)

    private fun tour(dark: Boolean) {
        prefix = if (dark) "dark" else "light"
        Shots.prepare(prefix)
        try {
            // Each phase starts from its own launch: a problem in one never costs the shots of the next.
            soft("setup screens") { setupScreens(dark) }
            soft("wallet screens") { walletScreens(dark) }
            // The run of the wallet screens (its launch set it), with its two fixture wallets.
            val run = app.runId
            soft("lock screens") { lockScreens(dark, run) }
        } finally {
            app.forceStop()
        }
        if (problems.isNotEmpty()) fail("The $prefix tour met ${problems.size} problem(s):\n" + problems.joinToString("\n"))
    }

    // MARK: Welcome, onboarding, import and create (TOUR:29-121)

    private fun setupScreens(dark: Boolean) {
        launch(fixtures = 0, dark = dark)
        shot("01-welcome")
        click(ui.tag("welcome.import"))
        if (ui.waitForExistence(ui.tag("onboarding.next"), 3_000)) {
            // Wait for each page before tapping, so a tap never lands mid-animation.
            for ((page, name) in listOf(1 to "02-onboarding-1", 2 to "03-onboarding-2", 3 to "04-onboarding-3")) {
                ui.waitForExistence(ui.labeled("Page $page of 3"), 5_000)
                settle()
                shot(name)
                click(ui.tag("onboarding.next"))
            }
        }
        val input = ui.tag("recovery.input.visible")
        if (!expect(input, 5_000)) return
        setText(input, PUBLIC_RECOVERY_PHRASE.dropLast(3))
        settle()
        shot("05-import-phrase")
        setText(input, PUBLIC_RECOVERY_PHRASE)
        tap(ui.tag("setup.import"))
        val password = ui.tag("Wallet password")
        if (!expect(password, 20_000)) return
        // Move between fields with the keyboard's action, as people do; the focused field must scroll into view above
        // the keyboard on small screens.
        click(password)
        setText(password, FIXTURE_PASSWORD)
        imeAction(password)
        // The shot is of the confirmation field focused above the keyboard: wait for the focus to get there.
        val confirmation = ui.tag("Confirm password")
        if (!ui.waitUntil(5_000) { confirmation.locate(ui)?.isFocused == true }) {
            problems += "The keyboard's Next did not move to the confirmation field"
        }
        settle()
        shot("06-password-confirm-focus")
        setText(ui.tag("Confirm password"), FIXTURE_PASSWORD)
        settle()
        shot("06-password")
        tap(ui.tag("setup.finish"))
        if (!expect(ui.tag("setup.done"), 60_000)) return
        settle()
        shot("07-imported")
        click(ui.tag("setup.done"))
        ui.waitForExistence(ui.tagPrefix(SignerApp.CARD_PREFIX), 20_000)
        settle()
        shot("08-wallets-first")

        click(ui.tag("wallets.add"))
        val create = ui.labeled("Create New Wallet")
        if (ui.waitForExistence(create, 3_000)) {
            click(create)
            if (ui.waitForExistence(ui.tag("setup.create"), 5_000)) {
                settle()
                shot("09-create")
                click(ui.segment("setup.wordCount", "12 words"))
                tap(ui.tag("setup.create"))
                if (ui.waitForExistence(ui.tag("recovery.reveal"), 10_000)) {
                    settle()
                    shot("09b-backup-concealed")
                    tap(ui.segment("backup.format", "QR Code"))
                    settle()
                    shot("09c-backup-qr-tab")
                    // A revealed QR code or card holds real (throwaway) words: the shot folder only, never a report.
                    tap(ui.tag("backup.qr.reveal"))
                    if (ui.waitForExistence(ui.tag("backup.qr"), 5_000)) {
                        settle()
                        shot("09d-backup-qr-revealed")
                        ui.scrollClear(ui.tag("backup.continue"), ui.tag("backup.qr.hide"))
                        tap(ui.tag("backup.qr.hide"))
                    }
                    tap(ui.segment("backup.format", "Words"))
                    tap(ui.tag("backup.cards"))
                    if (ui.waitForExistence(ui.tag("cards.reveal"), 5_000)) {
                        settle()
                        shot("09e-recovery-cards")
                        tap(ui.tag("cards.reveal"))
                        settle()
                        shot("09e-recovery-cards-revealed")
                        click(ui.tag("cards.done"))
                        ui.waitForNonExistence(ui.title("Recovery Cards"), 5_000)
                    }
                    tap(ui.tag("backup.tips"))
                    if (ui.waitForExistence(ui.title("Security Tips"), 3_000)) {
                        settle()
                        shot("09f-security-tips")
                        click(ui.tag("tips.done"))
                        ui.waitForNonExistence(ui.title("Security Tips"), 5_000)
                    }
                    tap(ui.tag("recovery.reveal"))
                    tap(ui.tag("recovery.hide"))
                    tap(ui.tag("backup.continue"))
                    if (ui.waitForExistence(ui.tag("verification.continue"), 5_000)) {
                        ui.tagPrefix("verification.chip.").locate(ui)?.click()
                        settle()
                        shot("09g-verify-board")
                    }
                }
                click(ui.tag("setup.cancel"))
                if (ui.waitForExistence(ui.labeled("Discard"), 3_000)) click(ui.labeled("Discard"))
            }
        }
        // Another language: a public Spanish test phrase typed without accents.
        ui.waitUntil(5_000) { ui.isHittable(ui.tag("wallets.add")) }
        click(ui.tag("wallets.add"))
        val importItem = ui.labeled("Import Recovery Phrase")
        if (ui.waitForExistence(importItem, 3_000)) {
            click(importItem)
            if (ui.waitForExistence(input, 5_000)) {
                setText(input, SPANISH_PHRASE)
                settle()
                shot("05b-import-spanish")
                click(ui.tag("setup.cancel"))
                if (ui.waitForExistence(ui.labeled("Discard"), 3_000)) click(ui.labeled("Discard"))
            }
        }
        app.forceStop()
    }

    // MARK: Wallets, signing, activity and settings (TOUR:125-267)

    private fun walletScreens(dark: Boolean): String {
        launch(fixtures = 2, dark = dark)
        val run = app.runId
        shot("10-wallets")
        click(ui.tagPrefix(SignerApp.CARD_PREFIX))
        ui.waitForExistence(ui.tag("detail.changePassword"), 5_000)
        settle()
        shot("11-wallet-detail")
        ui.swipeUp()
        settle()
        shot("12-wallet-detail-bottom")
        for ((id, name, title) in listOf(
            Triple("detail.changePassword", "19a-change-password", "Change Password"),
            Triple("detail.recoveryPhrase", "19b-recovery-password", "Recovery Phrase"),
            Triple("detail.delete", "19c-delete", "Delete Wallet"),
        )) {
            tap(ui.tag(id))
            if (ui.waitForExistence(ui.title(title), 5_000)) {
                settle()
                shot(name)
                click(ui.labeled("Cancel"))
                ui.waitForNonExistence(ui.title(title), 5_000)
            } else {
                problems += "$title did not open"
            }
        }
        back()
        click(ui.tagPrefix("wallet.connect."))
        ui.waitForExistence(ui.tag("connect.walletQR"), 20_000)
        settle()
        shot("15c-connect-wallet-level")
        ui.swipeUp()
        settle()
        shot("15d-connect-wallet-level-verify")
        repeat(2) { ui.swipeDown() }
        if (ui.waitForExistence(ui.tag("connect.level"), 3_000)) {
            click(ui.segment("connect.level", "Coin Level"))
            ui.waitForExistence(ui.title("Pair Bitcoin"), 5_000)
        }
        settle()
        shot("13-connect-btc")
        ui.swipeUp()
        settle()
        shot("14-connect-btc-bottom")
        ui.swipeDown()
        for ((chain, title) in listOf("evm" to "Pair EVM", "tron" to "Pair TRON", "solana" to "Pair Solana")) {
            val button = ui.tag("chain.$chain")
            if (ui.waitForExistence(button, 3_000)) {
                click(button)
                ui.waitForExistence(ui.title(title), 5_000)
                settle()
                shot("15-connect-$chain")
            }
            if (chain == "tron" && ui.waitForExistence(ui.tag("connect.tronIndex"), 3_000)) {
                val increment = Target("Increase of connect.tronIndex") { u ->
                    u.tag("connect.tronIndex").locate(u)?.findObject(By.desc("Increase"))
                }
                if (ui.exists(increment)) {
                    repeat(2) { click(increment) }
                    ui.waitForExistence(ui.containing("m/44'/195'/0'/0/2"), 5_000)
                    settle()
                    shot("15b-connect-tron-index")
                }
            }
        }
        back()
        click(ui.tagPrefix("wallet.addresses."))
        ui.waitForExistence(ui.tag("address.row.0"), 20_000)
        settle()
        shot("16-addresses-btc")
        for ((chain, name) in listOf("evm" to "17-addresses-evm", "tron" to "17c-addresses-tron", "solana" to "17b-addresses-solana")) {
            click(ui.tag("chain.$chain"))
            ui.waitForExistence(ui.tag("address.row.0"), 20_000)
            settle()
            shot(name)
        }
        click(ui.tag("chain.evm"))
        if (ui.waitForExistence(ui.tag("address.row.1"), 5_000)) {
            click(ui.tag("address.row.1"))
            ui.waitForExistence(ui.labeled("Done"), 5_000)
            settle()
            shot("18-address-detail")
            if (ui.exists(ui.labeled("Done"))) {
                click(ui.labeled("Done"))
                ui.waitForNonExistence(ui.tag("address.done"), 5_000)
            }
        }

        openTab("Scan")
        settle()
        shot("20-scan")
        click(ui.tag("signing.paste"))
        val payload = ui.tag("signing.payload")
        if (ui.waitForExistence(payload, 3_000)) {
            typePayload(PUBLIC_SIGNING_REQUEST, into = payload)
            settle()
            shot("21-paste")
            click(ui.tag("signing.decode"))
        }
        val sign = ui.tag("signing.authorize")
        if (expect(sign, 20_000)) {
            settle()
            shot("22-review")
            ui.swipeUp()
            settle()
            shot("23-review-middle")
            ui.swipeUp()
            settle()
            shot("24-review-bottom")
            val risk = ui.tag("signing.risk")
            if (ui.scrollToExistence(risk)) {
                tap(risk)
                settle()
                shot("24b-review-acknowledged")
            }
            tap(sign)
            val password = ui.tag("Wallet password")
            if (ui.waitForExistence(password, 5_000)) {
                settle()
                shot("25-authorize")
                setText(password, FIXTURE_PASSWORD)
                tap(ui.tag("authorize.sign"))
            }
            if (expect(ui.tag("signature.title"), 30_000)) {
                settle()
                shot("26-signature")
                ui.swipeUp()
                settle()
                shot("27-signature-bottom")
                tap(ui.tag("signature.done"))
            }
        }
        // A dApp approval: the summary names the spender from the bundled library.
        openTab("Scan")
        settle()
        if (ui.waitForExistence(ui.tag("signing.paste"), 5_000)) {
            click(ui.tag("signing.paste"))
            if (ui.waitForExistence(payload, 3_000)) {
                typePayload(APPROVE_KNOWN_SPENDER_REQUEST, into = payload)
                click(ui.tag("signing.decode"))
            }
            if (expect(ui.tag("signing.authorize"), 20_000)) {
                ui.waitForExistence(ui.tag("review.contractNotice"), 10_000)
                settle()
                shot("28-review-known-contract")
                ui.swipeUp()
                settle()
                shot("29-review-known-contract-warnings")
                click(ui.tag("signing.reject"))
                ui.waitForExistence(ui.tag("signing.scan"), 5_000)
            }
        }

        openTab("Activity")
        settle()
        shot("30-activity")
        val row = ui.tag("activity.row")
        if (expect(row, 5_000)) {
            click(row)
            ui.waitForExistence(ui.title("Signature"), 5_000)
            settle()
            shot("31-activity-detail")
            ui.swipeUp()
            settle()
            shot("31b-activity-input-output")
            if (ui.exists(ui.tag("activity.decoded"))) {
                tap(ui.tag("activity.decoded"))
                ui.waitForExistence(ui.title("Signed Request"), 10_000)
                settle()
                shot("31c-activity-signed-request")
                back()
                ui.waitForExistence(ui.title("Signature"), 5_000)
                ui.swipeUp()
            }
            if (ui.scrollToExistence(ui.tag("activity.showQR"))) {
                tap(ui.tag("activity.showQR"))
                ui.waitForExistence(ui.containing("Scanning it again signs nothing new"), 5_000)
                settle()
                shot("31d-activity-signature-qr")
                if (ui.exists(ui.labeled("Done"))) click(ui.labeled("Done"))
            }
            back()
        }

        openTab("Settings")
        settle()
        shot("40-settings")
        ui.swipeUp()
        settle()
        shot("41-settings-bottom")
        ui.swipeDown()
        val directory = "Networks, Tokens & Contracts"
        tap(ui.labeled(directory))
        ui.waitForExistence(ui.title(directory), 5_000)
        settle()
        shot("42-networks")
        for ((network, name) in listOf("ethereum" to "43-tokens-ethereum", "solana" to "44-tokens-solana", "tron" to "44b-tokens-tron")) {
            tap(ui.tag("network.$network"))
            ui.waitForExistence(ui.tag("reference.token"), 5_000)
            settle()
            shot(name)
            back()
            ui.waitForExistence(ui.title(directory), 5_000)
        }
        // The same page browses the known-contract library.
        tap(ui.tag("network.ethereum"))
        val contracts = ui.tag("reference.contracts")
        if (ui.waitForExistence(contracts, 5_000)) {
            click(contracts)
            ui.waitForExistence(ui.tag("reference.contract"), 5_000)
            settle()
            shot("44c-contracts-ethereum")
        }
        back()
        ui.waitForExistence(ui.title(directory), 5_000)
        ui.swipeUp()
        settle()
        shot("44d-contracts-section")
        if (ui.waitForExistence(ui.tag("contracts.everyChain"), 3_000)) {
            tap(ui.tag("contracts.everyChain"))
            ui.waitForExistence(ui.title("All EVM networks"), 5_000)
            settle()
            shot("44e-contracts-every-chain")
            back()
        }
        ui.swipeDown()
        back()
        tap(ui.labeled("Compatibility"))
        ui.waitForExistence(ui.title("Compatibility"), 5_000)
        settle()
        shot("45-compatibility")
        ui.swipeUp()
        settle()
        shot("46-compatibility-bottom")
        back()
        if (ui.waitForExistence(ui.tag("settings.openSource"), 3_000) || ui.scrollToExistence(ui.tag("settings.openSource"))) {
            tap(ui.tag("settings.openSource"))
            ui.waitForExistence(ui.title("Open Source"), 5_000)
            settle()
            shot("46a-open-source")
            back()
        }
        tap(ui.labeled("Terms of Use"))
        ui.waitForExistence(ui.containing("Version 1.1"), 5_000)
        settle()
        shot("46b-terms-of-use")
        scrollToContact()
        shot("46b2-terms-of-use-bottom")
        back()
        tap(ui.labeled("Privacy Policy"))
        ui.waitForExistence(ui.containing("collects nothing"), 5_000)
        settle()
        shot("46c-privacy-policy")
        scrollToContact()
        shot("46c2-privacy-policy-bottom")
        back()
        app.forceStop()
        return run
    }

    // MARK: App Lock (TOUR:271-281)

    private fun lockScreens(dark: Boolean, run: String) {
        app.launch(run = run, reset = false, appLock = true, dark = dark, allowCapture = true, locale = Args.locale, waitUntilReady = false)
        if (!expect(ui.tag("appLock.usePassword"), 15_000)) return
        settle()
        shot("50-app-lock")
        click(ui.tag("appLock.usePassword"))
        ui.waitForExistence(ui.tag("Wallet password"), 3_000)
        settle()
        shot("51-app-lock-password")
        app.forceStop()
    }

    // MARK: Helpers

    private fun launch(fixtures: Int, dark: Boolean) {
        app.launch(fixtures = fixtures, dark = dark, allowCapture = true, locale = Args.locale)
    }

    /** Lets the screen finish what it is doing (no animations run: the device setup turned them off). */
    private fun settle() {
        ui.device.waitForIdle(2_000)
    }

    private fun shot(name: String) {
        Shots.take("$prefix-$name")
        Log.i(TAG, "shot $prefix-$name")
    }

    /** iOS `tap(_:_:)` of the tour: a missing element is recorded, not fatal. */
    private fun tap(target: Target) = soft("tap $target") { ui.tap(target) }

    private fun expect(target: Target, timeoutMs: Long): Boolean {
        val found = ui.waitForExistence(target, timeoutMs)
        if (!found) problems += "Missing $target after $timeoutMs ms"
        return found
    }

    private fun back() {
        // A page that just closed (Cancel) may still be leaving: wait for the arrow of the page below.
        if (ui.waitForExistence(ui.tag(Ui.BACK_TAG), 3_000)) soft("back") { ui.goBack() } else problems += "No back arrow"
        ui.device.waitForIdle(2_000)
    }

    /** A click that, like the iOS tour's taps, records a missing element instead of ending the tour. */
    private fun click(target: Target) = soft("click $target") { ui.click(target) }

    private fun setText(target: Target, text: String) = soft("type into $target") { ui.setText(target, text) }

    private fun typePayload(text: String, into: Target) = soft("type the payload into $into") { ui.typePayload(text, into) }

    private fun soft(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            problems += "$what: ${e.message?.substringBefore("\n")}"
            // The screen of the problem (redacted), for the test's logcat.
            e.message?.substringAfter("--- screen ---\n", "")?.lines()?.chunked(40)?.forEach { Log.w(TAG, it.joinToString("\n")) }
        }
    }

    /** iPhone shows a tab bar; the same on Android: the bottom navigation's `tab.<name>`. */
    private fun openTab(name: String) = soft("open the $name tab") { ui.selectTab(name) }

    /** The keyboard's action (Next) on [field], as the IME's action key sends it. */
    private fun imeAction(field: Target) {
        val node = field.locate(ui)?.accessibilityNodeInfo
        val done = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        if (!done) ui.device.pressEnter()
        ui.device.waitForIdle(2_000)
    }

    /** Both legal documents end with the contact address; scrolling there also shows the last block clears the bars. */
    private fun scrollToContact() {
        val contact = ui.labeled("https://bittool.com/signer")
        repeat(25) { if (!ui.isHittable(contact)) ui.swipeUp() }
        if (!ui.isHittable(contact)) problems += "The contact address should be readable at the end of the page."
        settle()
    }

    companion object {
        private const val TAG = "SignerE2E"

        @get:ClassRule
        @JvmStatic
        val deviceSetup = DeviceSetupRule()
    }
}
