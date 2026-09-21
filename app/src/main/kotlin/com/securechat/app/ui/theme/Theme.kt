package com.securechat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = SecurePrimary,
    onPrimary = SecureOnPrimary,
    primaryContainer = SecurePrimaryContainer,
    secondary = SecureSecondary,
    background = SecureBackgroundDark,
    surface = SecureSurfaceDark,
    error = SecureError,
)

private val LightColors = lightColorScheme(
    primary = SecurePrimary,
    onPrimary = SecureOnPrimary,
    primaryContainer = SecurePrimaryContainer,
    secondary = SecureSecondary,
    background = SecureBackgroundLight,
    surface = SecureSurfaceLight,
    error = SecureError,
)

@Composable
fun SecureChatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = SecureChatTypography, content = content)
}
