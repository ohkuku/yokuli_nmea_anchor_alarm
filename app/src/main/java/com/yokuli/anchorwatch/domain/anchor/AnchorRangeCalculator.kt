package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import kotlin.math.max
import kotlin.math.sqrt

data class AnchorRangeSuggestion(
    val radiusMeters: Double,
    val horizontalRodeMeters: Double,
    val gpsMarginMeters: Double,
    val learningMarginMeters: Double,
)

/** Calculates a swing radius from taut-rode geometry plus explicit safety margins. */
object AnchorRangeCalculator {
    fun advanced(
        depthMeters: Double,
        rodeMeters: Double,
        boatLengthMeters: Double,
        placement: AnchorPlacementMode,
        preset: AnchorSafetyPreset,
        bowRollerHeightMeters: Double = 1.5,
    ): AnchorRangeSuggestion? {
        if (depthMeters < 0 || rodeMeters <= 0 || boatLengthMeters < 0) return null
        val vertical = depthMeters + bowRollerHeightMeters
        if (rodeMeters < vertical) return null
        val horizontal = sqrt(max(0.0, rodeMeters * rodeMeters - vertical * vertical))
        val gpsMargin = when (preset) {
            AnchorSafetyPreset.STRICT -> 5.0
            AnchorSafetyPreset.BALANCED -> 10.0
            AnchorSafetyPreset.TOLERANT -> 15.0
        }
        val learningMargin = if (placement == AnchorPlacementMode.BACKDOWN) when (preset) {
            AnchorSafetyPreset.STRICT -> 5.0
            AnchorSafetyPreset.BALANCED -> 10.0
            AnchorSafetyPreset.TOLERANT -> 15.0
        } else 0.0
        return AnchorRangeSuggestion(
            radiusMeters = horizontal + boatLengthMeters + gpsMargin + learningMargin,
            horizontalRodeMeters = horizontal,
            gpsMarginMeters = gpsMargin,
            learningMarginMeters = learningMargin,
        )
    }
}
