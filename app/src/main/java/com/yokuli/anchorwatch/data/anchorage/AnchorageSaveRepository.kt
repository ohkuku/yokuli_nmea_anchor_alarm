package com.yokuli.anchorwatch.data.anchorage

import androidx.room.withTransaction
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.*
import com.yokuli.anchorwatch.domain.anchorage.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

data class AnchorageSavePlaceInput(
    val existingPlaceId:Long?=null,
    val displayName:String,
    val placeType:AnchoragePlaceType=AnchoragePlaceType.UNKNOWN,
    val primaryRegionId:Long?=null,
    val contextRegionIds:List<Long> = emptyList(),
    val description:String="",
    val personalNotes:String="",
    val planningStatus:AnchoragePlanningStatus=AnchoragePlanningStatus.NONE,
    val favorite:Boolean=false,
)
data class AnchorageSaveSpotInput(
    val existingSpotId:Long?=null,
    val name:String="Main spot",
    val approachNotes:String="",
    val personalNotes:String="",
)
data class AnchoragePersonalAssessmentInput(
    val wouldReturn:AnchorageWouldReturn=AnchorageWouldReturn.UNKNOWN,
    val holding:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val comfort:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val shoreAccess:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val crowding:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val quietness:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val notes:String="",
)
data class AnchorageSaveRequest(val draft:AnchorageSaveDraft,val place:AnchorageSavePlaceInput,val spot:AnchorageSaveSpotInput,val visitNotes:String="",val assessment:AnchoragePersonalAssessmentInput?=null)
data class AnchorageSaveResult(
    val placeId:Long,
    val spotId:Long,
    val visitId:Long?,
    val placeCreated:Boolean=false,
    val spotCreated:Boolean=false,
    val visitCreated:Boolean=false,
)

object AnchorageSaveDraftFactory {
    fun fromSession(session:AnchorSessionEntity):AnchorageSaveDraft{
        val position=AnchorageSavePositionPolicy.resolve(session)
        return AnchorageSaveDraft(session.id,position.latitude,position.longitude,position.source.name,position.uncertaintyMeters,session.waterDepthMeters?:session.minObservedDepthMeters,session.rodeLengthMeters.takeIf{it.isFinite()&&it>=0},session.alarmRadiusMeters.takeIf{it.isFinite()&&it>0},null)
    }
    fun fromMap(latitude:Double,longitude:Double)=AnchorageSaveDraft(null,latitude,longitude,"MAP_SELECTED",null,null,null,null,null)
}

