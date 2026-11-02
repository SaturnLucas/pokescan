package com.pokescan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PokeYellow = Color(0xFFFFCB05)
val PokeGreen = Color(0xFF4ADE80)

@Composable
fun PokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PokeYellow,
            onPrimary = Color(0xFF1A1A1A),
            secondary = Color(0xFF5B6CFF),
            onSecondary = Color.White,
            tertiary = Color(0xFFFF5A5A),
            background = Color(0xFF0F1220),
            onBackground = Color(0xFFEDEFFA),
            surface = Color(0xFF181C30),
            onSurface = Color(0xFFEDEFFA),
            surfaceVariant = Color(0xFF242A47),
            onSurfaceVariant = Color(0xFFC9CDE6),
            surfaceTint = Color.Transparent,
            error = Color(0xFFFF6B6B)
        ),
        content = content
    )
}
