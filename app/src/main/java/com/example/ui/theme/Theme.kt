package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class StudioExtendedColors(
    val liveCrimson: Color = SignalCrimson,
    val liveCrimsonContainer: Color = SignalCrimsonContainer,
    val velocityCyan: Color = ElectricCyan,
    val velocityCyanContainer: Color = ElectricCyanContainer,
    val thresholdAmber: Color = BroadcastAmber,
    val thresholdAmberContainer: Color = BroadcastAmberContainer,
    val targetEmerald: Color = StudioEmerald,
    val targetEmeraldContainer: Color = StudioEmeraldContainer,
    val rackSurface: Color = StudioRackSurface,
    val elevatedCard: Color = StudioElevatedSurface,
    val subtleOutline: Color = StudioOutlineSubtle,
    val mutedText: Color = TextMutedDark
)

val LocalStudioColors = staticCompositionLocalOf { StudioExtendedColors() }

private val BroadcastDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianCanvas,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = SignalCrimson,
    onSecondary = TextPrimaryDark,
    secondaryContainer = SignalCrimsonContainer,
    onSecondaryContainer = SignalCrimson,
    tertiary = BroadcastAmber,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = BroadcastAmberContainer,
    onTertiaryContainer = BroadcastAmber,
    background = ObsidianCanvas,
    onBackground = TextPrimaryDark,
    surface = StudioRackSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = StudioCardSurface,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = StudioRackSurface,
    surfaceContainer = StudioCardSurface,
    surfaceContainerHigh = StudioElevatedSurface,
    surfaceContainerHighest = StudioElevatedSurface,
    outline = StudioOutline,
    outlineVariant = StudioOutlineSubtle,
    error = SignalCrimson,
    onError = TextPrimaryDark,
    errorContainer = SignalCrimsonContainer,
    onErrorContainer = SignalCrimson
)

val PulseClipShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PulseClipTheme(
    content: @Composable () -> Unit
) {
    val extendedColors = StudioExtendedColors()
    CompositionLocalProvider(LocalStudioColors provides extendedColors) {
        MaterialTheme(
            colorScheme = BroadcastDarkColorScheme,
            typography = PulseClipTypography,
            shapes = PulseClipShapes,
            content = content
        )
    }
}
