package com.wennuanbiji

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WarmColorScheme = lightColorScheme(
    primary = Color(0xFFC75A45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCCF),
    onPrimaryContainer = Color(0xFF3E0B03),
    secondary = Color(0xFF8B6B4A),
    secondaryContainer = Color(0xFFFFDEB5),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF51443F)
)

@Composable
fun WarmNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WarmColorScheme,
        content = content
    )
}
