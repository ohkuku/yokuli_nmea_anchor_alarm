package com.yokuli.anchorwatch.data.nmea

import com.yokuli.anchorwatch.domain.vessel.VesselMetricId

enum class NmeaInvalidationReason { EXPLICIT_INVALID_STATUS }

/** A negative-validity sentence is evidence, not a missing update. */
data class NmeaSourceInvalidation(
    val sourceId:String,
    val affectedMetrics:Set<VesselMetricId>,
    val reason:NmeaInvalidationReason,
    val elapsedRealtime:Long,
    val transportProfileId:String,
    val connectionGeneration:Long,
    val fullSentenceId:String,
)

object NmeaInvalidationPolicy {
    fun affectedMetrics(sentenceType:String):Set<VesselMetricId> = when(sentenceType.uppercase()){
        "RMC"->setOf(VesselMetricId.POSITION,VesselMetricId.SOG,VesselMetricId.COG)
        "GGA","GNS"->setOf(VesselMetricId.POSITION)
        "GLL"->setOf(VesselMetricId.POSITION)
        "MWV"->setOf(VesselMetricId.APPARENT_WIND_ANGLE,VesselMetricId.APPARENT_WIND_SPEED,VesselMetricId.TRUE_WIND_ANGLE,VesselMetricId.TRUE_WIND_SPEED)
        "ROT"->setOf(VesselMetricId.RATE_OF_TURN)
        "RSA"->setOf(VesselMetricId.RUDDER_ANGLE)
        "RMB"->setOf(VesselMetricId.XTE,VesselMetricId.WAYPOINT_BEARING,VesselMetricId.WAYPOINT_DISTANCE,VesselMetricId.DESTINATION_WAYPOINT)
        "XTE","APB"->setOf(VesselMetricId.XTE,VesselMetricId.WAYPOINT_BEARING,VesselMetricId.DESTINATION_WAYPOINT)
        else->emptySet()
    }
}
