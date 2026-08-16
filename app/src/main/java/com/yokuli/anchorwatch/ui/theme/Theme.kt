package com.yokuli.anchorwatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CalmMarineColors = darkColorScheme(
    primary = YokuliAccent,
    secondary = MarineSecondary,
    tertiary = MarineEstimate,
    background = MarineBackground,
    surface = MarineSurface,
    surfaceVariant = MarineSurfaceRaised,
    error = MarineAlarm,
)

@Composable
fun YokuliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CalmMarineColors,
        typography = YokuliTypography,
        shapes = YokuliShapes,
        content = content,
    )
}
