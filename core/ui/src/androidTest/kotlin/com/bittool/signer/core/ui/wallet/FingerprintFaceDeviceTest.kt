package com.bittool.signer.core.ui.wallet

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bittool.signer.core.designsystem.theme.SignerTypography
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.monospacedRun
import com.bittool.signer.core.l10n.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA regression-10 on the device's own fonts and text stack: in Arabic the "Fingerprint" line ("البصمة ⁨F7FB19CD⁩")
 * draws the value in the monospaced face, glyph for glyph as the value drawn alone in that face, and not in the
 * default face. It holds for the line as `fingerprintLine` builds it (the family also on the value) and for the
 * sentence drawn in a monospaced style alone, which is how the QA build drew it: the screenshot that was read as a
 * proportional face shows the value on exactly these cells. (Robolectric's JVM fonts agree; this checks the platform.)
 *
 * Headless: the lines are laid out and drawn into bitmaps with Compose's `Paragraph`, without an activity, a window or
 * any input, so it is safe on a shared or locked device and runs with a plain `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintFaceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = createFontFamilyResolver(context)
    private val density = Density(context)
    private val value = "F7FB19CD"

    /** The caption style at a size where every glyph edge is clear; the face does not depend on the size. */
    private val caption = SignerTypography.Default.caption.copy(fontSize = 40.sp, lineHeight = 50.sp)

    private fun arabicSentence(): String {
        val arabic = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) }
        return context.createConfigurationContext(arabic).getString(R.string.fingerprint_x, isolate(value))
    }

    /** Where the value's glyphs are drawn: the ink runs of its columns, relative to its first inked column. */
    private fun glyphRuns(text: AnnotatedString, style: TextStyle): List<IntRange> {
        val paragraph = Paragraph(
            text = text.text,
            style = style,
            spanStyles = text.spanStyles,
            constraints = Constraints(maxWidth = 2_000),
            density = density,
            fontFamilyResolver = resolver,
        )
        val bitmap = Bitmap.createBitmap(ceil(paragraph.width).toInt(), ceil(paragraph.height).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        paragraph.paint(Canvas(bitmap.asImageBitmap()), color = Color.Black)
        val start = text.text.indexOf(value)
        val boxes = (start until start + value.length).map(paragraph::getBoundingBox)
        val left = floor(boxes.minOf { it.left }).toInt().coerceAtLeast(0)
        val right = ceil(boxes.maxOf { it.right }).toInt().coerceAtMost(bitmap.width)
        val inked = (left until right).map { x ->
            (0 until bitmap.height).sumOf { y -> 255 - android.graphics.Color.red(bitmap.getPixel(x, y)) } > INK_THRESHOLD
        }
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < inked.size) {
            if (inked[x]) {
                val runStart = x
                while (x < inked.size && inked[x]) x++
                runs += runStart until x
            } else {
                x++
            }
        }
        val origin = runs.first().first
        return runs.map { (it.first - origin) until (it.last + 1 - origin) }
    }

    /** How many glyph edges of [actual] are more than [TOLERANCE_PX] away from [expected]'s (all of them if the counts differ). */
    private fun misplacedEdges(actual: List<IntRange>, expected: List<IntRange>): Int {
        if (actual.size != expected.size) return 2 * maxOf(actual.size, expected.size)
        return actual.zip(expected).sumOf { (a, e) ->
            (if (abs(a.first - e.first) > TOLERANCE_PX) 1 else 0) + (if (abs(a.last - e.last) > TOLERANCE_PX) 1 else 0)
        }
    }

    @Test
    fun theArabicFingerprintValueIsDrawnInTheMonospacedFace() {
        val sentence = arabicSentence()
        assertTrue("the Arabic catalogue string embeds the value: $sentence", sentence.contains(value) && sentence.first() != 'F')
        val rtl = caption.copy(textDirection = TextDirection.Rtl, localeList = LocaleList("ar-u-nu-latn"))
        val ltr = caption.copy(textDirection = TextDirection.Ltr)
        val monospacedCells = glyphRuns(AnnotatedString(value), ltr.monospaced())
        val defaultCells = glyphRuns(AnnotatedString(value), ltr.copy(fontFamily = FontFamily.Default))
        assertTrue(
            "the check tells the two faces apart on this device: $monospacedCells / $defaultCells",
            misplacedEdges(defaultCells, monospacedCells) >= 3,
        )

        // What fingerprintLine draws: the family on the value as well as on the sentence.
        assertEquals(0, misplacedEdges(glyphRuns(monospacedRun(sentence, value), rtl.monospaced()), monospacedCells))
        // The sentence in a monospaced style alone, as the QA build drew it.
        assertEquals(0, misplacedEdges(glyphRuns(AnnotatedString(sentence), rtl.monospaced()), monospacedCells))
    }

    private companion object {
        /** Summed darkness (0–255 per pixel) above which a column counts as inked: antialiasing fringes stay out. */
        const val INK_THRESHOLD = 120

        /** Sub-pixel positions move an edge by a pixel; the two faces differ by more for several glyphs. */
        const val TOLERANCE_PX = 1
    }
}
