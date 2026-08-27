package com.yokuli.anchorwatch.data.backup

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity

/** V2 extends, but never mutates, the stable V1 session contract. */
data class BackupAnchorSessionV2(
    val base:BackupAnchorSessionV1,
    val depthGuardEnabled:Boolean=false,val shallowDepthAlarmMeters:Double?=null,val deepDepthAlarmMeters:Double?=null,
    val windGuardEnabled:Boolean=false,val windWarningKnots:Double?=null,val windAlarmKnots:Double?=null,
    val windShiftEnabled:Boolean=false,val windShiftThresholdDegrees:Double?=null,val windAllowApparentFallback:Boolean=true,
    val windBaselineDirectionDegrees:Double?=null,val windBaselineEstablishedAt:Long?=null,val windBaselineSource:String?=null,
    val depthAlarmSnoozedUntil:Long?=null,val windAlarmSnoozedUntil:Long?=null,val windShiftAlarmSnoozedUntil:Long?=null,
    val minObservedDepthMeters:Double?=null,val maxObservedDepthMeters:Double?=null,val maxObservedWindKnots:Double?=null,val maxObservedWindSource:String?=null,
    val depthAlarmCount:Int=0,val windAlarmCount:Int=0,val savedAnchorageId:Long?=null,
    val candidateTrackDiameterMeters:Double=0.0,val candidateFittedRadiusMeters:Double?=null,
    val candidateMaximumRodeMeters:Double=0.0,val candidateGpsMarginMeters:Double=0.0,
    val candidateRadialObservable:Boolean=false,val candidateObservabilityReason:String="NO_USABLE_CIRCLE_FIT",
    val headingEvidenceEnabled:Boolean=false,val headingEvidenceEpoch:Long=0L,
    val headingEvidenceEnabledAt:Long?=null,val headingEvidenceSourceId:String?=null,
    // Nullable for JSON compatibility: Gson does not invoke Kotlin defaults
    // when these fields are absent from a backup produced before schema 21.
    val anchorOriginMode:String?=null,val monitoringPhase:String?=null,val monitoringActivatedAt:Long?=null,
){
    fun toEntity():AnchorSessionEntity{
        val legacy=base.toEntity()
        val restoredOrigin=anchorOriginMode?:when{
            legacy.placementMode=="BACKDOWN"->"BACKDOWN_FROM_ACCEPTED_POSITION"
            legacy.centerSource=="MANUAL_COORDINATES"->"MANUAL_COORDINATE"
            legacy.centerSource=="MAP_PICK"->"MAP_PICK"
            else->"CURRENT_ACCEPTED_POSITION"
        }
        val restoredPhase=monitoringPhase?:when{
            !legacy.active||legacy.endedAt!=null->"ENDED"
            legacy.paused->"PAUSED"
            legacy.placementMode=="BACKDOWN"&&legacy.centerStatus!="RESOLVED"->"LEARNING"
            else->"ARMED"
        }
        return legacy.copy(depthGuardEnabled=depthGuardEnabled,shallowDepthAlarmMeters=shallowDepthAlarmMeters,deepDepthAlarmMeters=deepDepthAlarmMeters,windGuardEnabled=windGuardEnabled,windWarningKnots=windWarningKnots,windAlarmKnots=windAlarmKnots,windShiftEnabled=windShiftEnabled,windShiftThresholdDegrees=windShiftThresholdDegrees,windAllowApparentFallback=windAllowApparentFallback,windBaselineDirectionDegrees=windBaselineDirectionDegrees,windBaselineEstablishedAt=windBaselineEstablishedAt,windBaselineSource=windBaselineSource,depthAlarmSnoozedUntil=depthAlarmSnoozedUntil,windAlarmSnoozedUntil=windAlarmSnoozedUntil,windShiftAlarmSnoozedUntil=windShiftAlarmSnoozedUntil,minObservedDepthMeters=minObservedDepthMeters,maxObservedDepthMeters=maxObservedDepthMeters,maxObservedWindKnots=maxObservedWindKnots,maxObservedWindSource=maxObservedWindSource,depthAlarmCount=depthAlarmCount,windAlarmCount=windAlarmCount,savedAnchorageId=savedAnchorageId,candidateTrackDiameterMeters=candidateTrackDiameterMeters,candidateFittedRadiusMeters=candidateFittedRadiusMeters,candidateMaximumRodeMeters=candidateMaximumRodeMeters,candidateGpsMarginMeters=candidateGpsMarginMeters,candidateRadialObservable=candidateRadialObservable,candidateObservabilityReason=candidateObservabilityReason,headingEvidenceEnabled=headingEvidenceEnabled,headingEvidenceEpoch=headingEvidenceEpoch,headingEvidenceEnabledAt=headingEvidenceEnabledAt,headingEvidenceSourceId=headingEvidenceSourceId,usePhoneHeading=headingEvidenceEnabled,anchorOriginMode=restoredOrigin,monitoringPhase=restoredPhase,monitoringActivatedAt=monitoringActivatedAt?:legacy.startedAt.takeIf{restoredPhase in setOf("ARMED","LEARNING")})
    }
    companion object{fun from(value:AnchorSessionEntity)=BackupAnchorSessionV2(BackupAnchorSessionV1.from(value),value.depthGuardEnabled,value.shallowDepthAlarmMeters,value.deepDepthAlarmMeters,value.windGuardEnabled,value.windWarningKnots,value.windAlarmKnots,value.windShiftEnabled,value.windShiftThresholdDegrees,value.windAllowApparentFallback,value.windBaselineDirectionDegrees,value.windBaselineEstablishedAt,value.windBaselineSource,value.depthAlarmSnoozedUntil,value.windAlarmSnoozedUntil,value.windShiftAlarmSnoozedUntil,value.minObservedDepthMeters,value.maxObservedDepthMeters,value.maxObservedWindKnots,value.maxObservedWindSource,value.depthAlarmCount,value.windAlarmCount,value.savedAnchorageId,value.candidateTrackDiameterMeters,value.candidateFittedRadiusMeters,value.candidateMaximumRodeMeters,value.candidateGpsMarginMeters,value.candidateRadialObservable,value.candidateObservabilityReason,value.headingEvidenceEnabled,value.headingEvidenceEpoch,value.headingEvidenceEnabledAt,value.headingEvidenceSourceId,value.anchorOriginMode,value.monitoringPhase,value.monitoringActivatedAt)}
}

