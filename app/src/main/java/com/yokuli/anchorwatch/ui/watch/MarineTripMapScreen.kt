package com.yokuli.anchorwatch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.data.trip.TripMapData
import com.yokuli.anchorwatch.data.trip.TripMapDestinationType
import com.yokuli.anchorwatch.data.trip.TripTrackRenderPolicy
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.map.MapDistanceTools
import com.yokuli.anchorwatch.map.MarineMapContext
import com.yokuli.anchorwatch.map.MarineMapPolicy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun MarineTripMapDestinationHost(state:MainUiState,vm:MainViewModel){
    val destination=state.tripMapDestination?:return
    BackHandler(onBack=vm::closeTripMap)
    val activeRevision=state.tripTrack.historicalRevision
    val activeTailKey=state.tripTrack.liveTail.lastOrNull()?.stableKey
    val pointBudget=if(destination.type==TripMapDestinationType.LIVE)TripTrackRenderPolicy.LIVE_DETAIL_BUDGET else TripTrackRenderPolicy.HISTORY_DETAIL_BUDGET
    val loaded by produceState<TripMapData?>(null,destination.tripId,activeRevision,state.activeTrip?.waypointCount){value=vm.tripMapData(destination.tripId,pointBudget)}
    val value=loaded
    if(value==null){Box(Modifier.fillMaxSize().testTag("trip_map_loading"),contentAlignment=Alignment.Center){CircularProgressIndicator()};return}
    val liveSegments=if(destination.type==TripMapDestinationType.LIVE&&state.tripTrack.tripId==destination.tripId)state.tripTrack.rendered(pointBudget)else value.segments
    TripMapDetailScreen(
        state=state,data=value.copy(segments=liveSegments),type=destination.type,
        initiallySelectedWaypointId=destination.selectedWaypointId,tailKey=activeTailKey,
        close=vm::closeTripMap,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TripMapDetailScreen(state:MainUiState,data:TripMapData,type:TripMapDestinationType,initiallySelectedWaypointId:Long?,tailKey:String?,close:()->Unit){
    val session=data.session
    if(session==null){Column(Modifier.fillMaxSize().padding(20.dp).testTag("trip_map_deleted"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Text(tr("This Trip was deleted while its map was open.","地图打开期间，这次航程已被删除。"));Button(close){Text(tr("Back","返回"))}};return}
    if(!BuildConfig.MAPS_CONFIGURED){Column(Modifier.fillMaxSize().padding(20.dp).testTag("trip_map_sdk_unavailable"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Text(tr("The map SDK is unavailable in this build. Trip data, waypoints and exports remain available.","当前构建没有地图 SDK；航程数据、航点和导出仍可使用。"));Button(close){Text(tr("Back","返回"))}};return}
    val route=data.segments.flatMap{segment->segment.points}.mapNotNull{point->if(point.hasPosition)LatLng(point.latitude!!,point.longitude!!)else null}
    val vessel=state.vesselData.position.value?.takeIf{type==TripMapDestinationType.LIVE}?.let{LatLng(it.latitude,it.longitude)}
    val initial=vessel?:route.lastOrNull()?:data.waypoints.lastOrNull()?.let{LatLng(it.latitude,it.longitude)}?:LatLng(-36.2,175.4)
    val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(initial,13f)}
    var mapLoaded by remember{mutableStateOf(false)};var follow by rememberSaveable{mutableStateOf(type==TripMapDestinationType.LIVE)};var satellite by rememberSaveable{mutableStateOf(false)};var measuring by rememberSaveable{mutableStateOf(false)}
    var measureA by remember{mutableStateOf<LatLng?>(null)};var measureB by remember{mutableStateOf<LatLng?>(null)};var selectedWaypointId by rememberSaveable{mutableStateOf(initiallySelectedWaypointId)}
    val measureAState:MarkerState?=remember(measureA){measureA?.let{position->MarkerState(position)}};val measureBState:MarkerState?=remember(measureB){measureB?.let{position->MarkerState(position)}}
    val selected=data.waypoints.firstOrNull{it.id==selectedWaypointId}
    val scope=rememberCoroutineScope()
    LaunchedEffect(camera){snapshotFlow{camera.isMoving to camera.cameraMoveStartedReason}.collectLatest{(moving,reason)->if(moving&&reason==CameraMoveStartedReason.GESTURE)follow=false}}
    LaunchedEffect(mapLoaded,follow,vessel,tailKey){if(mapLoaded&&follow&&vessel!=null){kotlinx.coroutines.delay(900L);val course=state.trustedNmeaCourse?.takeIf{it.isFresh(android.os.SystemClock.elapsedRealtime())}?.trueDegrees;val heading=state.vesselData.headingTrueDegrees.value.takeIf{state.vesselData.headingTrueDegrees.freshness==VesselDataFreshness.FRESH};camera.animate(CameraUpdateFactory.newCameraPosition(CameraPosition(vessel,15f,0f,(course?:heading?:0.0).toFloat())))}}
    fun fit(){val all=route+data.waypoints.map{LatLng(it.latitude,it.longitude)}+listOfNotNull(vessel);if(all.isNotEmpty())scope.launch{if(all.size==1)camera.animate(CameraUpdateFactory.newLatLngZoom(all.first(),15f))else runCatching{camera.animate(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().also{builder->all.forEach(builder::include)}.build(),64))}}}
    LaunchedEffect(mapLoaded,session.id){if(mapLoaded)fit()}
    BoxWithConstraints(Modifier.fillMaxSize().testTag("trip_map_detail")){
        val compact=AdaptiveMarineLayoutPolicy.classify(maxWidth.value,maxHeight.value)==AdaptiveMarineLayoutMode.COMPACT_SQUARE
        GoogleMap(
            Modifier.fillMaxSize(),cameraPositionState=camera,onMapLoaded={mapLoaded=true},
            properties=MapProperties(mapType=if(satellite)MapType.SATELLITE else MapType.NORMAL),
            uiSettings=MarineMapPolicy.uiSettings(if(type==TripMapDestinationType.LIVE)MarineMapContext.LIVE_TRIP else MarineMapContext.TRIP_HISTORY),
            onMapClick={point->if(measuring){if(measureA==null||measureB!=null){measureA=point;measureB=null}else measureB=point}else selectedWaypointId=null},
        ){
            data.segments.forEachIndexed{index,segment->val points=segment.points.mapNotNull{point->if(point.hasPosition)LatLng(point.latitude!!,point.longitude!!)else null};if(points.size>1)Polyline(points=points,color=MaterialTheme.colorScheme.primary,width=6f,zIndex=2f,tag="trip-segment-$index")}
            route.firstOrNull()?.let{Marker(remember(it){MarkerState(it)},title=tr("Trip start","航程起点"),icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)})}
            route.lastOrNull()?.let{Marker(remember(it){MarkerState(it)},title=tr("Trip end","航程终点"),icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)})}
            data.waypoints.forEach{waypoint->val target=LatLng(waypoint.latitude,waypoint.longitude);Marker(remember(waypoint.id){MarkerState(target)},title=waypoint.name,snippet=waypoint.note,onClick={selectedWaypointId=waypoint.id;false},icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)})}
            vessel?.let{Marker(remember(it){MarkerState(it)},title=tr("Vessel","船位"),icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)})}
            measureAState?.let{Marker(it,title=tr("Ruler start","标尺起点"),draggable=true)}
            measureBState?.let{Marker(it,title=tr("Ruler end","标尺终点"),draggable=true)}
            if(measureA!=null&&measureB!=null)Polyline(points=listOf(measureA!!,measureB!!),color=MaterialTheme.colorScheme.tertiary,width=5f,zIndex=8f)
        }
        TopAppBar(
            title={Column{Text(if(type==TripMapDestinationType.LIVE)tr("Live Trip map","实时航程地图")else tr("Trip map","航程地图"),maxLines=1);Text(session.name,style=MaterialTheme.typography.labelSmall,maxLines=1)}},
            navigationIcon={IconButton(close,Modifier.testTag("close_trip_map")){Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("Back","返回"))}},
            actions={IconButton({satellite=!satellite}){Icon(Icons.Default.Layers,tr("Map layer","地图图层"))}},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.94f)),
        )
        Column(Modifier.align(Alignment.CenterEnd).padding(end=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            SmallFloatingActionButton({follow=true;vessel?.let{scope.launch{camera.animate(CameraUpdateFactory.newLatLngZoom(it,15f))}}},Modifier.testTag("trip_map_follow")){Icon(if(follow)Icons.Default.Navigation else Icons.Default.MyLocation,tr("Follow vessel","跟随船位"))}
            SmallFloatingActionButton(::fit,Modifier.testTag("trip_map_fit")){Icon(Icons.Default.ZoomOutMap,tr("Fit route","显示全程"))}
            SmallFloatingActionButton({measuring=!measuring;if(!measuring){measureA=null;measureB=null}},Modifier.testTag("trip_map_ruler")){Icon(Icons.Default.Straighten,tr("Ruler","测距"))}
        }
        MapScale(camera,Modifier.align(Alignment.BottomStart).padding(10.dp))
        if(measuring)Surface(Modifier.align(Alignment.TopCenter).padding(top=if(compact)54.dp else 70.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface.copy(alpha=.92f)){Text(if(measureA!=null&&measureB!=null)MapDistanceTools.measurementLabel(MapDistanceTools.distanceMeters(measureA!!.latitude,measureA!!.longitude,measureB!!.latitude,measureB!!.longitude))else tr("Tap two points","点选两个位置"),Modifier.padding(horizontal=10.dp,vertical=6.dp),fontWeight=FontWeight.SemiBold)}
        selected?.let{waypoint->ElevatedCard(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().testTag("trip_waypoint_details")){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(waypoint.name,fontWeight=FontWeight.SemiBold);Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(waypoint.timestamp)),style=MaterialTheme.typography.bodySmall);if(waypoint.note.isNotBlank())Text(waypoint.note,style=MaterialTheme.typography.bodySmall,maxLines=2)};IconButton({selectedWaypointId=null}){Icon(Icons.Default.Close,tr("Close","关闭"))}}}}
    }
}

@Composable private fun MapScale(camera:CameraPositionState,modifier:Modifier){
    val density=LocalDensity.current.density;val scale=MapDistanceTools.scaleBar(camera.position.target.latitude,camera.position.zoom,120f*density)
    Surface(modifier,shape=MaterialTheme.shapes.extraSmall,color=MaterialTheme.colorScheme.surface.copy(alpha=.88f)){Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)){Text(scale.label,style=MaterialTheme.typography.labelSmall);HorizontalDivider(Modifier.width(with(LocalDensity.current){scale.widthPixels.toDp()}).height(2.dp))}}
}
