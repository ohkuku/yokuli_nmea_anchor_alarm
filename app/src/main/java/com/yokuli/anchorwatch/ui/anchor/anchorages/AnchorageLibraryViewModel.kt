package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.anchorage.AnchorageLibraryRepository
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.data.anchorage.AnchorageSearchRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageSaveRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageSaveRequest
import com.yokuli.anchorwatch.data.anchorage.AnchorageSavePlaceInput
import com.yokuli.anchorwatch.data.anchorage.AnchorageSaveSpotInput
import com.yokuli.anchorwatch.data.anchorage.AnchorageSaveDraftFactory
import com.yokuli.anchorwatch.data.anchorage.AnchoragePhotoRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageSharePayloadV2
import com.yokuli.anchorwatch.data.anchorage.AnchorageV2QrImageGenerator
import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionPlaceCrossRef
import com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceRepository
import com.yokuli.anchorwatch.data.database.AppDatabase
import android.net.Uri
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageMapPlace
import com.yokuli.anchorwatch.domain.anchorage.AnchoragePlanningStatus
import com.yokuli.anchorwatch.domain.anchorage.AnchorageViewport
import com.yokuli.anchorwatch.domain.anchorage.AnchorageProtectionMedium
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCompassSector
import com.yokuli.anchorwatch.domain.anchorage.AnchorageProtectionRating
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AnchorageDisplayMode{MAP,LIST}
internal const val UNASSIGNED_REGION_ID=-1L
data class AnchorageFilterState(val favoriteOnly:Boolean=false,val planningStatus:AnchoragePlanningStatus?=null,val visitedOnly:Boolean=false)
data class AnchorageLibraryUiState(
    val allPlaces:List<AnchoragePlaceEntity> = emptyList(),
    val regions:List<AnchorageRegionEntity> = emptyList(),
    val visiblePlaces:List<AnchoragePlaceEntity> = emptyList(),
    val selectedPlace:AnchoragePlaceBundle?=null,
    val selectedRegionId:Long?=null,
    val filters:AnchorageFilterState=AnchorageFilterState(),
    val displayMode:AnchorageDisplayMode=AnchorageDisplayMode.MAP,
    val query:String="",
    val loading:Boolean=true,
    val collections:List<AnchorageCollectionEntity> = emptyList(),
)

