package com.vanta.studio

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.vanta.studio.ui.VantaRoot
import com.vanta.studio.ui.theme.VantaTheme

/** AppCompatActivity so per-app language switching works on Android 12 and below. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VantaApp).container
        setContent {
            VantaTheme {
                VantaRoot(container)
            }
        }
    }
}
