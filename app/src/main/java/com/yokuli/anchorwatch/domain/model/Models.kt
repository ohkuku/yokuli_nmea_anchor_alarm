package com.yokuli.anchorwatch.domain.model

import com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason

data class NavigationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampUtcMillis: Long? = null,
    val receivedElapsedRealtime: Long,
    val sogKnots: Double? = null,
    val cogTrueDegrees: Double? = null,
    val headingTrueDegrees: Double? = null,
    val headingMagneticDegrees: Double? = null,
    val sogReceivedElapsedRealtime: Long? = null,
    val cogReceivedElapsedRealtime: Long? = null,
    val headingReceivedElapsedRealtime: Long? = null,
    val headingMagneticReceivedElapsedRealtime: Long? = null,
    val hdop: Double? = null,
    val fixQuality: Int? = null,
    val satellites: Int? = null,
    /** Receive times of the last GGA/GNS quality components. A later RMC/GLL
     * sentence may omit them without erasing their diagnostic value. Safety
     * consumers must still reject them after their own freshness window. */
    val hdopReceivedElapsedRealtime: Long? = null,
    val fixQualityReceivedElapsedRealtime: Long? = null,
    val satellitesReceivedElapsedRealtime: Long? = null,
    val depthMeters: Double? = null,
    /** Monotonic receive time of the last real depth sentence. A held value is
     * intentionally kept in [depthMeters]; consumers decide freshness from
     * this timestamp instead of treating an omitted DBT/DPT field as null. */
    val depthReceivedElapsedRealtime: Long? = null,
    val speedThroughWaterKnots:Double? = null,
    val speedThroughWaterReceivedElapsedRealtime:Long? = null,
    val altitudeMeters: Double? = null,
    val altitudeReceivedElapsedRealtime: Long? = null,
    val horizontalAccuracyMeters: Double? = null,
    val positionProvider: PositionProvider = PositionProvider.UNKNOWN,
    val isMockLocation: Boolean = false,
    val sourceSentence: String,
    val valid: Boolean,
    val windDirectionTrueDegrees: Double? = null,
    val windSpeedKnots: Double? = null,
    val apparentWindAngleDegrees: Double? = null,
    val trueWindAngleDegrees: Double? = null,
    val trueWindSpeedKnots: Double? = null,
    val apparentWindSpeedKnots: Double? = null,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val headingQuality: HeadingQuality = HeadingQuality.UNAVAILABLE,
    val headingEpoch: Long? = null,
    val headingSampleSequence: Long? = null,
    val windSampleSequence: Long? = null,
)

enum class GpsDataSource { SYSTEM, NMEA, DEMO }
enum class PositionProvider { UNKNOWN, NMEA, ANDROID_GNSS, ANDROID_NETWORK, DEMO }
enum class FixTrust { TRUSTED, DEGRADED, QUARANTINED, REJECTED }
enum class PositionHealth { GPS_OK, GPS_DEGRADED, GPS_LOST }
enum class HeadingSource { NONE, NMEA_PHYSICAL, PHONE }
enum class HeadingQuality { UNAVAILABLE, STABLE, MOVING, DISTURBED, RECOVERING }
/** SYSTEM is retained for backup compatibility; new installs and the picker use explicit languages. */
enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE,
    TRADITIONAL_CHINESE,
    JAPANESE,
    FRENCH,
    SPANISH,
}
enum class AlarmSound { SYSTEM_ALARM, SYSTEM_RINGTONE, SYSTEM_NOTIFICATION, CUSTOM }
enum class DemoScenario { SAFE_SWING, ANCHOR_DRAG, WIND_SHIFT, GPS_DROPOUT, DEPTH_SHALLOW, DEPTH_DEEP, WIND_ALARM }

