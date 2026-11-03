package com.pokescan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PokeYellow = Color(0xFFE0B04A)   // dourado
val PokeGreen = Color(0xFF3DDC97)
val GoldLine = Color(0xFF3A301A)     // borda dourada escura
val PanelBg = Color(0xFF14120C)      // painéis (marrom quase preto)

@Composable
fun PokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PokeYellow,
            onPrimary = Color(0xFF1A1405),
            secondary = Color(0xFFC9962B),
            onSecondary = Color(0xFF1A1405),
            tertiary = Color(0xFFD9534F),
            background = Color(0xFF08080A),
            onBackground = Color(0xFFF2EFE8),
            surface = Color(0xFF111113),
            onSurface = Color(0xFFF2EFE8),
            surfaceVariant = Color(0xFF1C1912),
            onSurfaceVariant = Color(0xFFB9B2A0),
            outline = GoldLine,
            surfaceTint = Color.Transparent,
            error = Color(0xFFFF6B6B)
        ),
        content = content
    )
}
