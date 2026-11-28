package com.sometenn.callcaptions.ui.home

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sometenn.callcaptions.capture.MediaProjectionConsentCoordinator
import com.sometenn.callcaptions.core.AudioSource
import com.sometenn.callcaptions.core.CaptionDisplayMode
import com.sometenn.callcaptions.core.OverlayAppearance
import com.sometenn.callcaptions.core.OverlaySink
import com.sometenn.callcaptions.core.Provider
import com.sometenn.callcaptions.core.Session
import com.sometenn.callcaptions.core.SessionState
import com.sometenn.callcaptions.overlay.AndroidOverlayController
import com.sometenn.callcaptions.overlay.OverlayUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(
    private val session: Session,
    private val overlaySink: OverlaySink,
    val overlayState: StateFlow<OverlayUiState>,
    private val consentCoordinator: MediaProjectionConsentCoordinator = MediaProjectionConsentCoordinator(),
    private val updateAppearanceAction: (OverlayAppearance) -> Unit = {},
    private val resetAppearanceAction: () -> Unit = {},
    private val clearOverlayNoticeAction: () -> Unit = {},
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = session.state
    val mediaProjectionRequestId: StateFlow<Long?> = consentCoordinator.pendingRequestId

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            consentCoordinator.onConsentGranted(resultCode, data)
        } else {
            consentCoordinator.onConsentDenied()
        }
    }

    fun selectProvider(provider: Provider) {
        session.select(provider)
    }

    fun selectSource(source: AudioSource) {
        session.selectSource(source)
    }

    fun selectDisplayMode(mode: CaptionDisplayMode) {
        session.selectDisplayMode(mode)
    }

    private var pendingExport: String? = null

    fun prepareExport() {
        pendingExport = session.transcriptText()
    }

    fun finishExport(uri: Uri?, resolver: ContentResolver, notify: (String) -> Unit) {
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null) return
        if (snapshot == null) {
            notify("记录已失效，请重新保存。")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开文件")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                }
                notify("记录已保存")
            } catch (error: IOException) {
                notify("保存失败：" + error.localizedMessage)
            } catch (error: SecurityException) {
                notify("无权写入所选位置，请重新选择。")
            }
        }
    }

    fun beginTranslate() {
        session.begin(Session.Mode.TRANSLATE)
    }

    fun beginProbe() {
        session.begin(Session.Mode.PROBE)
    }

    fun finish() {
        session.finish()
    }

    fun saveKey(value: String): Result<Unit> = runCatching {
        session.saveKey(value)
    }

    fun startOverlay() {
        overlaySink.start()
    }

    fun stopOverlay() {
        overlaySink.stop()
    }

    fun clearOverlayNotice() {
        clearOverlayNoticeAction()
    }

    fun updateAppearance(next: OverlayAppearance) {
        updateAppearanceAction(next)
    }

    fun resetAppearance() {
        resetAppearanceAction()
    }

    fun refreshPreviewIfIdle() {
        session.refreshPreviewIfIdle()
    }

    companion object {
        fun factory(
            session: Session,
            overlay: AndroidOverlayController,
            consentCoordinator: MediaProjectionConsentCoordinator,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    session = session,
                    overlaySink = overlay,
                    overlayState = overlay.uiState,
                    consentCoordinator = consentCoordinator,
                    updateAppearanceAction = { overlay.updateAppearance(it) },
                    resetAppearanceAction = { overlay.resetAppearance() },
                    clearOverlayNoticeAction = { overlay.clearNotice() },
                ) as T
            }
        }

        fun forTesting(
            session: Session,
            overlaySink: OverlaySink = session.overlay,
            overlayState: MutableStateFlow<OverlayUiState> = MutableStateFlow(OverlayUiState()),
            consentCoordinator: MediaProjectionConsentCoordinator = MediaProjectionConsentCoordinator(),
        ): HomeViewModel = HomeViewModel(
            session = session,
            overlaySink = overlaySink,
            overlayState = overlayState.asStateFlow(),
            consentCoordinator = consentCoordinator,
            updateAppearanceAction = { next ->
                overlayState.value = overlayState.value.copy(appearance = next.sanitized())
            },
            resetAppearanceAction = {
                overlayState.value = overlayState.value.copy(appearance = OverlayAppearance.DEFAULT)
            },
        )
    }
}
