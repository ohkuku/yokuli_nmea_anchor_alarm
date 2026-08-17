package com.yokuli.anchorwatch.domain.model

data class NavigationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampUtcMillis: Long? = null,
    val receivedElapsedRealtime: Long,
    val sogKnots: Double? = null,
    val cogTrueDegrees: Double? = null,
    val headingTrueDegrees: Double? = null,
    val headingMagneticDegrees: Double? = null,
    val cogReceivedElapsedRealtime: Long? = null,
    val headingReceivedElapsedRealtime: Long? = null,
    val hdop: Double? = null,
    val fixQuality: Int? = null,
    val satellites: Int? = null,
    val depthMeters: Double? = null,
    val altitudeMeters: Double? = null,
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
){
    fun debugSummary():String="confidence=$confidence, centre=$latitude/$longitude, distance=$distanceMeters, uncertainty=$uncertaintyRadiusMeters, sampleCount=$sampleCount, effectiveDuration=$effectiveDurationMillis, angularCoverage=$angularCoverageDegrees, sectorCount=$angularSectorCount, reversalCount=$swingReversalCount, rmsError=$rmsErrorMeters, temporalConsensus=$temporalFitConsistent, directionEvidenceConsistent=$directionEvidenceConsistent"
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
