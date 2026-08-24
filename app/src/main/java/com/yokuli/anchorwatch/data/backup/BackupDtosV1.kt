package com.yokuli.anchorwatch.data.backup

import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity

/** Stable archive DTOs. Room entities may evolve without changing backup v1 JSON. */
data class BackupAnchorSessionV1(
    val id:Long=0,val startedAt:Long=0,val endedAt:Long?=null,
    val anchorLatitude:Double=0.0,val anchorLongitude:Double=0.0,val rodeLengthMeters:Double=0.0,
    val waterDepthMeters:Double?=null,val bowRollerHeightMeters:Double=0.0,val gpsAntennaOffsetMeters:Double=0.0,
    val expectedSwingRadiusMeters:Double=0.0,val warningRadiusMeters:Double=0.0,val alarmRadiusMeters:Double=0.0,
    val active:Boolean=true,val paused:Boolean=false,val placementMode:String="CENTER_DROP",val centerStatus:String="RESOLVED",
    val centerResolvedAt:Long?=null,val centerConfidence:String="HIGH",val centerSampleCount:Int=0,val boatLengthMeters:Double?=null,
    val rangeMode:String="BASIC",val safetyPreset:String="BALANCED",val alarmSnoozedUntil:Long?=null,
    val learningReferenceLatitude:Double?=null,val learningReferenceLongitude:Double?=null,
    val provisionalAnchorLatitude:Double?=null,val provisionalAnchorLongitude:Double?=null,val provisionalRadiusMeters:Double?=null,
    val positionSource:String="UNKNOWN",val anchorPositionMode:String="KNOWN",val centerSource:String="UNKNOWN",val usePhoneHeading:Boolean=false,
    val candidateId:Long?=null,val candidateCreatedAt:Long?=null,val candidateDecision:String="NONE",val candidateNotificationShown:Boolean=false,
    val candidateRmsErrorMeters:Double?=null,val candidateAngularCoverageDegrees:Double?=null,val candidateAngularSectorCount:Int=0,
    val candidateSwingReversalCount:Int=0,val candidateTemporalFitConsistent:Boolean=false,val candidateEffectiveDurationMillis:Long=0,
    val candidateDirectionEvidenceConsistent:Boolean=false,val maxDistanceMeters:Double=0.0,val alarmCount:Int=0,
    val estimationEpoch:Long=0,val estimationEpochStartedAt:Long?=null,val adoptedCenterEpoch:Long=0,val latestEstimateEpoch:Long=0,
){
    fun toEntity()=AnchorSessionEntity(id,startedAt,endedAt,anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,expectedSwingRadiusMeters,warningRadiusMeters,alarmRadiusMeters,active,paused,placementMode,centerStatus,centerResolvedAt,centerConfidence,centerSampleCount,boatLengthMeters,rangeMode,safetyPreset,alarmSnoozedUntil,learningReferenceLatitude,learningReferenceLongitude,provisionalAnchorLatitude,provisionalAnchorLongitude,provisionalRadiusMeters,positionSource,anchorPositionMode,centerSource,usePhoneHeading,candidateId,candidateCreatedAt,candidateDecision,candidateNotificationShown,candidateRmsErrorMeters,candidateAngularCoverageDegrees,candidateAngularSectorCount,candidateSwingReversalCount,candidateTemporalFitConsistent,candidateEffectiveDurationMillis,candidateDirectionEvidenceConsistent,maxDistanceMeters,alarmCount,estimationEpoch,estimationEpochStartedAt,adoptedCenterEpoch,latestEstimateEpoch)
    companion object{fun from(value:AnchorSessionEntity)=BackupAnchorSessionV1(value.id,value.startedAt,value.endedAt,value.anchorLatitude,value.anchorLongitude,value.rodeLengthMeters,value.waterDepthMeters,value.bowRollerHeightMeters,value.gpsAntennaOffsetMeters,value.expectedSwingRadiusMeters,value.warningRadiusMeters,value.alarmRadiusMeters,value.active,value.paused,value.placementMode,value.centerStatus,value.centerResolvedAt,value.centerConfidence,value.centerSampleCount,value.boatLengthMeters,value.rangeMode,value.safetyPreset,value.alarmSnoozedUntil,value.learningReferenceLatitude,value.learningReferenceLongitude,value.provisionalAnchorLatitude,value.provisionalAnchorLongitude,value.provisionalRadiusMeters,value.positionSource,value.anchorPositionMode,value.centerSource,value.usePhoneHeading,value.candidateId,value.candidateCreatedAt,value.candidateDecision,value.candidateNotificationShown,value.candidateRmsErrorMeters,value.candidateAngularCoverageDegrees,value.candidateAngularSectorCount,value.candidateSwingReversalCount,value.candidateTemporalFitConsistent,value.candidateEffectiveDurationMillis,value.candidateDirectionEvidenceConsistent,value.maxDistanceMeters,value.alarmCount,value.estimationEpoch,value.estimationEpochStartedAt,value.adoptedCenterEpoch,value.latestEstimateEpoch)}
}