/** Legacy names remain on disk so existing sessions migrate without data loss. */
enum class AnchorPlacementMode { CENTER_DROP, BACKDOWN }
enum class AnchorPositionMode { KNOWN, ESTIMATE }
enum class KnownAnchorMethod { CURRENT_POSITION, MANUAL_COORDINATES, MAP_PICK }
enum class AnchorCenterSource { UNKNOWN, CURRENT_POSITION, MANUAL_COORDINATES, MAP_PICK, ESTIMATED_USER_ACCEPTED }
/** Auditable origin of the coordinate that created an anchor session. */
enum class AnchorOriginMode { CURRENT_ACCEPTED_POSITION, BACKDOWN_FROM_ACCEPTED_POSITION, MANUAL_COORDINATE, MAP_PICK }
/** Persisted safety phase. A saved session is not necessarily monitoring yet. */
enum class AnchorMonitoringPhase { WAITING_FOR_GPS, ARMED, LEARNING, PAUSED, ENDED }
enum class CandidateDecision { NONE, AVAILABLE, ACCEPTED, REJECTED }
enum class AnchorCenterStatus { LEARNING, CANDIDATE_READY, RESOLVED }
enum class AnchorRangeMode { BASIC, ADVANCED }
enum class AnchorSafetyPreset { STRICT, BALANCED, TOLERANT }
enum class NmeaConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CONNECTED_NO_DATA, CONNECTED_NO_FIX, STALE, RECONNECTING, ERROR }
enum class AlarmState { IDLE, SETTING, LEARNING, ARMED, WARNING, ALARM, ACKNOWLEDGED, STOPPED }
enum class AlarmType { ANCHOR_RADIUS_EXCEEDED, GPS_DATA_LOST, NMEA_CONNECTION_LOST, GPS_QUALITY_BAD, MOCK_GPS_FAILED, ALARM_TEST }
enum class Confidence { LOW, MEDIUM, HIGH }

data class AnchorEstimate(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val confidence: Confidence,
    val rmsErrorMeters: Double,
    val angularCoverageDegrees: Double,
    val sampleCount: Int,
)

data class BackdownAnchorEstimate(
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val uncertaintyRadiusMeters: Double,
    val confidence: Confidence,
    val sampleCount: Int,
    val angularCoverageDegrees: Double = 0.0,
    val angularSectorCount: Int = 0,
    val rmsErrorMeters: Double? = null,
    val swingReversalCount: Int = 0,
    val temporalFitConsistent: Boolean = false,
    val effectiveDurationMillis: Long = 0L,
    val directionEvidenceConsistent: Boolean = false,
    val radialObservable: Boolean = false,
    val trackDiameterMeters: Double = 0.0,
    val fittedRadiusMeters: Double? = null,
    val maximumRodeMeters: Double = 0.0,
    val gpsMarginMeters: Double = 0.0,
    val fittedRadiusRatio: Double? = null,
    val trackSpanRatio: Double = 0.0,
    val observabilityReason: AnchorCentreObservabilityReason = AnchorCentreObservabilityReason.NO_USABLE_CIRCLE_FIT,
    val nmeaPhysicalHeadingEvidenceCount: Int = 0,
    val phoneHeadingEvidenceCount: Int = 0,
){
    fun debugSummary():String="confidence=$confidence, centre=$latitude/$longitude, distance=$distanceMeters, uncertainty=$uncertaintyRadiusMeters, sampleCount=$sampleCount, effectiveDuration=$effectiveDurationMillis, angularCoverage=$angularCoverageDegrees, sectorCount=$angularSectorCount, reversalCount=$swingReversalCount, rmsError=$rmsErrorMeters, temporalConsensus=$temporalFitConsistent, directionEvidenceConsistent=$directionEvidenceConsistent, radialObservable=$radialObservable, observabilityReason=$observabilityReason, trackDiameter=$trackDiameterMeters, fittedRadius=$fittedRadiusMeters, maximumRode=$maximumRodeMeters, gpsMargin=$gpsMarginMeters, fittedRadiusRatio=$fittedRadiusRatio, trackSpanRatio=$trackSpanRatio, nmeaHeadingEvidence=$nmeaPhysicalHeadingEvidenceCount, phoneHeadingEvidence=$phoneHeadingEvidenceCount"
}

data class AnchorConfig(
    val latitude: Double,
    val longitude: Double,
    val rodeLengthMeters: Double,
    val waterDepthMeters: Double? = null,
    val bowRollerHeightMeters: Double = 0.0,
    val gpsAntennaOffsetMeters: Double = 0.0,
    val warningRadiusMeters: Double,
    val alarmRadiusMeters: Double,
)

data class AlarmSnapshot(
    val state: AlarmState = AlarmState.IDLE,
    val type: AlarmType? = null,
    val distanceMeters: Double? = null,
    val maxDistanceMeters: Double = 0.0,
    val minDistanceMeters: Double? = null,
    val averageDistanceMeters: Double? = null,
    val acknowledged: Boolean = false,
)
