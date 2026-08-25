package com.yokuli.anchorwatch.data.anchorage

import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageSaveDraft
import com.yokuli.anchorwatch.domain.anchorage.AnchorageSpotApproachTarget
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical presentation bridge for the Place -> Spot -> Visit library.
 *
 * The legacy saved_anchorages table remains readable by migrations/backups only.
 * Live nearby cards, history presentation and approach geometry all project from
 * the GIS library so a newly saved Spot cannot disappear from Watch.
 */
@Singleton
class AnchorageApproachRepository @Inject constructor(
    private val database:AppDatabase,
    private val places:AnchoragePlaceRepository,
    private val spots:AnchorageSpotRepository,
    private val saver:AnchorageSaveRepository,
) {
    val anchorages = combine(database.anchoragePlaceDao().observeActive(),database.anchorageSpotDao().observeAll(),database.anchorageVisitDao().observeAll(),database.anchorageMetadataDao().observeRatings()){placeRows,spotRows,visitRows,ratingRows->
        val placeById=placeRows.associateBy{it.id}
        val latestSessionBySpot=visitRows.filter{it.spotId!=null&&it.anchorSessionId!=null}.groupBy{it.spotId}.mapValues{(_,visits)->visits.maxBy{it.startedAt}.anchorSessionId}
        val ratingByPlace=ratingRows.associateBy{it.placeId}
        spotRows.mapNotNull{spot->placeById[spot.placeId]?.let{place->spot.toPresentation(place,ratingByPlace[place.id]?.legacyOverallRating,latestSessionBySpot[spot.id])}}
    }.distinctUntilChanged()

    /** Canonical live guidance targets. These IDs survive
     * map zoom, edits and process recreation. */
    val targets = combine(
        database.anchoragePlaceDao().observeActive(),
        database.anchorageSpotDao().observeAll(),
    ) { placeRows, spotRows ->
        val placesById = placeRows.associateBy { it.id }
        spotRows.mapNotNull { spot ->
            placesById[spot.placeId]?.let { place ->
                AnchorageSpotApproachTarget(
                    placeId = place.id,
                    spotId = spot.id,
                    placeName = place.displayName,
                    spotName = spot.name,
                    latitude = spot.latitude,
                    longitude = spot.longitude,
                    areaRadiusMeters = maxOf(
                        40.0,
                        spot.preferredAlarmRadiusMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                        spot.coordinateUncertaintyMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    ),
                    coordinateEstimated = spot.coordinateSource != "CONFIRMED_ANCHOR",
                    alarmRadiusMeters = spot.preferredAlarmRadiusMeters,
                    waterDepthMeters = spot.typicalWaterDepthMeters,
                    rodeMeters = spot.typicalRodeLengthMeters,
                )
            }
        }
    }.distinctUntilChanged()

    suspend fun save(value:SavedAnchorageEntity):Long{
        val current=value.id.takeIf{it>0}?.let{database.anchorageSpotDao().get(it)}
        if(current!=null){
            val place=requireNotNull(database.anchoragePlaceDao().get(current.placeId)){"Anchorage Place no longer exists"}
            places.save(place.copy(displayName=value.name.trim().ifBlank{place.displayName},centerLatitude=value.latitude,centerLongitude=value.longitude,bboxMinLatitude=value.latitude,bboxMaxLatitude=value.latitude,bboxMinLongitude=value.longitude,bboxMaxLongitude=value.longitude,personalNotes=value.notes,updatedAt=value.updatedAt))
            spots.save(current.copy(latitude=value.latitude,longitude=value.longitude,preferredAlarmRadiusMeters=value.preferredAlarmRadiusMeters,typicalWaterDepthMeters=value.typicalWaterDepthMeters,typicalRodeLengthMeters=value.typicalRodeLengthMeters,seabedType=value.seabedType,customSeabedText=value.customSeabedText,personalNotes=value.notes,coordinateSource=value.coordinateSource,coordinateUncertaintyMeters=value.coordinateUncertaintyMeters,updatedAt=value.updatedAt))
            return current.id
        }
        val draft=AnchorageSaveDraft(value.sourceSessionId,value.latitude,value.longitude,value.coordinateSource,value.coordinateUncertaintyMeters,value.typicalWaterDepthMeters,value.typicalRodeLengthMeters,value.preferredAlarmRadiusMeters,value.seabedType)
        // This compatibility entry creates a distinct Place/Spot. A fixed
        // distance must never silently merge or reject an anchoring position;
        // the stepped save flow exposes uncertainty-aware matches for the user
        // to decide explicitly.
        return saver.save(AnchorageSaveRequest(draft,AnchorageSavePlaceInput(displayName=value.name.trim().ifBlank{"Saved anchorage"},personalNotes=value.notes),AnchorageSaveSpotInput(name="Main spot",personalNotes=value.notes))).spotId
    }

    suspend fun delete(spotId:Long,activePlaceId:Long?){
        val spot=database.anchorageSpotDao().get(spotId)?:return
        require(spot.placeId!=activePlaceId){"Cannot delete the Place used by the active Anchor watch"}
        spots.delete(spotId)
        if(database.anchorageSpotDao().forPlaceNow(spot.placeId).isEmpty())places.delete(spot.placeId,activePlaceId)
    }
}

private fun AnchorageSpotEntity.toPresentation(place:AnchoragePlaceEntity,rating:Int?=null,sourceSessionId:Long?=null)=SavedAnchorageEntity(
    id=id,
    name=if(name.equals("Main spot",true)||name.equals("Chart reference",true))place.displayName else "${place.displayName} · $name",
    latitude=latitude,
    longitude=longitude,
    createdAt=minOf(createdAt,place.createdAt),
    updatedAt=maxOf(updatedAt,place.updatedAt),
    lastVisitedAt=lastVisitedAt?:place.lastVisitedAt,
    visitCount=maxOf(visitCountCached+legacyVisitCount,place.visitCountCached+place.legacyVisitCount),
    preferredAlarmRadiusMeters=preferredAlarmRadiusMeters,
    typicalWaterDepthMeters=typicalWaterDepthMeters,
    typicalRodeLengthMeters=typicalRodeLengthMeters,
    seabedType=seabedType,
    customSeabedText=customSeabedText,
    rating=rating,
    notes=personalNotes.ifBlank{place.personalNotes},
    sourceSessionId=sourceSessionId,
    coordinateSource=coordinateSource,
    coordinateUncertaintyMeters=coordinateUncertaintyMeters,
)
