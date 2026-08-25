package com.yokuli.anchorwatch.domain.vessel

import com.yokuli.anchorwatch.domain.sonar.DepthReference

enum class VesselMetricId{
    POSITION,SOG,COG,HEADING_TRUE,HEADING_MAGNETIC,DEVICE_HEADING_TRUE,DEVICE_HEADING_MAGNETIC,
    SPEED_THROUGH_WATER,DEPTH,UKC,APPARENT_WIND_ANGLE,APPARENT_WIND_SPEED,TRUE_WIND_ANGLE,
    TRUE_WIND_SPEED,TRUE_WIND_DIRECTION,RATE_OF_TURN,RUDDER_ANGLE,HEEL,PITCH,ROLL_RATE,PITCH_RATE,YAW_RATE,
    PRESSURE,WATER_TEMPERATURE,AIR_TEMPERATURE,CURRENT_SET,CURRENT_DRIFT,XTE,WAYPOINT_BEARING,
    WAYPOINT_DISTANCE,DESTINATION_WAYPOINT,TOTAL_LOG,TRIP_LOG,VMG_WIND,VMC_WAYPOINT,MOTION_SCORE,ROLL_PERIOD,
}

enum class VesselSourceType{NMEA_INPUT,PHONE_SENSOR,APP_DERIVED,PHONE_TX_ECHO,DEMO}
enum class VesselSourceClass{NONE,BOAT_NMEA,PHONE_GNSS,PHONE_DEVICE_COMPASS,PHONE_VESSEL_HEADING,PHONE_IMU,PHONE_BAROMETER,DERIVED_WATER,DERIVED_GROUND,DEMO}

data class VesselSourceIdentity(
    val id:String,
    val transportProfileId:String?=null,
    val connectionGeneration:Long?=null,
    val sourceType:VesselSourceType,
    val talkerId:String?=null,
    val sentenceType:String?=null,
    val fullSentenceId:String?=null,
    val transducerName:String?=null,
    val phoneSensorType:String?=null,
    val displayName:String,
    /** Physical-source key persisted across transport reconnect generations. */
    val stableKey:String=id,
)

object VesselSourcePinPolicy{
    private val generatedNmeaKey=Regex("^nmea:([^:]+):([0-9]+):(.*)$")
    fun normalize(key:String):String=generatedNmeaKey.matchEntire(key)?.let{match->"nmea:${match.groupValues[1]}:${match.groupValues[3]}"}?:key
    fun matches(source:VesselSourceIdentity,stored:String)=normalize(source.stableKey)==normalize(stored)||source.id==stored||source.fullSentenceId==stored
    fun resolve(candidates:List<VesselSourceCandidate<*>>,stored:String):String?=candidates.firstOrNull{matches(it.source,stored)}?.source?.id
}

val VesselSourceIdentity.persistentKey:String get()=VesselSourcePinPolicy.normalize(stableKey)

sealed interface VesselReference{
    data object TrueNorth:VesselReference
    data object MagneticNorth:VesselReference
    data object WaterReferenced:VesselReference
    data object GroundReferenced:VesselReference
    /** Angle measured relative to the calibrated vessel bow. */
    data object VesselRelative:VesselReference
    data class Depth(val reference:DepthReference):VesselReference
}

sealed interface VesselProvenance{
    data class Nmea(val source:VesselSourceIdentity):VesselProvenance
    data class PhoneSensor(val sensor:String,val calibrationVersion:Int?=null):VesselProvenance
    data class Derived(val algorithm:String,val inputs:List<VesselSourceIdentity>):VesselProvenance
}

enum class CandidateValidity{ELIGIBLE,LOW_QUALITY,STALE,INVALID,DISABLED}
data class VesselSourceCandidate<T>(
    val metric:VesselMetricId,
    val value:T,
    val source:VesselSourceIdentity,
    val sourceClass:VesselSourceClass,
    val reference:VesselReference?=null,
    val receivedElapsedRealtime:Long,
    val observedAtUtcMillis:Long?=null,
    val quality:VesselDataQuality=VesselDataQuality.GOOD,
    val validity:CandidateValidity=CandidateValidity.ELIGIBLE,
    val provenance:VesselProvenance?=null,
    val sourceHeartbeatElapsedRealtime:Long=receivedElapsedRealtime,
){
    /** Monotonic time of the last complete numeric measurement. Kept as an
     * explicit semantic alias while the persisted/public API still calls the
     * field receivedElapsedRealtime. It must never be replaced by a blank
     * same-source heartbeat. */
    val measuredElapsedRealtime:Long get()=receivedElapsedRealtime

    /** Explicit negative validity is authoritative and cannot be extended by
     * a transport or same-sentence heartbeat. */
    val explicitValidity:CandidateValidity get()=validity
}

data class VesselSourceConflict(
    val active:Boolean=false,
    val selectedSource:VesselSourceIdentity?=null,
    val conflictingSources:List<VesselSourceIdentity> = emptyList(),
    val message:String="",
)

data class VesselSourceSelection<T>(
    val selected:VesselSourceCandidate<T>?=null,
    val candidates:List<VesselSourceCandidate<T>> = emptyList(),
    val conflict:VesselSourceConflict=VesselSourceConflict(),
    val reason:String="NO_ELIGIBLE_SOURCE",
    val pinnedSourceUnavailable:Boolean=false,
)

fun VesselDataSource.toSourceClass()=when(this){
    VesselDataSource.BOAT_NMEA->VesselSourceClass.BOAT_NMEA
    VesselDataSource.PHONE_GNSS->VesselSourceClass.PHONE_GNSS
    VesselDataSource.PHONE_IMU->VesselSourceClass.PHONE_IMU
    VesselDataSource.PHONE_MAGNETOMETER->VesselSourceClass.PHONE_DEVICE_COMPASS
    VesselDataSource.PHONE_BAROMETER->VesselSourceClass.PHONE_BAROMETER
    VesselDataSource.DERIVED->VesselSourceClass.DERIVED_WATER
    VesselDataSource.DEMO->VesselSourceClass.DEMO
    VesselDataSource.NONE->VesselSourceClass.NONE
}

fun VesselSourceClass.toLegacySource()=when(this){
    VesselSourceClass.BOAT_NMEA->VesselDataSource.BOAT_NMEA
    VesselSourceClass.PHONE_GNSS->VesselDataSource.PHONE_GNSS
    VesselSourceClass.PHONE_DEVICE_COMPASS,VesselSourceClass.PHONE_VESSEL_HEADING->VesselDataSource.PHONE_MAGNETOMETER
    VesselSourceClass.PHONE_IMU->VesselDataSource.PHONE_IMU
    VesselSourceClass.PHONE_BAROMETER->VesselDataSource.PHONE_BAROMETER
    VesselSourceClass.DERIVED_WATER,VesselSourceClass.DERIVED_GROUND->VesselDataSource.DERIVED
    VesselSourceClass.DEMO->VesselDataSource.DEMO
    VesselSourceClass.NONE->VesselDataSource.NONE
}
