package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ObsidianColorScheme = darkColorScheme(
    primary = ObsidianPrimary,
    primaryContainer = Color(0xFF30365B),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondaryContainer = Color(0xFF30365B),
    onSecondaryContainer = Color(0xFFDCE1FF),
    background = ObsidianBackground,
    surface = ObsidianSurface,
    surfaceVariant = ObsidianSurfaceVariant,
    error = ObsidianError,
    onPrimary = ObsidianBackground,
    onBackground = ObsidianTextPrimary,
    onSurface = ObsidianTextPrimary,
    onSurfaceVariant = ObsidianTextSecondary,
    onError = ObsidianTextPrimary,
    tertiary = ObsidianSuccess,
    outline = ObsidianBorder
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianColorScheme,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}
