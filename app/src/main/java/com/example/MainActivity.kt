package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.PulseClipApp
import com.example.ui.theme.PulseClipTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            PulseClipTheme {
                PulseClipApp()
            }
        }
        (application as? PulseClipApplication)?.container?.embeddedPlayerManager?.attachHostWindow(window)
    }

    override fun onResume() {
        super.onResume()
        (application as? PulseClipApplication)?.container?.embeddedPlayerManager?.attachHostWindow(window)
    }
}
