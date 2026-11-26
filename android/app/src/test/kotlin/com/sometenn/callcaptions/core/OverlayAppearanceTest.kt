package com.sometenn.callcaptions.core

import com.sometenn.callcaptions.ui.home.HomeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayAppearanceTest {

    @Test
    fun sanitizeClampsAndRoundsOutOfRangeValues() {
        val low = OverlayAppearance(
            fontScale = 0.1f,
            widthPercent = 10,
            maxLines = 0,
            backgroundAlphaPercent = 5,
        ).sanitized()
        assertEquals(OverlayAppearance.MIN_FONT_SCALE, low.fontScale, 0.0001f)
        assertEquals(OverlayAppearance.MIN_WIDTH_PERCENT, low.widthPercent)
        assertEquals(OverlayAppearance.MIN_LINES, low.maxLines)
        assertEquals(OverlayAppearance.MIN_BG_ALPHA_PERCENT, low.backgroundAlphaPercent)

        val high = OverlayAppearance(
            fontScale = 3.0f,
            widthPercent = 200,
            maxLines = 20,
            backgroundAlphaPercent = 150,
        ).sanitized()
        assertEquals(OverlayAppearance.MAX_FONT_SCALE, high.fontScale, 0.0001f)
        assertEquals(OverlayAppearance.MAX_WIDTH_PERCENT, high.widthPercent)
        assertEquals(OverlayAppearance.MAX_LINES, high.maxLines)
        assertEquals(OverlayAppearance.MAX_BG_ALPHA_PERCENT, high.backgroundAlphaPercent)
    }

    @Test
    fun persistenceRoundTripAndCharBudgetScaling() {
        val prefs = InMemoryPreferencesStore()
        assertEquals(OverlayAppearance.DEFAULT, OverlayAppearance.load(prefs))

        val custom = OverlayAppearance(
            fontScale = 1.35f,
            widthPercent = 80,
            maxLines = 3,
            backgroundAlphaPercent = 65,
            sourceSize = 19,
            translationSize = 28,
            face = CaptionFace.SERIF,
            ink = CaptionInk.MINT,
        )
        custom.save(prefs)
        val loaded = OverlayAppearance.load(prefs)
        assertEquals(custom.sanitized(), loaded)

        // Larger font shrinks per-line budget; more lines expands budget
        val smallFont = OverlayAppearance(fontScale = 0.85f, widthPercent = 100, maxLines = 3)
        val largeFont = OverlayAppearance(fontScale = 1.50f, widthPercent = 65, maxLines = 1)
        assertTrue(smallFont.charBudget(isChineseOnly = true) > largeFont.charBudget(isChineseOnly = true))
    }

    @Test
    fun homeViewModelUpdatesAndResetsAppearance() = runTest(StandardTestDispatcher()) {
        val session = Session(scope = this, prefs = InMemoryPreferencesStore(), enableTicker = false)
        val vm = HomeViewModel.forTesting(session)

        assertEquals(OverlayAppearance.DEFAULT, vm.overlayState.value.appearance)

        val custom = OverlayAppearance(fontScale = 1.25f, widthPercent = 76, maxLines = 3, backgroundAlphaPercent = 70)
        vm.updateAppearance(custom)
        assertEquals(custom.sanitized(), vm.overlayState.value.appearance)

        vm.resetAppearance()
        assertEquals(OverlayAppearance.DEFAULT, vm.overlayState.value.appearance)
    }
}
