package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

private val ObsidianColorScheme = darkColorScheme(
    primary = ObsidianPrimary,
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
        content = content
    )
}