@Singleton class AnchorageSaveRepository @Inject constructor(
    private val database:AppDatabase,
    private val placeRepository:AnchoragePlaceRepository,
    private val spotRepository:AnchorageSpotRepository,
    private val visitRepository:AnchorageVisitRepository,
    private val spatial:AnchorageSpatialIndexRepository,
    private val search:AnchorageSearchRepository,
){
    suspend fun nearbyPlaceMatches(draft:AnchorageSaveDraft,regionId:Long?,proposedName:String?):List<AnchoragePlaceMatchResult>{
        val radius=2_000.0;val lat=radius/111_320.0;val lon=radius/(111_320.0*cos(Math.toRadians(draft.proposedLatitude)).coerceAtLeast(.15));val viewport=AnchorageViewport((draft.proposedLatitude-lat).coerceAtLeast(-90.0),normalize(draft.proposedLongitude-lon),(draft.proposedLatitude+lat).coerceAtMost(90.0),normalize(draft.proposedLongitude+lon))
        return AnchoragePlaceMatchEngine.rank(AnchorageGeoPoint(draft.proposedLatitude,draft.proposedLongitude),regionId,proposedName,spatial.viewport(viewport).map{AnchoragePlaceMatchCandidate(it.id,it.displayName,AnchorageGeoPoint(it.centerLatitude,it.centerLongitude),it.geometryGeoJson?.let{json->runCatching{AnchorageGeometryCodec.decode(json)}.getOrNull()},it.primaryRegionId)})
    }
    suspend fun nearbySpotMatches(draft:AnchorageSaveDraft,placeId:Long)=database.anchorageSpotDao().forPlaceNow(placeId).map{spot->spot to AnchorageSpotMatchEngine.evaluate(AnchorageSpotMatchCandidate(draft.proposedLatitude,draft.proposedLongitude,draft.uncertaintyMeters,draft.alarmRadiusMeters),AnchorageSpotMatchCandidate(spot.latitude,spot.longitude,spot.coordinateUncertaintyMeters,spot.preferredAlarmRadiusMeters),true)}.sortedBy{it.second.distanceMeters}

    suspend fun save(request:AnchorageSaveRequest):AnchorageSaveResult=database.withTransaction{
        validate(request)
        val now=System.currentTimeMillis();val draft=request.draft
        val existingPlace=request.place.existingPlaceId?.let{requireNotNull(database.anchoragePlaceDao().get(it)){"Selected Place no longer exists"}}
        val verification=if(draft.sessionId!=null)AnchorageVerificationStatus.VERIFIED_BY_SESSION else AnchorageVerificationStatus.PLANNED
        val placeId=existingPlace?.id?:placeRepository.save(AnchoragePlaceEntity(primaryRegionId=request.place.primaryRegionId,displayName=request.place.displayName.trim(),placeType=request.place.placeType.name,geometryType="POINT",centerLatitude=draft.proposedLatitude,centerLongitude=draft.proposedLongitude,bboxMinLatitude=draft.proposedLatitude,bboxMaxLatitude=draft.proposedLatitude,bboxMinLongitude=draft.proposedLongitude,bboxMaxLongitude=draft.proposedLongitude,description=request.place.description.trim(),personalNotes=request.place.personalNotes.trim(),verificationStatus=verification.name,planningStatus=request.place.planningStatus.name,favorite=request.place.favorite,createdAt=now,updatedAt=now))
        if(existingPlace!=null){
            placeRepository.save(existingPlace.copy(
                primaryRegionId=request.place.primaryRegionId?:existingPlace.primaryRegionId,
                displayName=request.place.displayName.trim().ifBlank{existingPlace.displayName},
                placeType=request.place.placeType.takeUnless{it==AnchoragePlaceType.UNKNOWN}?.name?:existingPlace.placeType,
                description=request.place.description.trim().ifBlank{existingPlace.description},
                personalNotes=request.place.personalNotes.trim().ifBlank{existingPlace.personalNotes},
                planningStatus=request.place.planningStatus.takeUnless{it==AnchoragePlanningStatus.NONE}?.name?:existingPlace.planningStatus,
                favorite=existingPlace.favorite||request.place.favorite,
                verificationStatus=if(draft.sessionId!=null)AnchorageVerificationStatus.VERIFIED_BY_SESSION.name else existingPlace.verificationStatus,
                updatedAt=now,
            ))
        }
        val regionIds=(listOfNotNull(request.place.primaryRegionId)+request.place.contextRegionIds).distinct()
        if(existingPlace==null||regionIds.isNotEmpty()){
            database.anchorageMetadataDao().clearPlaceRegions(placeId);database.anchorageMetadataDao().upsertPlaceRegions(regionIds.mapIndexed{index,id->AnchoragePlaceRegionCrossRef(placeId,id,if(index==0&&id==request.place.primaryRegionId)"PRIMARY" else "CONTEXT",index)})
        }
        regionIds.forEach{id->database.anchorageRegionDao().get(id)?.takeIf{!it.userConfirmed}?.let{database.anchorageRegionDao().upsert(it.copy(userConfirmed=true,updatedAt=now))}}
        val existingSpot=request.spot.existingSpotId?.let{requireNotNull(database.anchorageSpotDao().get(it)){"Selected Spot no longer exists"}.also{spot->require(spot.placeId==placeId){"Selected Spot belongs to another Place"}}}
        val spotId=existingSpot?.id?:spotRepository.save(AnchorageSpotEntity(placeId=placeId,name=request.spot.name.trim().ifBlank{"Main spot"},spotType=if(draft.sessionId==null)AnchorageSpotType.PLANNED_REFERENCE.name else AnchorageSpotType.ANCHOR_SPOT.name,latitude=draft.proposedLatitude,longitude=draft.proposedLongitude,coordinateSource=draft.coordinateSource,coordinateUncertaintyMeters=draft.uncertaintyMeters,preferredAlarmRadiusMeters=draft.alarmRadiusMeters,typicalWaterDepthMeters=draft.depthMeters,typicalRodeLengthMeters=draft.rodeMeters,seabedType=draft.seabedType?:"UNKNOWN",approachNotes=request.spot.approachNotes.trim(),personalNotes=request.spot.personalNotes.trim(),verificationStatus=verification.name,createdAt=now,updatedAt=now))
        if(existingSpot!=null&&draft.sessionId!=null)spotRepository.save(existingSpot.copy(verificationStatus=AnchorageVerificationStatus.VERIFIED_BY_SESSION.name,updatedAt=now))
        val existingVisit=draft.sessionId?.let{database.anchorageVisitDao().bySession(it)}
        val visitId=draft.sessionId?.let{sessionId->
            existingVisit?.id?:run{val session=requireNotNull(database.anchorDao().session(sessionId)){"Anchor session no longer exists"};visitRepository.save(session.visitSnapshot(placeId,spotId,request.visitNotes,now))}
        }
        request.assessment?.let{value->database.anchorageMetadataDao().upsertAssessment(AnchoragePersonalAssessmentEntity(placeId,value.wouldReturn.name,value.holding.name,value.comfort.name,value.shoreAccess.name,value.crowding.name,value.quietness.name,value.notes.trim(),existingPlace?.let{database.anchorageMetadataDao().assessment(placeId)?.legacyOverallRating},now))}
        search.rebuildPlace(placeId)
        AnchorageSaveResult(placeId,spotId,visitId,existingPlace==null,existingSpot==null,visitId!=null&&existingVisit==null)
    }

    /** Undo only records created by the immediately completed transaction. */
    suspend fun undo(result:AnchorageSaveResult,sessionId:Long?):Boolean=database.withTransaction{
        if(result.visitCreated)result.visitId?.let{database.anchorageVisitDao().delete(it)}
        sessionId?.let{database.anchorDao().session(it)}?.takeIf{it.anchorageVisitId==result.visitId}?.let{session->
            database.anchorDao().updateSession(session.copy(anchoragePlaceId=null,anchorageSpotId=null,anchorageVisitId=null))
        }
        if(result.spotCreated&&database.anchorageVisitDao().forPlaceNow(result.placeId).none{it.spotId==result.spotId})spotRepository.delete(result.spotId)
        if(result.placeCreated&&database.anchorageSpotDao().forPlaceNow(result.placeId).isEmpty())placeRepository.delete(result.placeId)
        true
    }

    private fun AnchorSessionEntity.visitSnapshot(placeId:Long,spotId:Long,notes:String,now:Long)=AnchorageVisitEntity(placeId=placeId,spotId=spotId,anchorSessionId=id,visitKind=AnchorageVisitKind.SESSION.name,startedAt=startedAt,endedAt=endedAt,actualAnchorLatitude=anchorLatitude,actualAnchorLongitude=anchorLongitude,coordinateSource=centerSource,coordinateUncertaintyMeters=provisionalRadiusMeters,waterDepthMeters=waterDepthMeters,rodeLengthMeters=rodeLengthMeters,alarmRadiusMeters=alarmRadiusMeters,maxExcursionMeters=maxDistanceMeters,alarmCount=alarmCount,minDepthMeters=minObservedDepthMeters,maxDepthMeters=maxObservedDepthMeters,maxWindKnots=maxObservedWindKnots,maxWindSource=maxObservedWindSource,typicalMotionScore=null,p95MotionScore=null,p95AbsoluteHeelDegrees=null,dominantRollPeriodSeconds=null,impactCount=null,userNotes=notes.trim(),summaryVersion=PersonalAnchorageSummaryEngine.VERSION,createdAt=now)
    private fun validate(value:AnchorageSaveRequest){require(value.place.displayName.trim().isNotEmpty());require(value.place.displayName.length<=200&&value.place.description.length<=20_000&&value.place.personalNotes.length<=20_000);require(value.spot.name.length<=200&&value.spot.approachNotes.length<=20_000&&value.spot.personalNotes.length<=20_000&&value.visitNotes.length<=20_000&&(value.assessment?.notes?.length?:0)<=20_000)}
    private fun normalize(value:Double)=((value+540)%360)-180
}
