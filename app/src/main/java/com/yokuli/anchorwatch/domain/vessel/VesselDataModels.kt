package com.yokuli.anchorwatch.domain.vessel

enum class VesselDataSource { NONE, BOAT_NMEA, PHONE_GNSS, PHONE_IMU, PHONE_MAGNETOMETER, PHONE_BAROMETER, DERIVED, DEMO }
enum class VesselDataQuality { UNKNOWN, DEGRADED, GOOD }
enum class VesselDataFreshness { FRESH, HELD, STALE, UNAVAILABLE }
enum class VesselSourcePreference { AUTO, BOAT, PHONE, DERIVED }
enum class WatchWorkspaceMode { ANCHOR, TRIP }
enum class TripInstrumentPreset { SAILING, NAV, MOTION, WEATHER, CUSTOM }
enum class InstrumentTileId {
    SOG, COG, HEADING, DEPTH, UKC, POSITION, BOAT_SPEED, TRUE_WIND_SPEED, TRUE_WIND_DIRECTION,
    APPARENT_WIND_SPEED, APPARENT_WIND_ANGLE, TRUE_WIND_ANGLE,
    HEEL, PITCH, ROLL_RATE, PITCH_RATE, ROLL_PERIOD, MOTION_SCORE,
    IMPACT_COUNT, PRESSURE, PRESSURE_TREND_1H, PRESSURE_TREND_3H,
    PRESSURE_TREND_6H, RATE_OF_TURN, RUDDER_ANGLE, WATER_TEMPERATURE,
    AIR_TEMPERATURE, CURRENT_SET, CURRENT_DRIFT, CROSS_TRACK_ERROR,
    WAYPOINT_BEARING, WAYPOINT_DISTANCE, TOTAL_LOG, TRIP_LOG, VMG, VMC,
}

object InstrumentLayoutPolicy {
    fun defaults(preset:TripInstrumentPreset):List<InstrumentTileId> = when(preset){
        TripInstrumentPreset.NAV->listOf(InstrumentTileId.SOG,InstrumentTileId.COG,InstrumentTileId.HEADING,InstrumentTileId.BOAT_SPEED,InstrumentTileId.DEPTH,InstrumentTileId.UKC,InstrumentTileId.POSITION,InstrumentTileId.WAYPOINT_BEARING,InstrumentTileId.WAYPOINT_DISTANCE,InstrumentTileId.CROSS_TRACK_ERROR)
        TripInstrumentPreset.SAILING->listOf(InstrumentTileId.APPARENT_WIND_SPEED,InstrumentTileId.APPARENT_WIND_ANGLE,InstrumentTileId.TRUE_WIND_SPEED,InstrumentTileId.TRUE_WIND_DIRECTION,InstrumentTileId.TRUE_WIND_ANGLE,InstrumentTileId.BOAT_SPEED,InstrumentTileId.SOG,InstrumentTileId.VMG,InstrumentTileId.HEEL)
        TripInstrumentPreset.MOTION->listOf(InstrumentTileId.HEEL,InstrumentTileId.PITCH,InstrumentTileId.ROLL_RATE,InstrumentTileId.PITCH_RATE,InstrumentTileId.ROLL_PERIOD,InstrumentTileId.MOTION_SCORE,InstrumentTileId.IMPACT_COUNT)
        TripInstrumentPreset.WEATHER->listOf(InstrumentTileId.PRESSURE,InstrumentTileId.PRESSURE_TREND_1H,InstrumentTileId.PRESSURE_TREND_3H,InstrumentTileId.PRESSURE_TREND_6H,InstrumentTileId.AIR_TEMPERATURE,InstrumentTileId.WATER_TEMPERATURE)
        TripInstrumentPreset.CUSTOM->emptyList()
    }
    fun catalog(preset:TripInstrumentPreset):List<InstrumentTileId> = if(preset==TripInstrumentPreset.CUSTOM)InstrumentTileId.entries else defaults(preset)
    fun normalized(preset:TripInstrumentPreset,ids:List<InstrumentTileId>):List<InstrumentTileId>{
        val allowed=catalog(preset).toSet()
        return ids.distinct().filter{it in allowed}
    }
}

data class VesselPosition(
    val latitude:Double,
    val longitude:Double,
    val altitudeMeters:Double?=null,
    val horizontalAccuracyMeters:Double?=null,
    val satellites:Int?=null,
    val hdop:Double?=null,
)

