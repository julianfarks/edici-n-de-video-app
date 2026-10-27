package com.vanta.studio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vanta Studio palette: soft blacks and graphite with a single electric-violet accent. */
object Vanta {
    val Bg = Color(0xFF0A0A0D)
    val Surface = Color(0xFF111116)
    val Surface2 = Color(0xFF18181F)
    val Surface3 = Color(0xFF212129)
    val Outline = Color(0xFF2B2B35)
    val OutlineStrong = Color(0xFF3A3A47)
    val Accent = Color(0xFF8B6CFF)
    val AccentBright = Color(0xFFB9A6FF)
    val AccentDim = Color(0xFF2E2560)
    val Text = Color(0xFFF2F2F7)
    val TextDim = Color(0xFFA3A3B0)
    val TextFaint = Color(0xFF8C8C9A)
    val Danger = Color(0xFFFF5C7A)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFFB547)

    val TrackVideo = Color(0xFF241F3D)
    val TrackText = Color(0xFFFFB547)
    val TrackOverlay = Color(0xFFF472B6)
    val TrackAudio = Color(0xFF2DD4BF)
    val Playhead = Color(0xFFFFFFFF)
}

private val scheme = darkColorScheme(
    primary = Vanta.Accent,
    onPrimary = Color.White,
    primaryContainer = Vanta.AccentDim,
    onPrimaryContainer = Vanta.AccentBright,
    secondary = Vanta.AccentBright,
    onSecondary = Vanta.Bg,
    background = Vanta.Bg,
    onBackground = Vanta.Text,
    surface = Vanta.Surface,
    onSurface = Vanta.Text,
    surfaceVariant = Vanta.Surface2,
    onSurfaceVariant = Vanta.TextDim,
    surfaceContainer = Vanta.Surface2,
    surfaceContainerHigh = Vanta.Surface3,
    surfaceContainerHighest = Vanta.Surface3,
    surfaceContainerLow = Vanta.Surface,
    outline = Vanta.Outline,
    outlineVariant = Vanta.Outline,
    error = Vanta.Danger,
    onError = Color.White,
)

private val base = Typography()
private val family = FontFamily.SansSerif

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = base.bodyLarge.copy(fontFamily = family, fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = family, fontSize = 14.sp),
    bodySmall = base.bodySmall.copy(fontFamily = family, fontSize = 12.sp, color = Vanta.TextDim),
    labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = base.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = base.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Monospaced digits for timecodes so they don't jitter while playing. */
val TimecodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Vanta.TextDim)

@Composable
fun VantaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        // Text outside a Surface would otherwise fall back to black on the dark background.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Vanta.Text,
            content = content,
        )
    }
}