@HiltViewModel class AnchorageLibraryViewModel @Inject constructor(private val app:Application,private val database:AppDatabase,private val library:AnchorageLibraryRepository,private val places:AnchoragePlaceRepository,private val search:AnchorageSearchRepository,private val saver:AnchorageSaveRepository,private val photos:AnchoragePhotoRepository,private val qr:AnchorageV2QrImageGenerator):ViewModel(){
    private val visible=MutableStateFlow<List<AnchoragePlaceEntity>>(emptyList());private val selected=MutableStateFlow<AnchoragePlaceBundle?>(null);private val controls=MutableStateFlow(Controls());private var viewportJob:Job?=null;private var queryJob:Job?=null
    private val planning=MutableStateFlow<Pair<Double,Double>?>(null);val planningPoint=planning.asStateFlow()
    private data class Controls(val regionId:Long?=null,val filters:AnchorageFilterState=AnchorageFilterState(),val mode:AnchorageDisplayMode=AnchorageDisplayMode.MAP,val query:String="")
    private val libraryIndex=combine(library.places,database.anchorageRegionDao().observeAll()){all,regions->all to regions}
    val state:StateFlow<AnchorageLibraryUiState> = combine(libraryIndex,visible,selected,controls,library.collections){(all,regions),inViewport,selectedPlace,control,collections->
        val base=(if(control.query.isBlank())inViewport.ifEmpty{all}else inViewport).filter{place->
            val inRegion=when(control.regionId){null->true;UNASSIGNED_REGION_ID->place.primaryRegionId==null;else->place.primaryRegionId==control.regionId}
            inRegion&&(!control.filters.favoriteOnly||place.favorite)&&(!control.filters.visitedOnly||place.visitCountCached+place.legacyVisitCount>0)&&(control.filters.planningStatus==null||place.planningStatus==control.filters.planningStatus.name)
        }
        AnchorageLibraryUiState(all,regions,base,selectedPlace,control.regionId,control.filters,control.mode,control.query,false,collections)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),AnchorageLibraryUiState())
    fun updateViewport(value:AnchorageViewport){viewportJob?.cancel();viewportJob=viewModelScope.launch{delay(225);visible.value=library.viewport(value)}}
    fun setMode(value:AnchorageDisplayMode){controls.value=controls.value.copy(mode=value)}
    fun setFilters(value:AnchorageFilterState){controls.value=controls.value.copy(filters=value)}
    fun setRegion(id:Long?){controls.value=controls.value.copy(regionId=id)}
    fun search(value:String){controls.value=controls.value.copy(query=value);queryJob?.cancel();queryJob=viewModelScope.launch{delay(250);visible.value=if(value.isBlank())state.value.allPlaces else search.search(value)}}
    fun selectPlace(id:Long?){viewModelScope.launch{selected.value=id?.let{library.bundle(it)}}}
    fun importLegacyQr(value:SavedAnchorageEntity)=viewModelScope.launch{
        val draft=com.yokuli.anchorwatch.domain.anchorage.AnchorageSaveDraft(null,value.latitude,value.longitude,"QR_IMPORTED",value.coordinateUncertaintyMeters,value.typicalWaterDepthMeters,value.typicalRodeLengthMeters,value.preferredAlarmRadiusMeters,value.seabedType)
        val result=saver.save(AnchorageSaveRequest(draft,AnchorageSavePlaceInput(displayName=value.name,personalNotes=value.notes),AnchorageSaveSpotInput(name="Main spot",personalNotes=value.customSeabedText.orEmpty())))
        selected.value=library.bundle(result.placeId)
    }
    fun importV2Qr(value:AnchorageSharePayloadV2)=viewModelScope.launch{
        val draft=com.yokuli.anchorwatch.domain.anchorage.AnchorageSaveDraft(null,value.latitude,value.longitude,value.coordinateSource,value.coordinateUncertaintyMeters,value.typicalWaterDepthMeters,value.typicalRodeLengthMeters,value.preferredAlarmRadiusMeters,value.seabedType)
        val matches=saver.nearbyPlaceMatches(draft,null,value.placeName)
        val likely=matches.firstOrNull{it.contains&&it.score>=80.0}
        val result=saver.save(AnchorageSaveRequest(draft,AnchorageSavePlaceInput(existingPlaceId=likely?.place?.id,displayName=likely?.place?.name?:value.placeName,personalNotes=value.notes),AnchorageSaveSpotInput(name=value.spotName,approachNotes=value.approachNotes,personalNotes=value.notes)))
        selected.value=library.bundle(result.placeId)
    }
    fun importPhoto(uri:Uri)=viewModelScope.launch{selected.value?.place?.id?.let{id->runCatching{photos.import(id,uri)};selected.value=library.bundle(id)}}
    fun deletePhoto(value:AnchoragePhotoEntity)=viewModelScope.launch{runCatching{photos.delete(value)};selected.value=library.bundle(value.placeId)}
    fun photoPath(value:AnchoragePhotoEntity,thumbnail:Boolean=true)=photos.file(value,thumbnail).absolutePath
    fun shareSpot(spotId:Long)=viewModelScope.launch{selected.value?.let{bundle->bundle.spots.firstOrNull{it.id==spotId}?.let{spot->runCatching{val file=qr.generate(bundle.place,spot,bundle.regionPath.map{it.displayName});val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file);app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share anchorage").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}}}
    fun setFavorite(value:Boolean)=viewModelScope.launch{selected.value?.place?.let{place->places.save(place.copy(favorite=value,updatedAt=System.currentTimeMillis()));selected.value=library.bundle(place.id)}}
    fun setPlanning(value:AnchoragePlanningStatus)=viewModelScope.launch{selected.value?.place?.let{place->places.save(place.copy(planningStatus=value.name,updatedAt=System.currentTimeMillis()));selected.value=library.bundle(place.id)}}
    fun toggleCollection(collectionId:Long)=viewModelScope.launch{selected.value?.let{bundle->if(bundle.collections.any{it.id==collectionId})database.anchorageCollectionDao().removeMembership(collectionId,bundle.place.id)else database.anchorageCollectionDao().setMembership(AnchorageCollectionPlaceCrossRef(collectionId,bundle.place.id,System.currentTimeMillis()));selected.value=library.bundle(bundle.place.id)}}
    fun cycleProtection(medium:AnchorageProtectionMedium,sector:AnchorageCompassSector)=viewModelScope.launch{selected.value?.let{bundle->val existing=bundle.protection.firstOrNull{it.medium==medium.name&&it.sector==sector.name};val values=AnchorageProtectionRating.entries;val next=values[(values.indexOf(runCatching{AnchorageProtectionRating.valueOf(existing?.rating?:"UNKNOWN")}.getOrDefault(AnchorageProtectionRating.UNKNOWN))+1)%values.size];database.anchorageMetadataDao().upsertProtection(listOf(AnchorageProtectionSectorEntity(bundle.place.id,medium.name,sector.name,next.name,"USER",notes=existing?.notes.orEmpty(),updatedAt=System.currentTimeMillis())));selected.value=library.bundle(bundle.place.id)}}
    fun planAt(latitude:Double,longitude:Double){planning.value=latitude to longitude}
    fun cancelPlan(){planning.value=null}
    fun savePlan(name:String,spotName:String,notes:String)=viewModelScope.launch{planning.value?.let{(lat,lon)->runCatching{saver.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromMap(lat,lon),AnchorageSavePlaceInput(displayName=name.trim(),planningStatus=AnchoragePlanningStatus.WANT_TO_VISIT,personalNotes=notes,favorite=true),AnchorageSaveSpotInput(name=spotName.trim().ifBlank{"Chart reference"}))).also{result->planning.value=null;selected.value=library.bundle(result.placeId)}}}}
    fun mapModels():List<AnchorageMapPlace> = state.value.visiblePlaces.map{place->AnchorageMapPlace(place.id,place.centerLatitude,place.centerLongitude,place.displayName,place.favorite,runCatching{AnchoragePlanningStatus.valueOf(place.planningStatus)}.getOrDefault(AnchoragePlanningStatus.NONE),place.visitCountCached+place.legacyVisitCount,state.value.selectedPlace?.takeIf{it.place.id==place.id}?.spots?.size?:0)}
}
