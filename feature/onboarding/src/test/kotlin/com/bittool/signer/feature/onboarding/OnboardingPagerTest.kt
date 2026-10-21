package com.bittool.signer.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupIntent
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The introduction (iOS `OnboardingView`; the UI-test flow UIT:23-56, gaps.md G1.1): three pages, "Page n of 3" on the
 * indicator, no "Skip", Continue → Continue → Get Started, Back after the first page, Cancel, system Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class OnboardingPagerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var completed = 0
    private var cancelled = 0

    private fun show() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                OnboardingPager(intent = SetupIntent.Restore, onComplete = { completed++ }, onCancel = { cancelled++ })
            }
        }
    }

    private fun indicatorSays(page: Int) =
        compose.onNodeWithTag(OnboardingTags.INDICATOR).assertContentDescriptionEquals("Page $page of 3")

    private fun next() {
        compose.onNodeWithTag(OnboardingTags.NEXT).performClick()
        compose.waitForIdle()
    }

    @Test
    fun threePagesEndInGetStartedWithoutSkip() {
        show()
        compose.onNodeWithText("Offline by Design").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.BACK).assertDoesNotExist()
        indicatorSays(1)
        compose.onNodeWithText("Continue").assertIsDisplayed()

        next()
        indicatorSays(2)
        compose.onNodeWithText("Built for Compatibility").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.BACK).assertIsDisplayed()

        next()
        indicatorSays(3)
        compose.onNodeWithText("Prepare Your Device").assertIsDisplayed()
        compose.onNodeWithText("Get Started").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        assertEquals(0, completed)

        next()
        assertEquals(1, completed)
        assertEquals(0, cancelled)
    }

    @Test
    fun theThirdPageUsesTheAndroidWording() {
        show()
        next()
        next()
        compose.onNodeWithText("For the strongest isolation, use a dedicated phone and keep its connections off.").assertIsDisplayed()
        compose.onNodeWithText("BitTool Signer has no internet permission, so Android blocks every connection").assertExists()
        compose.onNodeWithText("iPhone", substring = true).assertDoesNotExist()
        compose.onNodeWithText("If iOS asks", substring = true).assertDoesNotExist()
    }

    @Test
    fun backReturnsAPageAndCancelEndsTheIntroduction() {
        show()
        next()
        compose.onNodeWithTag(OnboardingTags.BACK).performClick()
        compose.waitForIdle()
        indicatorSays(1)
        compose.onNodeWithTag(OnboardingTags.CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(0, completed)
    }

    @Test
    fun systemBackGoesToThePreviousPageThenCancels() {
        show()
        next()
        next()
        systemBack()
        indicatorSays(2)
        systemBack()
        indicatorSays(1)
        assertEquals(0, cancelled)
        systemBack()
        assertEquals(1, cancelled)
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** QA setup-15: the large page titles break into balanced lines, at phrase boundaries in Japanese on API 33+. */
    @Test
    fun pageTitlesUseHeadingLineBreaks() {
        show()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Offline by Design").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals(LineBreak.Heading, results.single().layoutInput.style.lineBreak)
    }
}
