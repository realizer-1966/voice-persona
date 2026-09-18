package com.voicepersona.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Emerald = Color(0xFF34D399)
private val EmeraldDeep = Color(0xFF059669)
private val Ink = Color(0xFF0F1512)
private val Surface0 = Color(0xFF151D19)
private val Surface1 = Color(0xFF1E2A24)
private val Sage = Color(0xFF9DB5AA)

val AppDark = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF04231A),
    secondary = Sage,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFE7F0EB),
    surface = Surface0,
    onSurface = Color(0xFFE7F0EB),
    surfaceVariant = Surface1,
    onSurfaceVariant = Sage,
    error = Color(0xFFFF8A80),
)

val AppLight = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    secondary = Color(0xFF3F5B4E),
    background = Color(0xFFF4F8F5),
    onBackground = Color(0xFF10201A),
    surface = Color.White,
    onSurface = Color(0xFF10201A),
    surfaceVariant = Color(0xFFE1EBE4),
    onSurfaceVariant = Color(0xFF44554C),
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun VoicePersonaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) AppDark else AppLight,
        typography = AppTypography,
        content = content,
    )
}
