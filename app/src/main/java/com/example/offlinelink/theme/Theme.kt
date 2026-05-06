package com.example.offlinelink.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = LinkGreenDark,
    onPrimary = Color(0xFF123021),
    primaryContainer = Color(0xFF214B37),
    onPrimaryContainer = Color(0xFFD9F2E4),
    secondary = Color(0xFFA8D5E5),
    onSecondary = Color(0xFF17323D),
    tertiary = Color(0xFFE3C38A),
    background = Color(0xFF171B18),
    onBackground = Color(0xFFE7ECE6),
    surface = Color(0xFF1E2420),
    onSurface = Color(0xFFE7ECE6),
    surfaceVariant = Color(0xFF39423C),
    onSurfaceVariant = Color(0xFFC3CBC4),
    outline = Color(0xFF8A948C),
    outlineVariant = Color(0xFF39423C),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = LinkGreen,
    onPrimary = Color(0xFFF4FBF6),
    primaryContainer = Color(0xFFD8EEE2),
    onPrimaryContainer = Color(0xFF123021),
    secondary = SignalBlue,
    onSecondary = Color(0xFFF2FAFD),
    secondaryContainer = Color(0xFFD4EAF2),
    onSecondaryContainer = Color(0xFF17323D),
    tertiary = FieldAmber,
    onTertiary = Color(0xFFFCF7EC),
    tertiaryContainer = Color(0xFFF0DFC0),
    onTertiaryContainer = Color(0xFF30220D),
    background = WarmCanvas,
    onBackground = Ink,
    surface = WarmSurface,
    onSurface = Ink,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF8D958D),
    outlineVariant = Color(0xFFD5D8CD),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
