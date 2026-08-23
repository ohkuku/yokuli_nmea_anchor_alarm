package com.yokuli.anchorwatch.domain.anchor

enum class AnchorCentreObservabilityReason {
    OBSERVABLE,
    TRACK_TOO_SMALL,
    FIT_RADIUS_TOO_SMALL,
    FIT_RADIUS_IMPLAUSIBLE,
    GPS_UNCERTAINTY_DOMINATES,
    NO_USABLE_CIRCLE_FIT,
}

/** Separates a mathematically neat local circle from an observable full-rode swing. */
data class AnchorCentreObservability(
    val radialObservable: Boolean,
    val trackDiameterMeters: Double,
    val fittedRadiusMeters: Double?,
    val maximumRodeMeters: Double,
    val gpsMarginMeters: Double,
    val fittedRadiusRatio: Double?,
    val trackSpanRatio: Double,
    val reason: AnchorCentreObservabilityReason,
)

object AnchorCentreObservabilityPolicy {
    fun evaluate(
        trackDiameterMeters: Double,
        fittedRadiusMeters: Double?,
        maximumRodeMeters: Double,
        gpsMarginMeters: Double,
        fitGeometricallyUsable: Boolean,
    ): AnchorCentreObservability {
        val safeRode = maximumRodeMeters.coerceAtLeast(1.0)
        val minimumMeaningfulRadius = maxOf(12.0, safeRode * .35, gpsMarginMeters * 2.5)
        val minimumMeaningfulTrackSpan = maxOf(12.0, safeRode * .40, gpsMarginMeters * 3.0)
        val reason = when {
            fittedRadiusMeters == null || !fitGeometricallyUsable -> AnchorCentreObservabilityReason.NO_USABLE_CIRCLE_FIT
            fittedRadiusMeters > safeRode + gpsMarginMeters -> AnchorCentreObservabilityReason.FIT_RADIUS_IMPLAUSIBLE
            trackDiameterMeters < minimumMeaningfulTrackSpan -> AnchorCentreObservabilityReason.TRACK_TOO_SMALL
            fittedRadiusMeters < minimumMeaningfulRadius -> AnchorCentreObservabilityReason.FIT_RADIUS_TOO_SMALL
            gpsMarginMeters >= fittedRadiusMeters * .60 || gpsMarginMeters >= trackDiameterMeters * .45 ->
                AnchorCentreObservabilityReason.GPS_UNCERTAINTY_DOMINATES
            else -> AnchorCentreObservabilityReason.OBSERVABLE
        }
        return AnchorCentreObservability(
            radialObservable = reason == AnchorCentreObservabilityReason.OBSERVABLE,
            trackDiameterMeters = trackDiameterMeters,
            fittedRadiusMeters = fittedRadiusMeters,
            maximumRodeMeters = safeRode,
            gpsMarginMeters = gpsMarginMeters,
            fittedRadiusRatio = fittedRadiusMeters?.div(safeRode),
            trackSpanRatio = trackDiameterMeters / safeRode,
            reason = reason,
        )
    }
}

data class AnchorCentreEvidenceBaseline(val samples:Int,val coverageDegrees:Double,val trackDiameterMeters:Double,val reversals:Int)

object AnchorCentreEvidenceGrowthPolicy {
    fun hasMeaningfulGrowth(baseline:AnchorCentreEvidenceBaseline?,estimate:com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate,maximumRodeMeters:Double):Boolean{
        baseline?:return true
        return estimate.sampleCount>=baseline.samples+120&&(
            estimate.angularCoverageDegrees>=baseline.coverageDegrees+25.0||
                estimate.trackDiameterMeters>=baseline.trackDiameterMeters+maxOf(6.0,maximumRodeMeters*.15)||
                estimate.swingReversalCount>baseline.reversals
            )
    }
}

object AnchorCentreCandidatePolicy {
    fun isMeaningfulShift(shiftMeters:Double,uncertaintyRadiusMeters:Double):Boolean =
        shiftMeters.isFinite()&&uncertaintyRadiusMeters.isFinite()&&
            shiftMeters>=maxOf(8.0,uncertaintyRadiusMeters*1.5)
}
