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
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageMapPlace
import com.yokuli.anchorwatch.domain.anchorage.AnchoragePlanningStatus
import com.yokuli.anchorwatch.domain.anchorage.AnchorageViewport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AnchorageDisplayMode{MAP,LIST}
data class AnchorageFilterState(val favoriteOnly:Boolean=false,val planningStatus:AnchoragePlanningStatus?=null,val visitedOnly:Boolean=false)
data class AnchorageLibraryUiState(
    val allPlaces:List<AnchoragePlaceEntity> = emptyList(),
    val visiblePlaces:List<AnchoragePlaceEntity> = emptyList(),
    val selectedPlace:AnchoragePlaceBundle?=null,
    val selectedRegionId:Long?=null,
    val filters:AnchorageFilterState=AnchorageFilterState(),
    val displayMode:AnchorageDisplayMode=AnchorageDisplayMode.MAP,
    val query:String="",
    val loading:Boolean=true,
)

@HiltViewModel class AnchorageLibraryViewModel @Inject constructor(private val library:AnchorageLibraryRepository,private val search:AnchorageSearchRepository,private val saver:AnchorageSaveRepository):ViewModel(){
    private val visible=MutableStateFlow<List<AnchoragePlaceEntity>>(emptyList());private val selected=MutableStateFlow<AnchoragePlaceBundle?>(null);private val controls=MutableStateFlow(Controls());private var viewportJob:Job?=null;private var queryJob:Job?=null
    private data class Controls(val regionId:Long?=null,val filters:AnchorageFilterState=AnchorageFilterState(),val mode:AnchorageDisplayMode=AnchorageDisplayMode.MAP,val query:String="")
    val state:StateFlow<AnchorageLibraryUiState> = combine(library.places,visible,selected,controls){all,inViewport,selectedPlace,control->
        val base=(if(control.query.isBlank())inViewport.ifEmpty{all}else inViewport).filter{place->(control.regionId==null||place.primaryRegionId==control.regionId)&&(!control.filters.favoriteOnly||place.favorite)&&(!control.filters.visitedOnly||place.visitCountCached+place.legacyVisitCount>0)&&(control.filters.planningStatus==null||place.planningStatus==control.filters.planningStatus.name)}
        AnchorageLibraryUiState(all,base,selectedPlace,control.regionId,control.filters,control.mode,control.query,false)
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
    fun mapModels():List<AnchorageMapPlace> = state.value.visiblePlaces.map{place->AnchorageMapPlace(place.id,place.centerLatitude,place.centerLongitude,place.displayName,place.favorite,runCatching{AnchoragePlanningStatus.valueOf(place.planningStatus)}.getOrDefault(AnchoragePlanningStatus.NONE),place.visitCountCached+place.legacyVisitCount,state.value.selectedPlace?.takeIf{it.place.id==place.id}?.spots?.size?:0)}
}
