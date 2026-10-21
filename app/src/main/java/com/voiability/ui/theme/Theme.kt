package com.voiability.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightFallback = lightColorScheme(
    primary = Color(0xFF6B4EFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF5E5C71), tertiary = Color(0xFFB0306A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E3),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFFCABEFF), onPrimary = Color(0xFF35159A),
    primaryContainer = Color(0xFF4D33C8), onPrimaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFFFFB0C9), onTertiary = Color(0xFF5E1136),
)

private val base = Typography()
private val VoiTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = VoiTypography,
        content = content,
    )
}
