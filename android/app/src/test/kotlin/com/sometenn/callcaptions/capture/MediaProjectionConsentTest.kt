package com.sometenn.callcaptions.capture

import android.app.Activity
import android.content.Intent
import com.sometenn.callcaptions.core.InMemoryPreferencesStore
import com.sometenn.callcaptions.core.Session
import com.sometenn.callcaptions.ui.home.HomeViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaProjectionConsentTest {

    @Test
    fun requestGrantDenyAndCancelFlow() = runTest(StandardTestDispatcher()) {
        val coordinator = MediaProjectionConsentCoordinator()
        assertNull(coordinator.pendingRequestId.value)

        // Deny flow via HomeViewModel
        val session = Session(scope = this, prefs = InMemoryPreferencesStore(), enableTicker = false)
        val vm = HomeViewModel.forTesting(session = session, consentCoordinator = coordinator)

        val d1 = coordinator.requestConsent()
        assertNotNull(vm.mediaProjectionRequestId.value)
        vm.onMediaProjectionResult(Activity.RESULT_CANCELED, null)
        assertNull(vm.mediaProjectionRequestId.value)
        assertNull(d1.await())

        // Grant flow
        val d2 = coordinator.requestConsent()
        val id2 = vm.mediaProjectionRequestId.value
        assertNotNull(id2)
        val fakeIntent = Intent()
        coordinator.onConsentGranted(Activity.RESULT_OK, fakeIntent)
        assertNull(vm.mediaProjectionRequestId.value)
        val result = d2.await()
        assertNotNull(result)
        assertEquals(Activity.RESULT_OK, result!!.resultCode)
        assertEquals(fakeIntent, result.data)

        // Cancel pending flow
        val d3 = coordinator.requestConsent()
        assertNotNull(coordinator.pendingRequestId.value)
        coordinator.cancelPending()
        assertNull(coordinator.pendingRequestId.value)
        assertTrue(d3.isCancelled)
        var cancelled = false
        try {
            d3.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
