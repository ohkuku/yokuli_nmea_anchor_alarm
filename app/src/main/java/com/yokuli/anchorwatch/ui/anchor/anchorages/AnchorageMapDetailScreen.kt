package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.domain.vessel.VesselPosition
import com.yokuli.anchorwatch.map.MapDistanceTools
import com.yokuli.anchorwatch.map.MarineMapContext
import com.yokuli.anchorwatch.map.MarineMapPolicy
import com.yokuli.anchorwatch.tr
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AnchorageMapDetailDialog(bundle:AnchoragePlaceBundle,initialSpotId:Long?,vessel:VesselPosition?,dismiss:()->Unit,approach:(Long)->Unit){
    Dialog(dismiss,DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        if(!BuildConfig.MAPS_CONFIGURED){Scaffold(topBar={TopAppBar(title={Text(bundle.place.displayName)},navigationIcon={IconButton(dismiss){Icon(Icons.Default.Close,tr("Close","关闭"))}})}){padding->Box(Modifier.fillMaxSize().padding(padding).padding(20.dp),contentAlignment=Alignment.Center){Text(tr("The map SDK is unavailable. Saved positions and details remain available.","地图 SDK 当前不可用；收藏位置和资料仍可查看。"))}};return@Dialog}
        val selectedDefault=initialSpotId?:bundle.spots.firstOrNull()?.id
        var selectedId by rememberSaveable(bundle.place.id){mutableStateOf(selectedDefault)};var satellite by rememberSaveable{mutableStateOf(false)};var measuring by rememberSaveable{mutableStateOf(false)};var firstMeasure by remember{mutableStateOf<LatLng?>(null)};var secondMeasure by remember{mutableStateOf<LatLng?>(null)}
        val firstMeasureState:MarkerState?=remember(firstMeasure){firstMeasure?.let{position->MarkerState(position)}};val secondMeasureState:MarkerState?=remember(secondMeasure){secondMeasure?.let{position->MarkerState(position)}}
        val selected=bundle.spots.firstOrNull{it.id==selectedId};val center=selected?.let{LatLng(it.latitude,it.longitude)}?:LatLng(bundle.place.centerLatitude,bundle.place.centerLongitude);val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(center,14f)};val scope=rememberCoroutineScope()
        fun fit(){val all=bundle.spots.map{LatLng(it.latitude,it.longitude)}+listOfNotNull(vessel?.let{LatLng(it.latitude,it.longitude)});scope.launch{if(all.size<=1)camera.animate(CameraUpdateFactory.newLatLngZoom(all.firstOrNull()?:center,15f))else runCatching{camera.animate(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().also{builder->all.forEach(builder::include)}.build(),64))}}}
        Box(Modifier.fillMaxSize().testTag("anchorage_map_detail")){
            GoogleMap(Modifier.fillMaxSize(),cameraPositionState=camera,properties=MapProperties(mapType=if(satellite)MapType.SATELLITE else MapType.NORMAL),uiSettings=MarineMapPolicy.uiSettings(MarineMapContext.ANCHORAGE_DETAIL),onMapClick={point->if(measuring){if(firstMeasure==null||secondMeasure!=null){firstMeasure=point;secondMeasure=null}else secondMeasure=point}else selectedId=null}){
                bundle.spots.forEach{spot->val target=LatLng(spot.latitude,spot.longitude);Marker(remember(spot.id){MarkerState(target)},title=spot.name,onClick={selectedId=spot.id;false},icon=remember{BitmapDescriptorFactory.defaultMarker(if(spot.id==selectedId)BitmapDescriptorFactory.HUE_ORANGE else BitmapDescriptorFactory.HUE_AZURE)})}
                vessel?.let{position->val target=LatLng(position.latitude,position.longitude);Marker(remember(target){MarkerState(target)},title=tr("Vessel","船位"),icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)})}
                firstMeasureState?.let{Marker(it,title=tr("Ruler start","标尺起点"))}
                secondMeasureState?.let{Marker(it,title=tr("Ruler end","标尺终点"))}
                if(firstMeasure!=null&&secondMeasure!=null)Polyline(points=listOf(firstMeasure!!,secondMeasure!!),color=MaterialTheme.colorScheme.tertiary,width=5f)
            }
            TopAppBar(title={Column{Text(bundle.place.displayName,maxLines=1);Text(tr("Saved anchorage map","收藏锚地地图"),style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(dismiss){Icon(Icons.Default.Close,tr("Close","关闭"))}},actions={IconButton({satellite=!satellite}){Icon(Icons.Default.Layers,tr("Map layer","地图图层"))}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.94f)))
            Column(Modifier.align(Alignment.CenterEnd).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                SmallFloatingActionButton({selected?.let{spot->scope.launch{camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(spot.latitude,spot.longitude),15f))}}}){Icon(Icons.Default.MyLocation,tr("Recenter","回到中心"))}
                SmallFloatingActionButton(::fit){Icon(Icons.Default.ZoomOutMap,tr("Fit all","显示全部"))}
                SmallFloatingActionButton({measuring=!measuring;if(!measuring){firstMeasure=null;secondMeasure=null}},Modifier.testTag("anchorage_map_ruler")){Icon(Icons.Default.Straighten,tr("Ruler","测距"))}
            }
            if(measuring)Surface(Modifier.align(Alignment.TopCenter).padding(top=70.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface.copy(alpha=.92f)){Text(if(firstMeasure!=null&&secondMeasure!=null)MapDistanceTools.measurementLabel(MapDistanceTools.distanceMeters(firstMeasure!!.latitude,firstMeasure!!.longitude,secondMeasure!!.latitude,secondMeasure!!.longitude))else tr("Tap two points","点选两个位置"),Modifier.padding(horizontal=10.dp,vertical=6.dp),fontWeight=FontWeight.SemiBold)}
            selected?.let{spot->ElevatedCard(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).testTag("anchorage_map_selected_spot")){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(spot.name,fontWeight=FontWeight.SemiBold);Text("%.6f, %.6f".format(spot.latitude,spot.longitude),style=MaterialTheme.typography.bodySmall);vessel?.let{position->val distance=MapDistanceTools.distanceMeters(position.latitude,position.longitude,spot.latitude,spot.longitude);val bearing=MapDistanceTools.initialBearingDegrees(position.latitude,position.longitude,spot.latitude,spot.longitude);Text(tr("${MapDistanceTools.measurementLabel(distance)} · bearing ${"%03.0f".format(bearing)}°T","${MapDistanceTools.measurementLabel(distance)} · 方位 ${"%03.0f".format(bearing)}°T"))};Button({approach(spot.id)},Modifier.fillMaxWidth()){Icon(Icons.Default.Navigation,null);Spacer(Modifier.width(6.dp));Text(tr("Approach this position","接近这个位置"))}}}}
        }
    }
}
