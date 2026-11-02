package com.eventreminder.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF), onPrimaryContainer = Color(0xFF1B0C73),
    background = Color(0xFFFBF9FF), surface = Color(0xFFFBF9FF)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC7BFFF), onPrimary = Color(0xFF2C1A98),
    primaryContainer = Color(0xFF4331B8), onPrimaryContainer = Color(0xFFE5E0FF),
    background = Color(0xFF131218), surface = Color(0xFF131218)
)

@Composable
fun EventTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