data class BackupTrackPointV1(
    val id:Long=0,val sessionId:Long=0,val timestamp:Long=0,val latitude:Double=0.0,val longitude:Double=0.0,
    val distanceFromAnchor:Double=0.0,val sog:Double?=null,val cog:Double?=null,val heading:Double?=null,val hdop:Double?=null,
    val windDirectionTrue:Double?=null,val windSpeedKnots:Double?=null,val apparentWindAngle:Double?=null,val trueWindAngle:Double?=null,
    val trueWindSpeedKnots:Double?=null,val apparentWindSpeedKnots:Double?=null,val headingMeasured:Boolean=false,
    val headingSampleSequence:Long?=null,val windSampleSequence:Long?=null,val positionSource:String="UNKNOWN",val positionProvider:String="UNKNOWN",
    val horizontalAccuracyMeters:Double?=null,val fixTrust:String="TRUSTED",val wasQuarantined:Boolean=false,val quarantineReason:String?=null,
    val headingSource:String="NONE",val headingQuality:String="UNAVAILABLE",val headingEpoch:Long?=null,
){
    fun toEntity()=TrackPointEntity(id,sessionId,timestamp,latitude,longitude,distanceFromAnchor,sog,cog,heading,hdop,windDirectionTrue,windSpeedKnots,apparentWindAngle,trueWindAngle,trueWindSpeedKnots,apparentWindSpeedKnots,headingMeasured,headingSampleSequence,windSampleSequence,positionSource,positionProvider,horizontalAccuracyMeters,fixTrust,wasQuarantined,quarantineReason,headingSource,headingQuality,headingEpoch)
    companion object{fun from(value:TrackPointEntity)=BackupTrackPointV1(value.id,value.sessionId,value.timestamp,value.latitude,value.longitude,value.distanceFromAnchor,value.sog,value.cog,value.heading,value.hdop,value.windDirectionTrue,value.windSpeedKnots,value.apparentWindAngle,value.trueWindAngle,value.trueWindSpeedKnots,value.apparentWindSpeedKnots,value.headingMeasured,value.headingSampleSequence,value.windSampleSequence,value.positionSource,value.positionProvider,value.horizontalAccuracyMeters,value.fixTrust,value.wasQuarantined,value.quarantineReason,value.headingSource,value.headingQuality,value.headingEpoch)}
}

data class BackupAlarmEventV1(val id:Long=0,val sessionId:Long=0,val timestamp:Long=0,val type:String="",val detail:String=""){
    fun toEntity()=AlarmEventEntity(id,sessionId,timestamp,type,detail)
    companion object{fun from(value:AlarmEventEntity)=BackupAlarmEventV1(value.id,value.sessionId,value.timestamp,value.type,value.detail)}
}

