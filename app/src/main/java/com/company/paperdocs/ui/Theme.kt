package com.company.paperdocs.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val DocsBlue = Color(0xFF1A73E8)
val DocsInk = Color(0xFF202124)
val DocsMuted = Color(0xFF68717D)
val DocsBackground = Color(0xFFF8FAFD)
val DocsLine = Color(0xFFE3E7ED)
val DocsGreen = Color(0xFF188038)

@Composable
fun PaperDocsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = DocsBlue, onPrimary = Color.White,
            primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF174EA6),
            secondaryContainer = Color(0xFFE8F0FE), onSecondaryContainer = Color(0xFF174EA6),
            background = DocsBackground, onBackground = DocsInk,
            surface = Color.White, onSurface = DocsInk, onSurfaceVariant = DocsMuted,
            surfaceVariant = Color(0xFFF0F4F9), outlineVariant = DocsLine,
        ),
        typography = Typography().let { it.copy(
            headlineLarge = it.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
            headlineMedium = it.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        ) },
        content = content,
    )
}
