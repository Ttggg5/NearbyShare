package com.nearbyshare.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1976D2)
private val BlueDark = Color(0xFF90CAF9)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Blue,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    secondary = BlueDark,
)

@Composable
fun NearbyShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
