package se.kinosthlm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors =
  darkColorScheme(
    primary = KinoDarkPrimary,
    onPrimary = KinoDarkOnPrimary,
    primaryContainer = KinoDarkPrimaryContainer,
    onPrimaryContainer = KinoDarkOnPrimaryContainer,
    secondary = KinoDarkSecondary,
    onSecondary = KinoDarkOnSecondary,
    secondaryContainer = KinoDarkSecondaryContainer,
    onSecondaryContainer = KinoDarkOnSecondaryContainer,
    tertiary = KinoDarkTertiary,
    onTertiary = KinoDarkOnTertiary,
    background = KinoDarkBg,
    onBackground = KinoDarkTextPrimary,
    surface = KinoDarkSurface,
    onSurface = KinoDarkTextPrimary,
    surfaceVariant = KinoDarkSurfaceElevated,
    onSurfaceVariant = KinoDarkTextSecondary,
    surfaceContainer = KinoDarkSurfaceElevated,
    surfaceContainerHigh = KinoDarkSurfaceElevated,
    outline = KinoDarkBorder,
    outlineVariant = KinoDarkBorderSubtle,
    error = KinoDarkError,
    onError = KinoDarkOnError,
  )

private val LightColors =
  lightColorScheme(
    primary = KinoLightPrimary,
    onPrimary = KinoLightOnPrimary,
    primaryContainer = KinoLightPrimaryContainer,
    onPrimaryContainer = KinoLightOnPrimaryContainer,
    secondary = KinoLightSecondary,
    onSecondary = KinoLightOnSecondary,
    secondaryContainer = KinoLightSecondaryContainer,
    onSecondaryContainer = KinoLightOnSecondaryContainer,
    tertiary = KinoLightTertiary,
    onTertiary = KinoLightOnTertiary,
    background = KinoLightBg,
    onBackground = KinoLightTextPrimary,
    surface = KinoLightSurface,
    onSurface = KinoLightTextPrimary,
    surfaceVariant = KinoLightSurfaceElevated,
    onSurfaceVariant = KinoLightTextSecondary,
    surfaceContainer = KinoLightSurfaceElevated,
    surfaceContainerHigh = KinoLightSurfaceElevated,
    outline = KinoLightBorder,
    outlineVariant = KinoLightBorderSubtle,
    error = KinoLightError,
    onError = KinoLightOnError,
  )

@Composable
fun KinoSthlmTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColors else LightColors
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as android.app.Activity).window
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
  }
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
