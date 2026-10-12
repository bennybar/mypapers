package com.bennybar.mypapers.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.R

/**
 * Material 3 Expressive theme — the **"Bloom"** variant from Ilay (calm
 * lavender palette, framed filled cards), Google Sans, light/dark from the device.
 */
object AppTheme {
    val bloomLight = lightColorScheme(
        primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
        surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9), surfaceDim = Color(0xFFDED8E1),
        surfaceBright = Color(0xFFFEF7FF), inverseSurface = Color(0xFF322F35),
        inverseOnSurface = Color(0xFFF5EFF7), inversePrimary = Color(0xFFD0BCFF),
        scrim = Color(0xFF000000), surfaceTint = Color(0xFF6750A4),
    )

    val bloomDark = darkColorScheme(
        primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
        error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
        background = Color(0xFF141218), onBackground = Color(0xFFE6E0E9),
        surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
        surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
        surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B), surfaceDim = Color(0xFF141218),
        surfaceBright = Color(0xFF3B383E), inverseSurface = Color(0xFFE6E0E9),
        inverseOnSurface = Color(0xFF322F35), inversePrimary = Color(0xFF6750A4),
        scrim = Color(0xFF000000), surfaceTint = Color(0xFFD0BCFF),
    )

    /** Material's emphasized easing, used by the page slide. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Google Sans (OFL), one variable file subset to Latin/Greek/Cyrillic/Hebrew; weight axis 400–700. */
    @OptIn(ExperimentalTextApi::class)
    val googleSans = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
            Font(R.font.google_sans, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
        },
    )

    /**
     * Material's type scale was drawn for Google Sans: keep its sizes and weights.
     * Each text reads in its own direction (a Hebrew vendor name right to left),
     * not the layout's.
     */
    val typography: Typography = Typography().run {
        fun TextStyle.g() = copy(fontFamily = googleSans, textDirection = TextDirection.Content)
        copy(
            displayLarge = displayLarge.g(), displayMedium = displayMedium.g(), displaySmall = displaySmall.g(),
            headlineLarge = headlineLarge.g(), headlineMedium = headlineMedium.g(), headlineSmall = headlineSmall.g(),
            titleLarge = titleLarge.g(), titleMedium = titleMedium.g(), titleSmall = titleSmall.g(),
            bodyLarge = bodyLarge.g(), bodyMedium = bodyMedium.g(), bodySmall = bodySmall.g(),
            labelLarge = labelLarge.g(), labelMedium = labelMedium.g(), labelSmall = labelSmall.g(),
        )
    }

    /** Bloom: cards radius 28; chips/buttons are stadiums (set per component). */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PapersTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) AppTheme.bloomDark else AppTheme.bloomLight,
        typography = AppTheme.typography,
        shapes = AppTheme.shapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
