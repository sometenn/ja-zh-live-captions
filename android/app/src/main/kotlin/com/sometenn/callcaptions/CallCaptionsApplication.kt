package com.sometenn.callcaptions

import android.app.Application
import android.content.Context
import com.sometenn.callcaptions.capture.MediaProjectionConsentCoordinator
import com.sometenn.callcaptions.capture.MicrophoneAudioCapture
import com.sometenn.callcaptions.capture.SystemAudioCapture
import com.sometenn.callcaptions.core.AndroidKeystoreVault
import com.sometenn.callcaptions.core.AndroidPreferencesStore
import com.sometenn.callcaptions.core.AudioSource
import com.sometenn.callcaptions.core.Session
import com.sometenn.callcaptions.overlay.AndroidOverlayController
import kotlinx.coroutines.MainScope

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = MainScope()
    private val prefs = AndroidPreferencesStore(
        appContext.getSharedPreferences("callcaptions_prefs", Context.MODE_PRIVATE),
    )
    private val vault = AndroidKeystoreVault(appContext)
    val overlay = AndroidOverlayController(appContext)
    val consentCoordinator = MediaProjectionConsentCoordinator()

    val session = Session(
        scope = appScope,
        prefs = prefs,
        overlay = overlay,
        makeCapture = { source ->
            when (source) {
                AudioSource.MICROPHONE -> MicrophoneAudioCapture(appContext)
                AudioSource.SYSTEM -> SystemAudioCapture(appContext, consentCoordinator)
            }
        },
        readKey = { provider -> vault.read(provider) },
        writeKey = { value, provider -> vault.save(value, provider) },
    )
}

class CallCaptionsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
