package com.yokuli.anchorwatch.data.nmea.input

import com.yokuli.anchorwatch.data.nmea.NmeaFieldObservation
import com.yokuli.anchorwatch.data.nmea.NmeaFieldSemantic
import com.yokuli.anchorwatch.domain.vessel.*

/** Maps the extended NMEA field bus into the same identity-preserving registry
 * as core navigation sentences. No semantic field is selected at ingest time. */
object NmeaFieldCandidateMapper{
    fun map(field:NmeaFieldObservation,profileId:String,generation:Long):VesselSourceCandidate<*>?{
        val metric=when(field.key.semantic){
            NmeaFieldSemantic.ROT->VesselMetricId.RATE_OF_TURN
            NmeaFieldSemantic.RUDDER_ANGLE->VesselMetricId.RUDDER_ANGLE
            NmeaFieldSemantic.HEEL->VesselMetricId.HEEL
            NmeaFieldSemantic.PITCH->VesselMetricId.PITCH
            NmeaFieldSemantic.WATER_TEMPERATURE->VesselMetricId.WATER_TEMPERATURE
            NmeaFieldSemantic.AIR_TEMPERATURE->VesselMetricId.AIR_TEMPERATURE
            NmeaFieldSemantic.AIR_PRESSURE->VesselMetricId.PRESSURE
            NmeaFieldSemantic.CURRENT_SET_TRUE->VesselMetricId.CURRENT_SET
            NmeaFieldSemantic.CURRENT_DRIFT->VesselMetricId.CURRENT_DRIFT
            NmeaFieldSemantic.CROSS_TRACK_ERROR->VesselMetricId.XTE
            NmeaFieldSemantic.BEARING_TO_WAYPOINT->VesselMetricId.WAYPOINT_BEARING
            NmeaFieldSemantic.DISTANCE_TO_WAYPOINT->VesselMetricId.WAYPOINT_DISTANCE
            NmeaFieldSemantic.DESTINATION_WAYPOINT->VesselMetricId.DESTINATION_WAYPOINT
            NmeaFieldSemantic.TOTAL_LOG->VesselMetricId.TOTAL_LOG
            NmeaFieldSemantic.TRIP_LOG->VesselMetricId.TRIP_LOG
            NmeaFieldSemantic.APPARENT_WIND_ANGLE->VesselMetricId.APPARENT_WIND_ANGLE
            NmeaFieldSemantic.APPARENT_WIND_SPEED->VesselMetricId.APPARENT_WIND_SPEED
            NmeaFieldSemantic.TRUE_WIND_ANGLE->VesselMetricId.TRUE_WIND_ANGLE
            NmeaFieldSemantic.TRUE_WIND_SPEED->VesselMetricId.TRUE_WIND_SPEED
            NmeaFieldSemantic.TRUE_WIND_DIRECTION->VesselMetricId.TRUE_WIND_DIRECTION
            NmeaFieldSemantic.RAW_ANGULAR,NmeaFieldSemantic.RAW->return null
        }
        val value:Any=field.value?:field.text?:return null
        val source=VesselSourceIdentity(
            id="nmea:$profileId:$generation:field:${field.key.stableId}",
            transportProfileId=profileId,connectionGeneration=generation,sourceType=VesselSourceType.NMEA_INPUT,
            talkerId=field.key.talker,sentenceType=field.key.sentenceType,fullSentenceId="${field.key.talker}${field.key.sentenceType}",
            transducerName=field.key.transducerName,
            displayName=listOfNotNull("${field.key.talker}${field.key.sentenceType}",field.key.transducerName).joinToString(" · "),
        )
        val reference=when(field.key.semantic){
            NmeaFieldSemantic.CURRENT_SET_TRUE,NmeaFieldSemantic.BEARING_TO_WAYPOINT,NmeaFieldSemantic.TRUE_WIND_DIRECTION->VesselReference.TrueNorth
            else->null
        }
        return VesselSourceCandidate(metric,value,source,VesselSourceClass.BOAT_NMEA,reference,field.receivedElapsedRealtime,quality=VesselDataQuality.GOOD,provenance=VesselProvenance.Nmea(source))
    }
}