data class BackupSonarSurveyV1(
    val id:Long=0,val name:String="",val startedAt:Long=0,val endedAt:Long?=null,val active:Boolean=true,
    val tideMode:String="OFF",val manualTideOffsetMeters:Double=0.0,val transducerDraftMeters:Double=0.0,val keelOffsetMeters:Double=0.0,
    val gpsToTransducerMeters:Double=0.0,val configuredDepthReference:String="UNKNOWN",val sounderOffsetMeters:Double=0.0,
    val tideStationId:String?=null,val tideStationName:String?=null,val tideStationDistanceMeters:Double?=null,val sampleCount:Int=0,
){
    fun toEntity()=SonarSurveyEntity(id,name,startedAt,endedAt,active,tideMode,manualTideOffsetMeters,transducerDraftMeters,keelOffsetMeters,gpsToTransducerMeters,configuredDepthReference,sounderOffsetMeters,tideStationId,tideStationName,tideStationDistanceMeters,sampleCount)
    companion object{fun from(value:SonarSurveyEntity)=BackupSonarSurveyV1(value.id,value.name,value.startedAt,value.endedAt,value.active,value.tideMode,value.manualTideOffsetMeters,value.transducerDraftMeters,value.keelOffsetMeters,value.gpsToTransducerMeters,value.configuredDepthReference,value.sounderOffsetMeters,value.tideStationId,value.tideStationName,value.tideStationDistanceMeters,value.sampleCount)}
}

data class BackupDepthSampleV1(
    val id:Long=0,val surveyId:Long=0,val timestamp:Long=0,val latitude:Double=0.0,val longitude:Double=0.0,
    val baseGridX:Long=0,val baseGridY:Long=0,val sourceElapsedRealtime:Long=0,val rawDepthMeters:Double=0.0,val measuredDepthMeters:Double=0.0,
    val normalizedDepthMeters:Double?=null,val depthReference:String="UNKNOWN",val sentenceType:String="",val nmeaOffsetMeters:Double?=null,
    val horizontalAccuracyMeters:Double?=null,val gpsSource:String="",val positionProvider:String="",val hdop:Double?=null,val sogKnots:Double?=null,
    val fixTrust:String="DEGRADED",val positionAgeMillis:Long=0,val disposition:String="ACCEPTED",val usable:Boolean=true,val integrityReason:String?=null,
    val positionCorrectionApplied:Boolean=false,val positionCorrectionMethod:String="NONE",val tideHeightMetersApplied:Double?=null,
    val tideCorrectionMode:String="OFF",val tideStationId:String?=null,val tideStationName:String?=null,val tideStationDistanceMeters:Double?=null,
    val tidePredictionYear:Int?=null,val tideCorrectionMethod:String?=null,val tideSource:String?=null,val tideSourceUpdatedAt:Long?=null,
    val tideCorrectionStatus:String="NOT_REQUESTED",val depthHeld:Boolean=false,val depthAgeMillis:Long=0,val depthSourceElapsedRealtime:Long?=null,
){
    fun toEntity()=DepthSampleEntity(id,surveyId,timestamp,latitude,longitude,baseGridX,baseGridY,sourceElapsedRealtime,rawDepthMeters,measuredDepthMeters,normalizedDepthMeters,depthReference,sentenceType,nmeaOffsetMeters,horizontalAccuracyMeters,gpsSource,positionProvider,hdop,sogKnots,fixTrust,positionAgeMillis,disposition,usable,integrityReason,positionCorrectionApplied,positionCorrectionMethod,tideHeightMetersApplied,tideCorrectionMode,tideStationId,tideStationName,tideStationDistanceMeters,tidePredictionYear,tideCorrectionMethod,tideSource,tideSourceUpdatedAt,tideCorrectionStatus,depthHeld,depthAgeMillis,depthSourceElapsedRealtime)
    companion object{fun from(value:DepthSampleEntity)=BackupDepthSampleV1(value.id,value.surveyId,value.timestamp,value.latitude,value.longitude,value.baseGridX,value.baseGridY,value.sourceElapsedRealtime,value.rawDepthMeters,value.measuredDepthMeters,value.normalizedDepthMeters,value.depthReference,value.sentenceType,value.nmeaOffsetMeters,value.horizontalAccuracyMeters,value.gpsSource,value.positionProvider,value.hdop,value.sogKnots,value.fixTrust,value.positionAgeMillis,value.disposition,value.usable,value.integrityReason,value.positionCorrectionApplied,value.positionCorrectionMethod,value.tideHeightMetersApplied,value.tideCorrectionMode,value.tideStationId,value.tideStationName,value.tideStationDistanceMeters,value.tidePredictionYear,value.tideCorrectionMethod,value.tideSource,value.tideSourceUpdatedAt,value.tideCorrectionStatus,value.depthHeld,value.depthAgeMillis,value.depthSourceElapsedRealtime)}
}