data class VesselWindObservation(
    val speedKnots:VesselObservation<Double> = VesselObservation(),
    val directionDegrees:VesselObservation<Double> = VesselObservation(),
    val angleDegrees:VesselObservation<Double> = VesselObservation(),
)

data class VesselAttitude(
    val heelDegrees:Double,
    val pitchDegrees:Double,
    val rollRateDegreesPerSecond:Double,
    val pitchRateDegreesPerSecond:Double,
    val yawRateDegreesPerSecond:Double,
)

enum class MotionPeriodConfidence { UNAVAILABLE, LOW, MEDIUM, HIGH }
data class VesselMotion(
    val score:Double?=null,
    val rollRmsDegrees:Double?=null,
    val pitchRmsDegrees:Double?=null,
    val rollRateRmsDegreesPerSecond:Double?=null,
    val pitchRateRmsDegreesPerSecond:Double?=null,
    val verticalAccelerationRmsG:Double?=null,
    val dominantRollPeriodSeconds:Double?=null,
    val rollPeriodConfidence:MotionPeriodConfidence=MotionPeriodConfidence.UNAVAILABLE,
    val impactCandidateElapsedRealtime:Long?=null,
    val impactPeakG:Double?=null,
    val impactCandidateCount:Int=0,
)

data class VesselDerivedSnapshot(
    val underKeelClearanceMeters:VesselObservation<Double> = VesselObservation(),
    val headingCogDifferenceDegrees:VesselObservation<Double> = VesselObservation(),
    val pressureTrend1hHpa:VesselObservation<Double> = VesselObservation(),
    val pressureTrend3hHpa:VesselObservation<Double> = VesselObservation(),
    val pressureTrend6hHpa:VesselObservation<Double> = VesselObservation(),
    val vmgToWindKnots:VesselObservation<Double> = VesselObservation(),
    val vmcToWaypointKnots:VesselObservation<Double> = VesselObservation(),
    val estimatedCurrentSetTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val estimatedCurrentDriftKnots:VesselObservation<Double> = VesselObservation(),
)

data class VesselObservation<T>(
    val value:T?=null,
    val source:VesselDataSource=VesselDataSource.NONE,
    val observedAtUtcMillis:Long?=null,
    val receivedElapsedRealtime:Long?=null,
    val quality:VesselDataQuality=VesselDataQuality.UNKNOWN,
    val freshness:VesselDataFreshness=VesselDataFreshness.UNAVAILABLE,
    val provenance:String?=null,
    val sourceIdentity:VesselSourceIdentity?=null,
    val sourceClass:VesselSourceClass=source.toSourceClass(),
    val reference:VesselReference?=null,
    val provenanceDetail:VesselProvenance?=null,
    val conflict:VesselSourceConflict?=null,
    val sourceHeartbeatElapsedRealtime:Long?=receivedElapsedRealtime,
    val selectionReason:String?=null,
)

data class VesselDataSnapshot(
    val position:VesselObservation<VesselPosition> = VesselObservation(),
    val sogKnots:VesselObservation<Double> = VesselObservation(),
    val cogTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val headingTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val headingMagneticDegrees:VesselObservation<Double> = VesselObservation(),
    val depthMeters:VesselObservation<Double> = VesselObservation(),
    val speedThroughWaterKnots:VesselObservation<Double> = VesselObservation(),
    val trueWind:VesselWindObservation = VesselWindObservation(),
    val apparentWind:VesselWindObservation = VesselWindObservation(),
    val attitude:VesselObservation<VesselAttitude> = VesselObservation(),
    val motion:VesselObservation<VesselMotion> = VesselObservation(),
    val pressureHpa:VesselObservation<Double> = VesselObservation(),
    val rateOfTurnDegreesPerMinute:VesselObservation<Double> = VesselObservation(),
    val rudderAngleDegrees:VesselObservation<Double> = VesselObservation(),
    val waterTemperatureCelsius:VesselObservation<Double> = VesselObservation(),
    val airTemperatureCelsius:VesselObservation<Double> = VesselObservation(),
    val currentSetTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val currentDriftKnots:VesselObservation<Double> = VesselObservation(),
    val crossTrackErrorNauticalMiles:VesselObservation<Double> = VesselObservation(),
    val waypointBearingTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val waypointDistanceNauticalMiles:VesselObservation<Double> = VesselObservation(),
    val destinationWaypoint:VesselObservation<String> = VesselObservation(),
    val totalLogNauticalMiles:VesselObservation<Double> = VesselObservation(),
    val tripLogNauticalMiles:VesselObservation<Double> = VesselObservation(),
    val derived:VesselDerivedSnapshot = VesselDerivedSnapshot(),
    val deviceHeadingTrueDegrees:VesselObservation<Double> = VesselObservation(),
    val deviceHeadingMagneticDegrees:VesselObservation<Double> = VesselObservation(),
    val candidates:Map<VesselMetricId,List<VesselSourceCandidate<*>>> = emptyMap(),
    val conflicts:Map<VesselMetricId,VesselSourceConflict> = emptyMap(),
    val generatedElapsedRealtime:Long=0L,
)

