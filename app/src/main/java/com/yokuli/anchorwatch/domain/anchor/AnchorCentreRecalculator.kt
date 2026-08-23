package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.Confidence
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.PositionProvider

enum class AnchorCentreRecalculationStatus {
    READY,
    INSUFFICIENT_GEOMETRY,
    RADIAL_NOT_OBSERVABLE,
    DATA_QUALITY_INSUFFICIENT,
}

object AnchorCentreApplyPolicy {
    fun mayApply(alarmState:AlarmState?):Boolean = alarmState !in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)
}

data class AnchorCentreRecalculationResult(
    val status: AnchorCentreRecalculationStatus,
    val currentLatitude: Double,
    val currentLongitude: Double,
    val candidate: BackdownAnchorEstimate?,
    val shiftMeters: Double?,
    val shiftBearingDegrees: Double?,
    val message: String,
)

object AnchorCentreRecalculator {
    fun analyze(session: AnchorSessionEntity, points: List<TrackPointEntity>): AnchorCentreRecalculationResult {
        val valid = points.filter { point ->
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
                point.fixTrust !in setOf(FixTrust.REJECTED.name, FixTrust.QUARANTINED.name) &&
                !point.wasQuarantined && (point.hdop == null || point.hdop <= 8.0) &&
                (point.horizontalAccuracyMeters == null || point.horizontalAccuracyMeters <= 100.0)
        }
        if (valid.size < 20) return result(session, AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY, null, "Not enough accepted track points yet.")
        val samples = valid.map { point ->
            BackdownCenterEstimator.Sample(
                latitude=point.latitude,longitude=point.longitude,timestamp=point.timestamp,hdop=point.hdop,
                headingTrueDegrees=point.heading.takeIf{point.headingMeasured},cogTrueDegrees=point.cog,sogKnots=point.sog,
                windDirectionTrueDegrees=point.windDirectionTrue,windSpeedKnots=point.windSpeedKnots,
                apparentWindAngleDegrees=point.apparentWindAngle,trueWindAngleDegrees=point.trueWindAngle,
                trueWindSpeedKnots=point.trueWindSpeedKnots,apparentWindSpeedKnots=point.apparentWindSpeedKnots,
                headingSampleSequence=point.headingSampleSequence,windSampleSequence=point.windSampleSequence,
                horizontalAccuracyMeters=point.horizontalAccuracyMeters,
                positionProvider=runCatching{PositionProvider.valueOf(point.positionProvider)}.getOrDefault(PositionProvider.UNKNOWN),
                fixTrust=runCatching{FixTrust.valueOf(point.fixTrust)}.getOrDefault(FixTrust.DEGRADED),
                headingSource=runCatching{HeadingSource.valueOf(point.headingSource)}.getOrDefault(HeadingSource.NONE),
                headingQuality=runCatching{HeadingQuality.valueOf(point.headingQuality)}.getOrDefault(HeadingQuality.UNAVAILABLE),
            )
        }
        val estimate = BackdownCenterEstimator().estimateSamples(samples, session.expectedSwingRadiusMeters)
            ?: return result(session, AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY, null, "The track does not yet contain enough time and movement.")
        val status = when {
            estimate.observabilityReason == AnchorCentreObservabilityReason.GPS_UNCERTAINTY_DOMINATES -> AnchorCentreRecalculationStatus.DATA_QUALITY_INSUFFICIENT
            !estimate.radialObservable || estimate.observabilityReason != AnchorCentreObservabilityReason.OBSERVABLE -> AnchorCentreRecalculationStatus.RADIAL_NOT_OBSERVABLE
            estimate.confidence != Confidence.HIGH -> AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY
            else -> AnchorCentreRecalculationStatus.READY
        }
        val message = when (status) {
            AnchorCentreRecalculationStatus.READY -> "A high-confidence alternative centre is ready for comparison."
            AnchorCentreRecalculationStatus.RADIAL_NOT_OBSERVABLE -> "The recorded movement does not yet reveal the full rode-scale swing."
            AnchorCentreRecalculationStatus.DATA_QUALITY_INSUFFICIENT -> "GPS uncertainty is too large for a safe centre estimate."
            AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY -> "More time, swing angle and direction reversals are required."
        }
        return result(session,status,estimate,message)
    }

    private fun result(session:AnchorSessionEntity,status:AnchorCentreRecalculationStatus,candidate:BackdownAnchorEstimate?,message:String):AnchorCentreRecalculationResult{
        val shift=candidate?.let{AnchorGeometry.distanceMeters(session.anchorLatitude,session.anchorLongitude,it.latitude,it.longitude)}
        val bearing=candidate?.let{AnchorGeometry.bearingDegrees(session.anchorLatitude,session.anchorLongitude,it.latitude,it.longitude)}
        return AnchorCentreRecalculationResult(status,session.anchorLatitude,session.anchorLongitude,candidate,shift,bearing,message)
    }
}