data class BackupSavedAnchorageV2(
    val id:Long=0,val name:String="",val latitude:Double=0.0,val longitude:Double=0.0,val createdAt:Long=0,val updatedAt:Long=0,val lastVisitedAt:Long?=null,val visitCount:Int=0,val preferredAlarmRadiusMeters:Double?=null,val typicalWaterDepthMeters:Double?=null,val typicalRodeLengthMeters:Double?=null,val seabedType:String="UNKNOWN",val customSeabedText:String?=null,val rating:Int?=null,val notes:String="",val sourceSessionId:Long?=null,
    val coordinateSource:String="CONFIRMED_ANCHOR",val coordinateUncertaintyMeters:Double?=null,
){
    fun toEntity()=SavedAnchorageEntity(id,name,latitude,longitude,createdAt,updatedAt,lastVisitedAt,visitCount,preferredAlarmRadiusMeters,typicalWaterDepthMeters,typicalRodeLengthMeters,seabedType,customSeabedText,rating,notes,sourceSessionId,coordinateSource,coordinateUncertaintyMeters)
    companion object{fun from(value:SavedAnchorageEntity)=BackupSavedAnchorageV2(value.id,value.name,value.latitude,value.longitude,value.createdAt,value.updatedAt,value.lastVisitedAt,value.visitCount,value.preferredAlarmRadiusMeters,value.typicalWaterDepthMeters,value.typicalRodeLengthMeters,value.seabedType,value.customSeabedText,value.rating,value.notes,value.sourceSessionId,value.coordinateSource,value.coordinateUncertaintyMeters)}
}
