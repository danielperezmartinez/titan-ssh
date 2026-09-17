package im.gar.titanssh.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Maps the dark-first titan-ssh tokens onto Material 3.
 *
 * Dark-first is intentional (ADR / visual decision): the dark scheme is the
 * product baseline; a light theme is a later task and is deliberately not wired
 * here.
 */
private val TitanDarkColorScheme = darkColorScheme(
    primary = TitanColors.Accent,
    onPrimary = TitanColors.Ink,
    background = TitanColors.Canvas,
    onBackground = TitanColors.Ink,
    surface = TitanColors.Surface,
    onSurface = TitanColors.Body,
    surfaceVariant = TitanColors.SurfaceElevated,
    onSurfaceVariant = TitanColors.Mute,
    outline = TitanColors.HairlineStrong,
    error = TitanColors.Danger,
    onError = TitanColors.Ink,
)

// "Mono en todo" is the identity of the visual language. The agreed family is
// JetBrains Mono; bundling the font file is a follow-up, so the skeleton falls
// back to the system monospace to keep the mono identity without a font asset.
private val Mono = FontFamily.Monospace

private val TitanTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp),
)

@Composable
fun TitanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TitanDarkColorScheme,
        typography = TitanTypography,
        content = content,
    )
}
