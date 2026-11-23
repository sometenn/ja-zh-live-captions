package com.sometenn.callcaptions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sometenn.callcaptions.ui.home.HomeRoute
import com.sometenn.callcaptions.ui.home.HomeViewModel
import com.sometenn.callcaptions.ui.theme.CallCaptionsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        val container = (application as CallCaptionsApplication).container
        HomeViewModel.factory(
            session = container.session,
            overlay = container.overlay,
            consentCoordinator = container.consentCoordinator,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            CallCaptionsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeRoute(viewModel = viewModel)
                }
            }
        }
    }
}
