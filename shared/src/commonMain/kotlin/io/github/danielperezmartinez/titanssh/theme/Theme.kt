package io.github.danielperezmartinez.titanssh.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.danielperezmartinez.titanssh.resources.Res
import io.github.danielperezmartinez.titanssh.resources.jetbrainsmono_bold
import io.github.danielperezmartinez.titanssh.resources.jetbrainsmono_medium
import io.github.danielperezmartinez.titanssh.resources.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font

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

/**
 * Builds the type scale from the visual decision on the given mono [family].
 * The scale is tuned for app density (not landing density).
 */
private fun titanTypography(family: FontFamily): Typography = Typography(
    displaySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp),
)

@Composable
fun TitanTheme(content: @Composable () -> Unit) {
    // "Mono en todo" with JetBrains Mono (the agreed family, bundled as a Compose
    // resource). Falls back to the platform monospace only if a face fails to load.
    val jetBrainsMono = FontFamily(
        Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
        Font(Res.font.jetbrainsmono_medium, FontWeight.Medium),
        Font(Res.font.jetbrainsmono_bold, FontWeight.Bold),
    )
    MaterialTheme(
        colorScheme = TitanDarkColorScheme,
        typography = titanTypography(jetBrainsMono),
        content = content,
    )
}
