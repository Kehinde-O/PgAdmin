package dev.pgapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PgBlue = Color(0xFF336791)
val Mono = FontFamily.Monospace

private val Light = lightColorScheme(
    primary = PgBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6F2),
    onPrimaryContainer = Color(0xFF0E2A42),
    secondary = Color(0xFF4F6D87),
    secondaryContainer = Color(0xFFE3EBF2),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEBEFF4),
    surfaceContainer = Color(0xFFF0F3F7),
    outline = Color(0xFF8A97A5),
    outlineVariant = Color(0xFFD3DAE2),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DBAE0),
    onPrimary = Color(0xFF0A2236),
    primaryContainer = Color(0xFF244B6C),
    onPrimaryContainer = Color(0xFFD8E6F2),
    secondary = Color(0xFFA9C2D8),
    secondaryContainer = Color(0xFF2B3A48),
    background = Color(0xFF14181C),
    surface = Color(0xFF1A1F24),
    surfaceVariant = Color(0xFF252C33),
    surfaceContainer = Color(0xFF20262C),
    outline = Color(0xFF7C8996),
    outlineVariant = Color(0xFF38424C),
    error = Color(0xFFF2B8B5),
)

/** Compact typography: this is a dense, desktop-style tool. */
private val AppTypography = Typography(
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun PgAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