object VesselSourceSelector {
    fun <T> select(preference:VesselSourcePreference,boat:VesselObservation<T>,phone:VesselObservation<T>):VesselObservation<T> = when(preference){
        VesselSourcePreference.BOAT->boat
        VesselSourcePreference.PHONE->phone
        VesselSourcePreference.DERIVED->VesselObservation()
        VesselSourcePreference.AUTO->when{
            boat.freshness==VesselDataFreshness.FRESH&&boat.quality==VesselDataQuality.GOOD->boat
            phone.freshness==VesselDataFreshness.FRESH&&phone.quality!=VesselDataQuality.UNKNOWN->phone
            boat.value!=null&&boat.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)->boat
            phone.value!=null&&phone.freshness in setOf(VesselDataFreshness.FRESH,VesselDataFreshness.HELD)->phone
            boat.value!=null->boat
            else->phone
        }
    }
}

/** Stateful AUTO selector. Explicit BOAT/PHONE choices remain immediate. */
class VesselAutoSourceSelector<T>(
    private val alternativeStableMillis:Long=2_000L,
    private val boatRecoveryStableMillis:Long=5_000L,
){
    private var selectedSource=VesselDataSource.NONE
    private var candidateSource=VesselDataSource.NONE
    private var candidateSince:Long?=null

    fun select(preference:VesselSourcePreference,boat:VesselObservation<T>,phone:VesselObservation<T>,nowElapsed:Long):VesselObservation<T>{
        if(preference!=VesselSourcePreference.AUTO){resetCandidate();selectedSource=if(preference==VesselSourcePreference.BOAT)VesselDataSource.BOAT_NMEA else phone.source.takeIf{it!=VesselDataSource.NONE}?:VesselDataSource.PHONE_GNSS;return when(preference){VesselSourcePreference.BOAT->boat;VesselSourcePreference.PHONE->phone;else->VesselObservation()}}
        val desired=VesselSourceSelector.select(VesselSourcePreference.AUTO,boat,phone)
        if(selectedSource==VesselDataSource.NONE){selectedSource=desired.source;resetCandidate();return desired}
        val current=when(selectedSource){VesselDataSource.BOAT_NMEA->boat;VesselDataSource.PHONE_GNSS,VesselDataSource.PHONE_MAGNETOMETER->phone;else->desired}
        if(desired.source==VesselDataSource.NONE||desired.source==selectedSource){resetCandidate();return current}
        if(candidateSource!=desired.source){candidateSource=desired.source;candidateSince=nowElapsed;return current}
        val required=if(desired.source==VesselDataSource.BOAT_NMEA)boatRecoveryStableMillis else alternativeStableMillis
        if(nowElapsed-(candidateSince?:nowElapsed)>=required){selectedSource=desired.source;resetCandidate();return desired}
        return current
    }

    fun reset(){selectedSource=VesselDataSource.NONE;resetCandidate()}
    private fun resetCandidate(){candidateSource=VesselDataSource.NONE;candidateSince=null}
}

object VesselFreshnessPolicy {
    fun <T> classify(
        observation:VesselObservation<T>,
        nowElapsed:Long,
        freshForMillis:Long,
        heldForMillis:Long,
    ):VesselObservation<T>{
        if(observation.value==null||observation.receivedElapsedRealtime==null)return observation.copy(freshness=VesselDataFreshness.UNAVAILABLE)
        val age=nowElapsed-observation.receivedElapsedRealtime
        val freshness=when{
            age<0L->VesselDataFreshness.STALE
            age<=freshForMillis->VesselDataFreshness.FRESH
            age<=heldForMillis->VesselDataFreshness.HELD
            else->VesselDataFreshness.STALE
        }
        return observation.copy(freshness=freshness)
    }

    /** A missing field means "not updated by this sentence", never "erase". */
    fun <T> retain(previous:VesselObservation<T>,next:VesselObservation<T>?):VesselObservation<T> =
        next?.takeIf{it.value!=null}?:previous
}
