package com.graphite.app.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GraphiteBlack = Color(0xFF000000)
val GraphiteSurface = Color(0xFF121212)
val GraphiteSurfaceVariant = Color(0xFF1E1E1E)
val GraphiteSurfaceElevated = Color(0xFF262626)
val GraphiteOrange = Color(0xFFFF7A30)
val GraphiteOrangeVariant = Color(0xFFE65100)
val GraphiteOnSurface = Color(0xFFEDEDED)
val GraphiteOnSurfaceMuted = Color(0xFF9E9E9E)
val GraphiteOutline = Color(0xFF2E2E2E)

private val GraphiteDarkColorScheme = darkColorScheme(
    primary = GraphiteOrange,
    onPrimary = Color.Black,
    primaryContainer = GraphiteOrangeVariant,
    onPrimaryContainer = Color.White,
    secondary = GraphiteOrange,
    onSecondary = Color.Black,
    background = GraphiteBlack,
    onBackground = GraphiteOnSurface,
    surface = GraphiteSurface,
    onSurface = GraphiteOnSurface,
    surfaceVariant = GraphiteSurfaceVariant,
    onSurfaceVariant = GraphiteOnSurfaceMuted,
    outline = GraphiteOutline
)

@Composable
fun GraphiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GraphiteDarkColorScheme,
        content = content
    )
}
