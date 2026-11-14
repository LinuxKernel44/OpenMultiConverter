package io.github.linuxkernel44.openmulticonverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.linuxkernel44.openmulticonverter.ui.OmcTheme
import io.github.linuxkernel44.openmulticonverter.ui.OmcRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OmcTheme { OmcRoot() } }
    }
}
