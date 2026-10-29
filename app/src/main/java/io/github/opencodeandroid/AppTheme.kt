package io.github.opencodeandroid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color

private val DarkWorkspaceColors = darkColorScheme(
    primary = Color(0xffa9e6c7), onPrimary = Color(0xff10251a), primaryContainer = Color(0xff314c3d), onPrimaryContainer = Color(0xffc1f4d7),
    secondary = Color(0xffb1ccba), secondaryContainer = Color(0xff33483b), onSecondaryContainer = Color(0xffd5ebdd),
    background = Color(0xff111512), onBackground = Color(0xffedf3ee), surface = Color(0xff1b211d), onSurface = Color(0xffedf3ee),
    surfaceVariant = Color(0xff29362d), onSurfaceVariant = Color(0xffb4c4b8), outline = Color(0xff839589), outlineVariant = Color(0xff3e4d43),
    surfaceContainer = Color(0xff1d2821), surfaceContainerHigh = Color(0xff26342b), surfaceContainerLow = Color(0xff171e19),
)
private val LightWorkspaceColors = lightColorScheme(
    primary = Color(0xff276749), onPrimary = Color.White, primaryContainer = Color(0xffc2ecd3), onPrimaryContainer = Color(0xff092c1b),
    secondary = Color(0xff526557), secondaryContainer = Color(0xffd5e8d9), onSecondaryContainer = Color(0xff122219),
    background = Color(0xfff7faf7), onBackground = Color(0xff18201a), surface = Color(0xfff7faf7), onSurface = Color(0xff18201a),
    surfaceVariant = Color(0xffdce5dc), onSurfaceVariant = Color(0xff3e4b42), outline = Color(0xff718076), outlineVariant = Color(0xffc1cdc3),
    surfaceContainer = Color(0xffeef3ee), surfaceContainerHigh = Color(0xffe5eee7), surfaceContainerLow = Color(0xfff1f6f1),
)

@Composable internal fun OpenCodeAppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Observe configuration changes as well as uiMode so wallpaper-derived palettes
    // are read again when Android updates its dynamic color resources.
    LocalConfiguration.current
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkWorkspaceColors else LightWorkspaceColors
    MaterialTheme(colorScheme = colors, content = content)
}
