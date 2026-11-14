package io.github.linuxkernel44.openmulticonverter.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary = Color(0xFF3A5BA9), secondary = Color(0xFF00796B))
private val Dark = darkColorScheme(primary = Color(0xFFB0C6FF), secondary = Color(0xFF4DB6AC))

@Composable
fun OmcTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> LocalContext.current.let { if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it) }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
