package com.yokuli.anchorwatch.data.anchorage

import androidx.room.withTransaction
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.*
import com.yokuli.anchorwatch.domain.anchorage.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class AnchoragePlaceBundle(
    val place:AnchoragePlaceEntity,
    val primaryRegion:AnchorageRegionEntity?,
    val regionPath:List<AnchorageRegionEntity>,
    val spots:List<AnchorageSpotEntity>,
    val rating:AnchoragePersonalRatingEntity?,
    val protection:List<AnchorageProtectionSectorEntity>,
    val facilities:List<AnchorageFacilityEntity>,
    val summary:AnchoragePlaceSummaryEntity?,
    val thumbnail:AnchoragePhotoEntity?,
    val visits:List<AnchorageVisitEntity> = emptyList(),
    val collections:List<AnchorageCollectionEntity> = emptyList(),
    val photos:List<AnchoragePhotoEntity> = emptyList(),
)

@Singleton class AnchoragePlaceRepository @Inject constructor(private val database:AppDatabase,private val spatial:AnchorageSpatialIndexRepository,private val search:AnchorageSearchRepository){
    val places:Flow<List<AnchoragePlaceEntity>> = database.anchoragePlaceDao().observeActive()
    suspend fun get(id:Long)=database.anchoragePlaceDao().get(id)
    suspend fun save(value:AnchoragePlaceEntity):Long=database.withTransaction{
        validate(value);val id=if(value.id==0L)database.anchoragePlaceDao().insert(value) else{database.anchoragePlaceDao().update(value);value.id};val stored=database.anchoragePlaceDao().get(id)!!;spatial.upsertPlace(stored);search.rebuildPlace(id);id
    }
    suspend fun delete(id:Long,activeAnchorPlaceId:Long?=null):Boolean=database.withTransaction{require(id!=activeAnchorPlaceId){"Cannot delete the Place used by the active Anchor watch"};spatial.deletePlace(id);search.removePlace(id);database.anchoragePlaceDao().delete(id)>0}
    private fun validate(value:AnchoragePlaceEntity){require(value.displayName.isNotBlank()&&value.displayName.length<=200);require(value.centerLatitude in -90.0..90.0&&value.centerLongitude in -180.0..180.0);require(value.description.length<=20_000&&value.personalNotes.length<=20_000)}
}

@Singleton class AnchorageSpotRepository @Inject constructor(private val database:AppDatabase,private val spatial:AnchorageSpatialIndexRepository,private val search:AnchorageSearchRepository){
    suspend fun get(id:Long)=database.anchorageSpotDao().get(id)
    suspend fun forPlace(placeId:Long)=database.anchorageSpotDao().forPlaceNow(placeId)
    suspend fun save(value:AnchorageSpotEntity):Long=database.withTransaction{
        require(database.anchoragePlaceDao().get(value.placeId)!=null);require(value.name.isNotBlank()&&value.name.length<=200);require(value.latitude in -90.0..90.0&&value.longitude in -180.0..180.0)
        val id=if(value.id==0L)database.anchorageSpotDao().insert(value)else{database.anchorageSpotDao().update(value);value.id};val stored=database.anchorageSpotDao().get(id)!!;spatial.upsertSpot(stored);search.rebuildPlace(value.placeId);id
    }
    suspend fun delete(id:Long):Boolean=database.withTransaction{val spot=database.anchorageSpotDao().get(id)?:return@withTransaction false;spatial.deleteSpot(id);val deleted=database.anchorageSpotDao().delete(id)>0;search.rebuildPlace(spot.placeId);deleted}
}

@Singleton class AnchorageVisitRepository @Inject constructor(private val database:AppDatabase,private val intelligence:AnchorageIntelligenceRepository){
    suspend fun save(value:AnchorageVisitEntity):Long=database.withTransaction{
        require(database.anchoragePlaceDao().get(value.placeId)!=null);require(value.spotId==null||database.anchorageSpotDao().get(value.spotId)!=null)
        val id=if(value.id==0L)database.anchorageVisitDao().insert(value)else{database.anchorageVisitDao().update(value);value.id}
        value.anchorSessionId?.let{sessionId->database.anchorDao().session(sessionId)?.let{session->database.anchorDao().updateSession(session.copy(anchoragePlaceId=value.placeId,anchorageSpotId=value.spotId,anchorageVisitId=id))}}
        refreshCounts(value.placeId,value.spotId);intelligence.rebuild(value.placeId);id
    }
    suspend fun forPlace(placeId:Long)=database.anchorageVisitDao().forPlaceNow(placeId)
    private suspend fun refreshCounts(placeId:Long,spotId:Long?){
        val visits=database.anchorageVisitDao().forPlaceNow(placeId);database.anchoragePlaceDao().get(placeId)?.let{database.anchoragePlaceDao().update(it.copy(visitCountCached=visits.size,lastVisitedAt=visits.maxOfOrNull(AnchorageVisitEntity::startedAt),updatedAt=System.currentTimeMillis()))}
        spotId?.let{id->database.anchorageSpotDao().get(id)?.let{spot->val spotVisits=visits.filter{it.spotId==id};database.anchorageSpotDao().update(spot.copy(visitCountCached=spotVisits.size,lastVisitedAt=spotVisits.maxOfOrNull(AnchorageVisitEntity::startedAt),updatedAt=System.currentTimeMillis()))}}
    }
}

@Singleton class AnchorageLibraryRepository @Inject constructor(private val database:AppDatabase,private val spatial:AnchorageSpatialIndexRepository){
    val places=database.anchoragePlaceDao().observeActive()
    val collections=database.anchorageCollectionDao().observeAll()
    suspend fun bundle(placeId:Long):AnchoragePlaceBundle?=database.withTransaction{
        val place=database.anchoragePlaceDao().get(placeId)?:return@withTransaction null;val path=regionPath(place.primaryRegionId)
        val photos=database.anchoragePhotoDao().forPlaceNow(placeId)
        AnchoragePlaceBundle(place,path.firstOrNull(),path,database.anchorageSpotDao().forPlaceNow(placeId),database.anchorageMetadataDao().rating(placeId),database.anchorageMetadataDao().protection(placeId),database.anchorageMetadataDao().facilities(placeId),database.anchorageMetadataDao().summary(placeId),photos.firstOrNull(),database.anchorageVisitDao().forPlaceNow(placeId),database.anchorageCollectionDao().forPlace(placeId),photos)
    }
    suspend fun viewport(value:AnchorageViewport)=spatial.viewport(value)
    suspend fun nearby(latitude:Double,longitude:Double,radiusMeters:Double=AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS):List<AnchorageNearbyPlace>{
        val grouped=spatial.nearbySpots(latitude,longitude,radiusMeters).groupBy{it.first.placeId}
        return grouped.mapNotNull{(placeId,spots)->database.anchoragePlaceDao().get(placeId)?.takeIf{!it.archived&&it.planningStatus!="AVOID"}?.let{place->AnchorageNearbyPlace(place,spots.minOf{it.second},spots.map{it.first})}}.sortedBy{it.distanceMeters}
    }
    private suspend fun regionPath(start:Long?):List<AnchorageRegionEntity>{val result=mutableListOf<AnchorageRegionEntity>();var id=start;repeat(32){val value=id?.let{database.anchorageRegionDao().get(it)}?:return result;result+=value;id=value.parentRegionId};return result}
}

data class AnchorageNearbyPlace(val place:AnchoragePlaceEntity,val distanceMeters:Double,val spots:List<AnchorageSpotEntity>)
