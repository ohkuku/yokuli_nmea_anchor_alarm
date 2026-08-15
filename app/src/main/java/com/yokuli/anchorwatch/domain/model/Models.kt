package com.yokuli.anchorwatch.domain.model

data class NavigationFix(
 val latitude: Double, val longitude: Double, val timestampUtcMillis: Long? = null,
 val receivedElapsedRealtime: Long, val sogKnots: Double? = null, val cogTrueDegrees: Double? = null,
 val headingTrueDegrees: Double? = null, val headingMagneticDegrees: Double? = null,
 val hdop: Double? = null, val fixQuality: Int? = null, val satellites: Int? = null,
 val depthMeters: Double? = null, val altitudeMeters: Double? = null,
 val sourceSentence: String, val valid: Boolean,
 val windDirectionTrueDegrees: Double? = null, val windSpeedKnots: Double? = null,
 val apparentWindAngleDegrees: Double? = null,
 val trueWindAngleDegrees: Double? = null, val trueWindSpeedKnots: Double? = null,
 val apparentWindSpeedKnots: Double? = null,
 val headingSampleSequence: Long? = null, val windSampleSequence: Long? = null,
)
enum class GpsDataSource { SYSTEM, NMEA, DEMO }
enum class AppLanguage { SYSTEM, ENGLISH, SIMPLIFIED_CHINESE }
enum class AlarmSound { SYSTEM_ALARM, SYSTEM_RINGTONE, SYSTEM_NOTIFICATION, CUSTOM }
enum class DemoScenario { SAFE_SWING, ANCHOR_DRAG, WIND_SHIFT, GPS_DROPOUT }
enum class AnchorPlacementMode { CENTER_DROP, BACKDOWN }
enum class AnchorCenterStatus { LEARNING, RESOLVED }
enum class AnchorRangeMode { BASIC, ADVANCED }
enum class AnchorSafetyPreset { STRICT, BALANCED, TOLERANT }
enum class NmeaConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CONNECTED_NO_DATA, CONNECTED_NO_FIX, STALE, RECONNECTING, ERROR }
enum class AlarmState { IDLE, SETTING, LEARNING, ARMED, WARNING, ALARM, ACKNOWLEDGED, STOPPED }
enum class AlarmType { ANCHOR_RADIUS_EXCEEDED, GPS_DATA_LOST, NMEA_CONNECTION_LOST, GPS_QUALITY_BAD, MOCK_GPS_FAILED }
enum class Confidence { LOW, MEDIUM, HIGH }
data class AnchorEstimate(val latitude: Double,val longitude: Double,val radiusMeters: Double,val confidence: Confidence,val rmsErrorMeters: Double,val angularCoverageDegrees: Double,val sampleCount: Int)
data class BackdownAnchorEstimate(val latitude:Double,val longitude:Double,val distanceMeters:Double,val uncertaintyRadiusMeters:Double,val confidence:Confidence,val sampleCount:Int,val angularCoverageDegrees:Double=0.0,val angularSectorCount:Int=0)
data class AnchorConfig(val latitude: Double,val longitude: Double,val rodeLengthMeters: Double,val waterDepthMeters: Double?=null,val bowRollerHeightMeters: Double=0.0,val gpsAntennaOffsetMeters: Double=0.0,val warningRadiusMeters: Double,val alarmRadiusMeters: Double)
data class AlarmSnapshot(val state: AlarmState=AlarmState.IDLE,val type: AlarmType?=null,val distanceMeters: Double?=null,val maxDistanceMeters: Double=0.0,val minDistanceMeters: Double?=null,val averageDistanceMeters: Double?=null,val acknowledged:Boolean=false)
