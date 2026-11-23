package com.sometenn.callcaptions.ui.home

import com.sometenn.callcaptions.core.AudioSource
import com.sometenn.callcaptions.core.CaptionDisplayMode
import com.sometenn.callcaptions.core.InMemoryKeyVault
import com.sometenn.callcaptions.core.InMemoryPreferencesStore
import com.sometenn.callcaptions.core.Provider
import com.sometenn.callcaptions.core.RecordingOverlaySink
import com.sometenn.callcaptions.core.Session
import com.sometenn.callcaptions.core.StubAudioCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Test
    fun saveAndRemoveKeyUpdatesHasKeyPerProvider() = runTest(StandardTestDispatcher()) {
        val vault = InMemoryKeyVault()
        val session = Session(
            scope = this,
            prefs = InMemoryPreferencesStore(),
            readKey = vault::read,
            writeKey = vault::save,
            enableTicker = false,
        )
        val vm = HomeViewModel.forTesting(session)

        assertEquals(Provider.SONIOX, vm.sessionState.value.provider)
        assertFalse(vm.sessionState.value.hasKey)

        assertTrue(vm.saveKey("  soniox-secret  ").isSuccess)
        assertTrue(vm.sessionState.value.hasKey)
        assertEquals("soniox-secret", vault.read(Provider.SONIOX))

        vm.selectProvider(Provider.GEMINI)
        assertEquals(Provider.GEMINI, vm.sessionState.value.provider)
        assertFalse(vm.sessionState.value.hasKey)

        assertTrue(vm.saveKey("gemini-secret").isSuccess)
        assertTrue(vm.sessionState.value.hasKey)

        assertTrue(vm.saveKey("").isSuccess)
        assertFalse(vm.sessionState.value.hasKey)

        vm.selectProvider(Provider.SONIOX)
        assertTrue(vm.sessionState.value.hasKey)
    }

    @Test
    fun sourceDisplayModeAndOverlayActionsDelegateCleanly() = runTest(StandardTestDispatcher()) {
        val overlay = RecordingOverlaySink()
        val session = Session(
            scope = this,
            prefs = InMemoryPreferencesStore(),
            overlay = overlay,
            makeCapture = {
                StubAudioCapture("机内声音采集将在阶段 3（Issue #3）接入，当前请先切换为「麦克风」进行测试或翻译。")
            },
            enableTicker = false,
        )
        val vm = HomeViewModel.forTesting(session, overlaySink = overlay)

        vm.selectDisplayMode(CaptionDisplayMode.CHINESE_ONLY)
        assertEquals(CaptionDisplayMode.CHINESE_ONLY, vm.sessionState.value.displayMode)

        vm.selectSource(AudioSource.MICROPHONE)
        assertEquals(AudioSource.MICROPHONE, vm.sessionState.value.source)

        vm.startOverlay()
        assertTrue(overlay.active)
        vm.stopOverlay()
        assertFalse(overlay.active)

        // System source in Stage 2 returns clear Stage 3 placeholder message and returns to IDLE cleanly
        vm.selectSource(AudioSource.SYSTEM)
        vm.beginProbe()
        runCurrent()
        assertEquals(Session.Stage.IDLE, vm.sessionState.value.stage)
        assertTrue(vm.sessionState.value.status.contains("阶段 3"))
    }
}
