package com.sometenn.callcaptions.capture

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaProjectionConsent(
    val resultCode: Int,
    val data: Intent,
)

/**
 * Coordinates the one-shot system MediaProjection consent dialog between
 * [SystemAudioCapture] and the active Activity/Compose host.
 */
class MediaProjectionConsentCoordinator {
    private val _pendingRequestId = MutableStateFlow<Long?>(null)
    val pendingRequestId: StateFlow<Long?> = _pendingRequestId.asStateFlow()

    private var nextId: Long = 0L
    private var pendingDeferred: CompletableDeferred<MediaProjectionConsent?>? = null

    fun requestConsent(): CompletableDeferred<MediaProjectionConsent?> {
        pendingDeferred?.cancel()
        val id = ++nextId
        val deferred = CompletableDeferred<MediaProjectionConsent?>()
        pendingDeferred = deferred
        _pendingRequestId.value = id
        return deferred
    }

    fun onConsentGranted(resultCode: Int, data: Intent) {
        val deferred = pendingDeferred
        pendingDeferred = null
        _pendingRequestId.value = null
        deferred?.complete(MediaProjectionConsent(resultCode, data))
    }

    fun onConsentDenied() {
        val deferred = pendingDeferred
        pendingDeferred = null
        _pendingRequestId.value = null
        deferred?.complete(null)
    }

    fun cancelPending() {
        val deferred = pendingDeferred
        pendingDeferred = null
        _pendingRequestId.value = null
        deferred?.cancel()
    }
}
