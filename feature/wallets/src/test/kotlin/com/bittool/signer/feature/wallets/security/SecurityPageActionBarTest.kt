package com.bittool.signer.feature.wallets.security

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA round 3 production-final-01: on the security pages with a password field, the action stays above the keyboard
 * while the person types, like setup's Reset Password (bottom action bar), and the field being typed in stays above
 * the action. Before, "Change Password" was laid out in the form under the requirements, entirely behind the keyboard
 * once the confirmation had the focus, and a tap where it was reported typed a character into the confirmation. What
 * belongs with the field (Change Password's requirements, Repair's footer) stays in view with it, as on setup's
 * Password step (QA setup-02), whenever it fits between the bars.
 *
 * mdpi, so 1 px = 1 dp: a 48 dp navigation bar and a 300 dp keyboard (which reaches the bottom of the screen). Positions
 * are the nodes' unclipped ones: a clipped bound would end at the scroll viewport and hide a button below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h640dp-mdpi")
class SecurityPageActionBarTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val identity = WalletFixtures.state().let { WalletIdentity.of(it, WalletFixtures.readyId) }

    private fun page(body: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
        }
        compose.waitForIdle()
    }

    private fun showKeyboard() {
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, KEYBOARD))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
    }

    private val SemanticsNodeInteraction.node: SemanticsNode get() = fetchSemanticsNode()
    private val SemanticsNode.top: Float get() = positionInRoot.y
    private val SemanticsNode.bottom: Float get() = positionInRoot.y + size.height

    /** The keyboard's top edge (px = dp at mdpi). */
    private fun keyboardTop(): Float = compose.onRoot().node.size.height - KEYBOARD.toFloat()

    /** [field] has the focus, sits under the top bar and ends above the action, which ends above the keyboard. */
    private fun assertTypingWithTheActionInView(field: SemanticsNodeInteraction) {
        field.assertIsFocused()
        val action = compose.onNodeWithTag("vaultManagement.submit").node
        val typed = field.node
        val keyboardTop = keyboardTop()
        assertTrue(action.bottom <= keyboardTop, "the action ends above the keyboard (${action.bottom} > $keyboardTop)")
        assertTrue(action.top >= typed.bottom, "the field being typed in stays above the action (${typed.bottom} > ${action.top})")
        val barBottom = compose.onNodeWithTag("nav.close").getBoundsInRoot().bottom.value
        assertTrue(typed.top >= barBottom, "the field being typed in is not under the top bar (${typed.top} < $barBottom)")
    }

    /**
     * [shown] is in the visible band of the page: under the top bar, and ending above the bottom bar's top edge (8 dp
     * above its action; no failure is shown in these tests).
     */
    private fun assertBetweenTheBars(shown: SemanticsNodeInteraction, what: String) {
        val node = shown.node
        val bottomBarTop = compose.onNodeWithTag("vaultManagement.submit").node.top - 8f
        assertTrue(node.bottom <= bottomBarTop, "$what ends above the bottom bar (${node.bottom} > $bottomBarTop)")
        val topBarBottom = compose.onNodeWithTag("nav.close").getBoundsInRoot().bottom.value
        assertTrue(node.top >= topBarBottom, "$what is not under the top bar (${node.top} < $topBarBottom)")
    }

    private fun changePasswordPage() = page {
        ChangePasswordContent(
            uiState = SecurityPageUiState(identity = identity),
            fields = remember { PasswordFields(3) },
            toast = rememberStatusToastState(),
            onSubmit = {},
            onCancel = {},
        )
    }

    private fun repairPage() = page {
        RepairWalletContent(
            uiState = SecurityPageUiState(identity = identity),
            fields = remember { PasswordFields(1) },
            toast = rememberStatusToastState(),
            onSubmit = {},
            onCancel = {},
        )
    }

    @Test
    fun changePasswordStaysAboveTheKeyboardWhileTheConfirmationIsTyped() {
        changePasswordPage()
        compose.onNodeWithTag(PasswordKind.CurrentWalletPassword.english).performTextInput("old password 1")
        compose.onNodeWithTag(PasswordKind.NewPassword.english).performTextInput("Public-Test-Password1!")
        val confirmation = compose.onNodeWithTag(PasswordKind.ConfirmNewPassword.english)
        confirmation.performTextInput("Public-Test-Password1!")
        showKeyboard()

        assertTypingWithTheActionInView(confirmation)
    }

    /**
     * The new password, its confirmation and the live requirements stay in view together while the confirmation is
     * typed, as on setup's Password step (QA setup-02: "the requirements checklist is hidden while typing"). With the
     * action moved into the bar and only the focused field brought into view, "Passwords match" stayed under the bar
     * once the keyboard was up (it ended at 562 dp, the bar's edge is at 425 dp); on the QA device the inline layout
     * still showed it just above the keyboard (`f41a`: 1295-1345 px against the keyboard at 1403 px).
     */
    @Test
    @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun changePasswordKeepsTheRequirementsInViewWhileTheConfirmationIsTyped() {
        changePasswordPage()
        compose.onNodeWithTag(PasswordKind.CurrentWalletPassword.english).performTextInput("old password 1")
        compose.onNodeWithTag(PasswordKind.NewPassword.english).performTextInput("Public-Test-Password1!")
        val confirmation = compose.onNodeWithTag(PasswordKind.ConfirmNewPassword.english)
        confirmation.performTextInput("Public-Test-Password1!")
        showKeyboard()

        assertTypingWithTheActionInView(confirmation)
        assertBetweenTheBars(compose.onNodeWithTag(PasswordKind.NewPassword.english), "the new password")
        assertBetweenTheBars(compose.onNodeWithText("Passwords match"), "the last requirement")
    }

    /**
     * On a window too short for the pair and its checklist above the bar, the field being typed in wins: the new
     * password stays below the top bar and above the action. The scroll viewport starts under the top bar, so the
     * block never counts as fitting by scrolling the focused field under that bar (with the viewport reaching the top
     * of the screen the new password ended at y 0, behind the top bar).
     */
    @Test
    fun changePasswordKeepsTheNewPasswordInViewOnAShortWindow() {
        changePasswordPage()
        compose.onNodeWithTag(PasswordKind.CurrentWalletPassword.english).performTextInput("old password 1")
        val new = compose.onNodeWithTag(PasswordKind.NewPassword.english)
        new.performTextInput("Public-Test-Password1!")
        showKeyboard()

        assertTypingWithTheActionInView(new)
    }

    @Test
    fun repairStaysAboveTheKeyboardWhileThePasswordIsTyped() {
        repairPage()
        val password = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        password.performTextInput("correct horse")
        showKeyboard()

        assertTypingWithTheActionInView(password)
    }

    /**
     * Repair's footer (what the repair rebuilds and what it leaves alone, the iOS section footer of the password)
     * stays in view with the password while it is typed. With the action in the bar and only the field brought into
     * view, the footer ended at 296 dp under the bar's edge at 265 dp.
     */
    @Test
    fun repairKeepsItsFooterInViewWhileThePasswordIsTyped() {
        repairPage()
        val password = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        password.performTextInput("correct horse")
        showKeyboard()

        assertTypingWithTheActionInView(password)
        val footer = compose.onNodeWithText("BitTool Signer decrypts the wallet once", substring = true)
        assertBetweenTheBars(footer, "the footer")
    }

    /**
     * Scrolled to its end, the form ends its bottom margin above the bar, which rises exactly with the keyboard: the
     * navigation bar inside the keyboard is not counted twice, and nothing of the form stays hidden under the bar. The
     * page's toast (the overlay-blocked notice of the bar's button) appears above the bar, not over the button.
     */
    @Test
    @Config(qualifiers = "w400dp-h800dp-mdpi")
    fun theFormEndsAboveTheBarThatRisesWithTheKeyboard() {
        val toast = StatusToastState()
        page {
            SecurityPage(
                title = "Repair Wallet",
                busy = false,
                onCancel = {},
                toast = toast,
                bottomActions = { Box(Modifier.fillMaxWidth().height(ACTION.dp).testTag("action")) },
            ) {
                Box(Modifier.fillMaxWidth().height(3_000.dp).testTag("form"))
            }
        }
        showKeyboard()
        compose.onNodeWithTag("form").performScrollTo()
        repeat(12) { compose.onNodeWithTag("form").performTouchInput { swipeUp() } }
        compose.waitForIdle()

        val action = compose.onNodeWithTag("action").node
        // BottomActionBar: 8 dp above and below its content, then the keyboard (which covers the navigation bar).
        assertEquals(keyboardTop() - 8f, action.bottom)
        assertEquals(action.top - 8f - BAR_FORM_BOTTOM_MARGIN.value, compose.onNodeWithTag("form").node.bottom)

        // The capsule 24 dp above the bar's top edge (its text node sits inside the capsule's 10 dp vertical padding).
        // The clock is held so the toast's 1.6 s timer does not remove it.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            toast.show("Blocked")
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(800)
        assertEquals(action.top - 8f - 24f - 10f, compose.onNodeWithText("Blocked").node.bottom)
    }

    private companion object {
        const val NAV_BAR = 48
        const val KEYBOARD = 300
        const val ACTION = 52
    }
}
