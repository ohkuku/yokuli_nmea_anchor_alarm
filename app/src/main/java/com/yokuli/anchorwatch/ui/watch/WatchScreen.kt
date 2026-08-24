package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.os.PowerManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.AnchorRangeCalculator
import com.yokuli.anchorwatch.domain.anchor.CoordinateParser
import com.yokuli.anchorwatch.domain.anchor.WindAnchorEvidence
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPositionMode
import com.yokuli.anchorwatch.domain.model.CandidateDecision
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.DemoScenario
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.map.LinzHydroConfiguration
import com.yokuli.anchorwatch.map.LinzHydroTileProvider
import com.yokuli.anchorwatch.map.LinzHydroDiagnostics
import com.yokuli.anchorwatch.map.MapOverlayZ
import com.yokuli.anchorwatch.map.MapDistanceTools
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.map.SonarTileProvider
import com.yokuli.anchorwatch.map.localdepth.GeoPoint
import com.yokuli.anchorwatch.map.localdepth.LocalDepthAvailability
import com.yokuli.anchorwatch.map.localdepth.MapChartPolicy
import com.yokuli.anchorwatch.map.localdepth.MapChartUiState
import com.yokuli.anchorwatch.map.nautical.OpenSeaMapConfiguration
import com.yokuli.anchorwatch.map.nautical.OpenSeaMapDiagnostics
import com.yokuli.anchorwatch.map.nautical.OpenSeaMapTileProvider
import com.yokuli.anchorwatch.map.nautical.NauticalPrimarySource
import com.yokuli.anchorwatch.map.nautical.NauticalSourcePreference
import com.yokuli.anchorwatch.map.nautical.MapOverlayPreferences
import com.yokuli.anchorwatch.map.nautical.NauticalSourceResolver
import com.yokuli.anchorwatch.map.style.BaseMapStyle
import com.yokuli.anchorwatch.map.style.GoogleBaseMapKind
import com.yokuli.anchorwatch.map.style.MapStylePolicy
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.SonarMapDisplayPolicy
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import java.text.DateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private data class SonarMapInspection(
    val grid:com.yokuli.anchorwatch.domain.sonar.SonarInspection,
)

@Composable
internal fun AnchorageApproachDestinationHost(state:MainUiState,vm:MainViewModel){
    val target=state.anchorageApproach.target
    var anchorageDetails by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var anchorageListDetails by remember{mutableStateOf<List<Long>?>(null)}
    var setupReference by remember{mutableStateOf<AnchorageSetupReference?>(null)}
    var showPreflight by remember{mutableStateOf(false)}
    var showSetup by remember{mutableStateOf(false)}

    fun openDetails(cluster:com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster){
        when(val resolved=com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsPolicy.resolve(cluster)){
            is com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget.SavedAnchorage->{
                val saved=state.savedAnchorages.firstOrNull{it.id==resolved.id}
                if(saved!=null){anchorageListDetails=null;anchorageDetails=saved}
                else{anchorageDetails=null;anchorageListDetails=listOf(resolved.id)}
            }
            is com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget.AnchorageList->{
                anchorageDetails=null;anchorageListDetails=resolved.ids
            }
        }
    }

    BackHandler(enabled=target!=null){vm.cancelAnchorageApproach()}
    target?.let{cluster->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("anchorage_approach_fullscreen")){
            AnchorageApproachOverlay(
                state=state.anchorageApproach,
                headingMode=state.approachHeadingMode,
                vesselHeadingAvailable=state.vesselApproachHeadingAvailable,
                setHeadingMode=vm::setApproachHeadingMode,
                details=::openDetails,
                cancel=vm::cancelAnchorageApproach,
                setAnchorWatch={reference->
                    if(state.activeTrip==null){
                        setupReference=reference
                        vm.cancelAnchorageApproach()
                        showPreflight=true
                    }
                },
            )
        }
    }
    anchorageDetails?.let{saved->AnchorageDetailDialog(
        saved=saved,
        dismiss={anchorageDetails=null},
        openGoogleMaps={vm.openAnchorageInGoogleMaps(saved)},
        shareQr={vm.shareAnchorageQr(saved)},
        approach={anchorageDetails=null;vm.approachSavedAnchorage(saved.id)},
        approachEnabled=state.active==null,
    )}
    anchorageListDetails?.let{ids->AnchorageListDialog(
        members=ids.mapNotNull{id->state.savedAnchorages.firstOrNull{it.id==id}},
        dismiss={anchorageListDetails=null},
        actions=SavedAnchorageCardActions(
            approach={saved->anchorageListDetails=null;vm.approachSavedAnchorage(saved.id)},
            openGoogleMaps=vm::openAnchorageInGoogleMaps,
            shareQr=vm::shareAnchorageQr,
            approachEnabled=state.active==null,
        ),
    )}
    if(state.approachDisclaimerTargetId!=null)AnchorageApproachDisclaimerDialog(vm::confirmAnchorageApproachDisclaimer,vm::dismissAnchorageApproachDisclaimer)
    if(showPreflight)WatchPreflightSheet(state,{showPreflight=false;setupReference=null}){showPreflight=false;showSetup=true}
    if(showSetup)AnchorSetupSheet(state,{showSetup=false;setupReference=null},reference=setupReference){lat,lon,input->vm.arm(lat,lon,input)}
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
internal fun AnchorWatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showPreflight by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) };var showLayers by remember{mutableStateOf(false)};var showLinzDisclaimer by remember{mutableStateOf(false)};var showSonarDisclaimer by remember{mutableStateOf(false)};var showNauticalDisclaimer by remember{mutableStateOf(false)}
    var anchorageDetails by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var anchorageListDetails by remember{mutableStateOf<List<Long>?>(null)}
    var setupReference by remember{mutableStateOf<AnchorageSetupReference?>(null)}
    val openAnchorageList:(List<Long>)->Unit={ids->anchorageDetails=null;anchorageListDetails=ids.distinct()}
    val nearbyActions=SavedAnchorageCardActions(
        approach={saved->anchorageListDetails=null;vm.approachSavedAnchorage(saved.id)},
        openGoogleMaps=vm::openAnchorageInGoogleMaps,
        shareQr=vm::shareAnchorageQr,
        approachEnabled=state.active==null,
    )
    val openAnchorageDetails:(List<com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster>)->Unit={clusters->
        when(val target=com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsPolicy.resolve(clusters)){
            is com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget.SavedAnchorage->{
                val saved=state.savedAnchorages.firstOrNull{it.id==target.id}
                if(saved!=null){anchorageListDetails=null;anchorageDetails=saved}else openAnchorageList(listOf(target.id))
            }
            is com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget.AnchorageList->openAnchorageList(target.ids)
        }
    }
    val fix = state.fix; val active = state.active
    // Map, instruments and NMEA output consume the same canonical presentation
    // selection. Safety/evidence applies stricter quality gates downstream, but
    // the map must not invent a second heading priority tree or use COG as bow.
    val boatHeading=state.vesselData.headingTrueDegrees.value
    DisposableEffect(Unit){vm.setMapHeadingDisplayActive(true);onDispose{vm.setMapHeadingDisplayActive(false)}}
    LaunchedEffect(state.rangeEditorRequested,active?.id){if(state.rangeEditorRequested){showAdjust=active!=null;vm.consumeRangeEditorRequest()}}
    val renderGoogleMap = BuildConfig.MAPS_CONFIGURED && MapRuntimePolicy.renderGoogleEngine
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val context=androidx.compose.ui.platform.LocalContext.current
    val baseMapStyle=BaseMapStyle.fromPersisted(state.settings.mapType)
    val baseMapPolicy=MapStylePolicy.forStyle(baseMapStyle)
    val nauticalSource=remember(baseMapStyle,state.offlineMap.installed,state.offlineMap.name,state.settings.offlineMapEnabled){
        NauticalSourceResolver.resolve(baseMapStyle,state.offlineMap.installed,if(state.settings.offlineMapEnabled)NauticalSourcePreference.USER_MBTILES else NauticalSourcePreference.DEFAULT_ONLINE,state.offlineMap.name)
    }
    val overlayPreferences=remember(state.settings.linzHydroEnabled,state.settings.linzHydroOpacity,state.settings.sonarLayerEnabled,state.settings.showLinzDepthReference,state.settings.showPersonalMapReference){MapOverlayPreferences(state.settings.linzHydroEnabled,state.settings.linzHydroOpacity,state.settings.sonarLayerEnabled,state.settings.showLinzDepthReference,state.settings.showPersonalMapReference)}
    val nauticalMapStyle=remember(context){runCatching{MapStyleOptions.loadRawResourceStyle(context,R.raw.map_style_nautical)}.getOrNull()}
    val nauticalTileProvider=remember(context){OpenSeaMapTileProvider(java.io.File(context.filesDir,"offline_maps/openseamap_recent"),BuildConfig.VERSION_NAME)}
    val linzTileProviders=remember{val templates=BuildConfig.LINZ_HYDRO_TILE_TEMPLATES.split('|').filter(LinzHydroConfiguration::isUsable);val perChartCache=LinzHydroTileProvider.MAX_DISK_BYTES/templates.size.coerceAtLeast(1);templates.mapIndexed{index,template->LinzHydroTileProvider(template,java.io.File(context.filesDir,"offline_maps/linz_recent/$index"),perChartCache)}}
    val offlineTileProvider=remember(nauticalSource.userChartEnabled,state.offlineMap.revision){if(nauticalSource.userChartEnabled)vm.createOfflineMapProvider()else null}
    DisposableEffect(offlineTileProvider){onDispose{offlineTileProvider?.close()}}
    val sonarGrid=state.sonarGrid
    val sonarTileProvider=remember{SonarTileProvider(SonarGrid.build(emptyList()))};val sonarTileOverlayState=rememberTileOverlayState()
    val sonarLayerVisible=SonarMapDisplayPolicy.isVisible(overlayPreferences.personalSonarEnabled,sonarGrid.cells.isNotEmpty())
    val sonarOverlayMounted=renderGoogleMap&&sonarLayerVisible&&sonarGrid.cells.isNotEmpty()
    var sonarInspection by remember{mutableStateOf<SonarMapInspection?>(null)}
    LaunchedEffect(sonarGrid,state.sonarGridVersion,sonarOverlayMounted){val version=state.sonarGridVersion;val changed=sonarTileProvider.updateGrid(sonarGrid,state.sonarGridChangedCells.takeIf{it.isNotEmpty()});if(changed>0&&sonarOverlayMounted)runCatching{sonarTileOverlayState.clearTileCache()};vm.consumeSonarGridChanges(version)}
    LaunchedEffect(sonarLayerVisible,sonarGrid){
        if(!sonarLayerVisible||sonarGrid.cells.isEmpty())sonarInspection=null
    }
    val initialPreciseFix=fix?.takeUnless{it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_NETWORK}
    val camera = rememberCameraPositionState { position = if(initialPreciseFix!=null)CameraPosition.fromLatLngZoom(LatLng(initialPreciseFix.latitude,initialPreciseFix.longitude), MapCameraPolicy.DEFAULT_FOLLOW_ZOOM)else CameraPosition.fromLatLngZoom(LatLng(0.0,0.0),2f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var hasCenteredOnFix by remember { mutableStateOf(false) }
    var followedSource by remember { mutableStateOf<GpsDataSource?>(null) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var mapLocked by rememberSaveable{mutableStateOf(true)}
    var measuringDistance by rememberSaveable{mutableStateOf(false)}
    val effectiveMapLocked=mapLocked&&!measuringDistance
    val measurementStartState=remember{MarkerState(camera.position.target)}
    val measurementEndState=remember{MarkerState(camera.position.target)}
    val measurementMidState=remember{MarkerState(camera.position.target)}
    var measurementTapStage by remember{mutableIntStateOf(0)}
    var measurementPreviousFollow by remember{mutableStateOf(true)}
    var lockedGestureReturnPending by remember{mutableStateOf(false)}
    var returningLockedCamera by remember{mutableStateOf(false)}
    val latestFix by rememberUpdatedState(fix)
    var inspectionTarget by remember{mutableStateOf<LatLng?>(null)}
    LaunchedEffect(measuringDistance,measurementStartState.position,measurementEndState.position){
        if(measuringDistance){val middle=MapDistanceTools.midpoint(measurementStartState.position.latitude,measurementStartState.position.longitude,measurementEndState.position.latitude,measurementEndState.position.longitude);measurementMidState.position=LatLng(middle.first,middle.second)}
    }
    LaunchedEffect(measuringDistance){
        if(!measuringDistance)return@LaunchedEffect
        snapshotFlow{measurementMidState.position}.collect{moved->
            val start=measurementStartState.position;val end=measurementEndState.position
            val expected=MapDistanceTools.midpoint(start.latitude,start.longitude,end.latitude,end.longitude)
            if(MapDistanceTools.distanceMeters(expected.first,expected.second,moved.latitude,moved.longitude)>0.75){
                val translated=MapDistanceTools.translateRuler(start.latitude,start.longitude,end.latitude,end.longitude,moved.latitude,moved.longitude)
                measurementStartState.position=LatLng(translated.first.first,translated.first.second)
                measurementEndState.position=LatLng(translated.second.first,translated.second.second)
            }
        }
    }
    LaunchedEffect(effectiveMapLocked){if(!effectiveMapLocked)inspectionTarget=camera.position.target}
    LaunchedEffect(camera,effectiveMapLocked){
        snapshotFlow{camera.isMoving}.collect{moving->if(!moving&&!effectiveMapLocked)inspectionTarget=camera.position.target}
    }
    LaunchedEffect(camera,mapLocked,measuringDistance){
        if(!mapLocked||measuringDistance){lockedGestureReturnPending=false;returningLockedCamera=false;return@LaunchedEffect}
        snapshotFlow{camera.isMoving to camera.cameraMoveStartedReason}.collectLatest{(moving,reason)->
            if(moving&&reason==CameraMoveStartedReason.GESTURE){lockedGestureReturnPending=true;return@collectLatest}
            if(!moving&&lockedGestureReturnPending){
                delay(MapCameraPolicy.LOCKED_GESTURE_RETURN_DELAY_MILLIS)
                val current=latestFix
                lockedGestureReturnPending=false
                if(current?.valid==true){
                    returningLockedCamera=true
                    try{camera.animate(CameraUpdateFactory.newLatLng(LatLng(current.latitude,current.longitude)))}finally{returningLockedCamera=false}
                }
            }
        }
    }
    val acceptedMapPoint=fix?.takeIf{it.valid}?.let{GeoPoint(it.latitude,it.longitude)}
    val cameraMapPoint=inspectionTarget?.let{GeoPoint(it.latitude,it.longitude)}
    val mapChartUiState=remember(effectiveMapLocked,acceptedMapPoint,cameraMapPoint,state.settings.linzHydroEnabled,state.settings.linzHydroOpacity){
        MapChartPolicy.resolve(effectiveMapLocked,acceptedMapPoint,cameraMapPoint,BuildConfig.LINZ_HYDRO_CONFIGURED,overlayPreferences.linzNzChartEnabled,overlayPreferences.linzNzChartOpacity)
    }
    val mapAttribution=buildList{
        if(baseMapStyle==BaseMapStyle.NAUTICAL)add(OpenSeaMapConfiguration.ATTRIBUTION)
        if(mapChartUiState.localDepthVisible)add(LinzHydroConfiguration.ATTRIBUTION)
        if(nauticalSource.userChartEnabled)add(state.settings.offlineMapAttribution?:tr("User-supplied MBTiles","用户导入的 MBTiles"))
    }.joinToString("\n")
    LaunchedEffect(recenterRequest,mapLocked){if(recenterRequest>0&&!mapLocked){delay(900);vm.follow(false)}}
    LaunchedEffect(mapLoaded, fix?.latitude, fix?.longitude, fix?.valid, state.follow, state.settings.gpsDataSource, recenterRequest, camera.isMoving, lockedGestureReturnPending, returningLockedCamera, measuringDistance) {
        if (mapLoaded && fix?.valid == true && fix.positionProvider!=com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_NETWORK && state.follow && !measuringDistance && !camera.isMoving && !lockedGestureReturnPending && !returningLockedCamera) {
            val target = LatLng(fix.latitude, fix.longitude)
            val update = when (MapCameraPolicy.nextMove(hasCenteredOnFix, followedSource, state.settings.gpsDataSource)) {
                FollowCameraMove.CENTER_WITH_DEFAULT_ZOOM -> CameraUpdateFactory.newLatLngZoom(target, MapCameraPolicy.DEFAULT_FOLLOW_ZOOM)
                FollowCameraMove.CENTER_PRESERVING_ZOOM -> CameraUpdateFactory.newLatLng(target)
            }
            camera.animate(update)
            hasCenteredOnFix = true
            followedSource = state.settings.gpsDataSource
        }
    }
    val bottomSheetState=rememberStandardBottomSheetState(initialValue=SheetValue.PartiallyExpanded,skipHiddenState=true)
    val scaffoldState=rememberBottomSheetScaffoldState(bottomSheetState=bottomSheetState)
    val bottomSheetScope=rememberCoroutineScope()
    BottomSheetScaffold(
        modifier=Modifier.fillMaxSize(),
        scaffoldState=scaffoldState,
        sheetPeekHeight=104.dp,
        sheetShadowElevation=8.dp,
        // Root tab pagers are click-only, so the sheet may keep its intended
        // vertical drag without a parent horizontal pager stealing the map.
        sheetSwipeEnabled=true,
        sheetDragHandle={Box(Modifier.fillMaxWidth().clickable{bottomSheetScope.launch{if(bottomSheetState.currentValue==SheetValue.Expanded)bottomSheetState.partialExpand()else bottomSheetState.expand()}}.testTag("watch_sheet_toggle"),contentAlignment=Alignment.Center){BottomSheetDefaults.DragHandle()}},
        sheetContent={WatchPanel(state,boatHeading,{setupReference=null;showPreflight=true},{showAdjust=true},{vm.openDataSection(0)},{active?.let(vm::resetCentreAnalysis)},vm::updateConditionGuards,vm::resetWindBaseline,openAnchorageList,nearbyActions,vm::pauseWatch,vm::resumeWatch,{confirmLift=true},{active?.let(vm::openAnchorInGoogleMaps)},{active?.let(vm::recalculateCentreFromTrack)},vm::reconnectNmea,{vm.openDataSection(1)}){vm.switchGpsDataSource(GpsDataSource.SYSTEM)}},
    ) { _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (renderGoogleMap) {
                    val gesturePolicy=MapCameraPolicy.gestures(effectiveMapLocked)
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(baseMapPolicy.googleBaseMap==GoogleBaseMapKind.SATELLITE)MapType.SATELLITE else MapType.NORMAL,mapStyleOptions=if(baseMapPolicy.applyNauticalStyle)nauticalMapStyle else null), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false,scrollGesturesEnabled=gesturePolicy.scrollEnabled,zoomGesturesEnabled=gesturePolicy.zoomEnabled,rotationGesturesEnabled=gesturePolicy.rotationEnabled,tiltGesturesEnabled=gesturePolicy.tiltEnabled), onMapLoaded = { mapLoaded = true },onMapClick={point->
                        if(measuringDistance){
                            if(measurementTapStage%2==0)measurementStartState.position=point else measurementEndState.position=point
                            measurementTapStage++
                            sonarInspection=null
                        }else{
                            val inspection=if(sonarLayerVisible)sonarGrid.inspect(point.latitude,point.longitude)else null
                            sonarInspection=inspection?.let{SonarMapInspection(it)}
                        }
                    }) {
                        // BitmapDescriptorFactory is initialized by the GoogleMap instance.
                        // Creating descriptors before entering this content lambda races cold
                        // Maps startup and crashes with "IBitmapDescriptorFactory is not initialized".
                        val boatIcon=remember{boatMarkerIcon()};val anchorIcon=remember{anchorMarkerIcon()};val savedAnchorIcon=remember{savedAnchorageMarkerIcon()}
                        val measureStartIcon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)}
                        val measureEndIcon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)}
                        if(nauticalSource.primary==NauticalPrimarySource.USER_MBTILES&&offlineTileProvider!=null)TileOverlay(tileProvider=offlineTileProvider,fadeIn=false,transparency=(1.0-state.settings.offlineMapOpacity.coerceIn(.30,1.0)).toFloat(),visible=true,zIndex=MapOverlayZ.OFFLINE_CHART)
                        if(mapChartUiState.localDepthVisible)linzTileProviders.forEach{provider->TileOverlay(tileProvider=provider,fadeIn=true,transparency=LinzHydroConfiguration.transparency(mapChartUiState.localDepthOpacity),visible=true,zIndex=MapOverlayZ.LINZ_CHART)}
                        if(sonarOverlayMounted)TileOverlay(tileProvider=sonarTileProvider,state=sonarTileOverlayState,fadeIn=false,transparency=.25f,visible=true,zIndex=MapOverlayZ.SONAR)
                        if(baseMapPolicy.showSeamarks)TileOverlay(tileProvider=nauticalTileProvider,fadeIn=true,transparency=0f,visible=true,zIndex=MapOverlayZ.NAUTICAL_SEAMARKS)
                        state.anchorageClusters.forEach{cluster->
                            val centre=LatLng(cluster.centerLatitude,cluster.centerLongitude)
                            val selected=cluster.id==state.anchorageApproach.selectedClusterId
                            Circle(
                                center=centre,
                                radius=cluster.radiusMeters,
                                strokeColor=Color(0xFF087F8C).copy(alpha=if(selected).90f else .55f),
                                fillColor=Color(0xFF087F8C).copy(alpha=if(selected).10f else .045f),
                                strokeWidth=if(selected)4f else 2f,
                                zIndex=MapOverlayZ.HISTORICAL_AREA,
                                visible=selected||camera.position.zoom>=13f,
                                strokePattern=if(cluster.radiusEstimated)listOf(Dash(16f),Gap(10f))else emptyList(),
                            )
                            Marker(
                                state=remember(cluster.id,cluster.centerLatitude,cluster.centerLongitude){MarkerState(centre)},
                                title=if(cluster.savedPointCount==1)cluster.displayName else "${cluster.displayName} · ⚓ ×${cluster.savedPointCount}",
                                snippet=if(cluster.coordinateEstimated)tr("Approximate saved anchorage reference","估算的收藏锚地参考")else tr("Saved anchorage reference","收藏锚地参考"),
                                icon=savedAnchorIcon,
                                alpha=if(selected)1f else if(cluster.coordinateEstimated).62f else .78f,
                                anchor=Offset(.5f,.5f),
                                zIndex=MapOverlayZ.HISTORICAL_ANCHOR,
                                onClick={openAnchorageDetails(listOf(cluster));true},
                            )
                        }
                        state.anchorageApproach.target?.let{target->
                            fix?.takeIf{it.valid}?.let{position->
                                Polyline(
                                    points=listOf(LatLng(position.latitude,position.longitude),LatLng(target.centerLatitude,target.centerLongitude)),
                                    color=Color(0xFF087F8C).copy(alpha=.85f),
                                    width=4f,
                                    pattern=listOf(Dash(24f),Gap(14f)),
                                    zIndex=.9f,
                                )
                            }
                        }
                        if(measuringDistance){
                            Marker(state=measurementStartState,title=tr("Measurement start","测距起点"),icon=measureStartIcon,zIndex=MapOverlayZ.MEASUREMENT,draggable=true)
                            Marker(state=measurementEndState,title=tr("Measurement end","测距终点"),icon=measureEndIcon,zIndex=MapOverlayZ.MEASUREMENT,draggable=true)
                            Marker(state=measurementMidState,title=tr("Drag the whole ruler","拖动整条标尺"),icon=remember{BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)},zIndex=MapOverlayZ.MEASUREMENT,draggable=true)
                            Polyline(points=listOf(measurementStartState.position,measurementEndState.position),color=Color(0xFF00ACC1),width=5f,zIndex=MapOverlayZ.MEASUREMENT)
                        }
                        fix?.let { position -> Marker(state=remember(position.latitude, position.longitude){MarkerState(LatLng(position.latitude,position.longitude))},title=tr("Boat","船位"),icon=boatIcon,rotation=(boatHeading?:0.0).toFloat(),flat=true,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.BOAT) }
                        active?.let { session ->
                            if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){val anchor=LatLng(session.anchorLatitude,session.anchorLongitude)
                             Marker(state=remember(session.anchorLatitude,session.anchorLongitude){MarkerState(anchor)},title=tr("Adopted safety anchor","已采用的安全锚点"),icon=anchorIcon,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR)
                             Circle(center=anchor,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Alarm,fillColor=SafetyColors.Alarm.copy(alpha=.09f),strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             // Continuous estimation is advisory until the user
                             // explicitly adopts it. Show uncertainty only; do
                             // not draw a second anchor symbol or move the alarm.
                             if(session.estimationEpoch>0L&&session.provisionalAnchorLatitude!=null&&session.provisionalAnchorLongitude!=null){Circle(center=LatLng(session.provisionalAnchorLatitude,session.provisionalAnchorLongitude),radius=session.provisionalRadiusMeters?:session.expectedSwingRadiusMeters.coerceAtLeast(10.0),strokeColor=SafetyColors.Candidate,fillColor=SafetyColors.Candidate.copy(alpha=.08f),strokeWidth=2f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                            }
                            else{
                             val reference=LatLng(session.learningReferenceLatitude?:session.anchorLatitude,session.learningReferenceLongitude?:session.anchorLongitude)
                             Circle(center=reference,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Warning,fillColor=SafetyColors.Warning.copy(alpha=.09f),strokeWidth=4f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             val estimatedLat=session.provisionalAnchorLatitude;val estimatedLon=session.provisionalAnchorLongitude
                             if(estimatedLat!=null&&estimatedLon!=null){val estimated=LatLng(estimatedLat,estimatedLon)
                              if(session.candidateDecision==CandidateDecision.AVAILABLE.name){Marker(state=remember(estimatedLat,estimatedLon){MarkerState(estimated)},title=tr("Candidate anchor · approval required","候选锚点 · 等待确认"),icon=anchorIcon,alpha=.80f,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR);Circle(center=estimated,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Candidate,fillColor=Color.Transparent,strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                              else Circle(center=estimated,radius=session.provisionalRadiusMeters?:session.expectedSwingRadiusMeters.coerceAtLeast(10.0),strokeColor=SafetyColors.Learning,fillColor=SafetyColors.Learning.copy(alpha=.13f),strokeWidth=2f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             }
                            }
                            trail.forEach{chunk->Polyline(points=chunk.points,color=SafetyColors.Trail.copy(alpha=chunk.alpha),width=4f,zIndex=MapOverlayZ.TRAIL)}
                        }
                    }
            } else if(!BuildConfig.MAPS_CONFIGURED) MapNotConfigured()
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).testTag("map_test_surface"))
            CompactWatchStatus(state,Modifier.align(Alignment.TopStart).padding(12.dp))
            com.yokuli.anchorwatch.ui.anchor.anchorages.GisNearbyAnchorageCard(
                latitude=fix?.latitude,
                longitude=fix?.longitude,
                enabled=active==null&&state.anchorageApproach.target==null,
                approachSpot=vm::approachAnchorageSpot,
                modifier=Modifier.align(Alignment.TopCenter).padding(start=12.dp,end=12.dp,top=76.dp),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick={mapLocked=!mapLocked;if(mapLocked){hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)}else vm.follow(false)},modifier=Modifier.testTag("map_lock_toggle")){Icon(if(mapLocked)Icons.Default.Lock else Icons.Default.LockOpen,if(mapLocked)tr("Auto-return to boat · pan and zoom remain available","自动回到船位 · 仍可拖动缩放")else tr("Free map browsing","地图自由浏览"))}
                FilledTonalIconButton(onClick={if(measuringDistance){measuringDistance=false;vm.follow(measurementPreviousFollow)};hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)},modifier=Modifier.testTag("map_recenter")) { Icon(Icons.Default.MyLocation, tr("Recenter on boat","回到船位")) }
                FilledTonalIconButton(onClick={
                    if(measuringDistance){measuringDistance=false;vm.follow(measurementPreviousFollow)}
                    else{
                        measurementPreviousFollow=state.follow
                        measuringDistance=true
                        vm.follow(false)
                        val centre=camera.position.target
                        val halfDistance=(MapDistanceTools.metersPerPixel(centre.latitude,camera.position.zoom)*70.0).coerceIn(10.0,1_000.0)
                        val first=AnchorGeometry.project(centre.latitude,centre.longitude,270.0,halfDistance)
                        val second=AnchorGeometry.project(centre.latitude,centre.longitude,90.0,halfDistance)
                        measurementStartState.position=LatLng(first.first,first.second);measurementEndState.position=LatLng(second.first,second.second);measurementMidState.position=centre;measurementTapStage=0
                    }
                    sonarInspection=null
                },modifier=Modifier.testTag("map_measure_toggle"),colors=if(measuringDistance)IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.primaryContainer)else IconButtonDefaults.filledTonalIconButtonColors()) { Icon(Icons.Default.Straighten, if(measuringDistance)tr("Stop measuring and clear pins","停止测距并清除图钉")else tr("Measure distance","测量距离")) }
                FilledTonalIconButton({showLayers=true},modifier=Modifier.testTag("map_layers")){Icon(Icons.Default.Layers,tr("Map layers","地图图层"))}
            }
            if(renderGoogleMap)MapScaleIndicator(camera.position.target.latitude,camera.position.zoom,Modifier.align(Alignment.BottomStart).padding(start=12.dp,bottom=112.dp))
            if(measuringDistance){
                val start=measurementStartState.position;val end=measurementEndState.position
                val label=MapDistanceTools.measurementLabel(MapDistanceTools.distanceMeters(start.latitude,start.longitude,end.latitude,end.longitude))+" · %03.0f°T · ".format(MapDistanceTools.initialBearingDegrees(start.latitude,start.longitude,end.latitude,end.longitude))+tr("drag A/B, midpoint or tap map","拖动 A/B、中心点或点击地图")
                Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp).padding(bottom=146.dp).testTag("map_measure_result"),color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),shape=MaterialTheme.shapes.medium,shadowElevation=4.dp){Text(label,Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)}
            }
            if(mapAttribution.isNotBlank())Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp).padding(bottom=112.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f),shape=MaterialTheme.shapes.small){Text(mapAttribution,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
            sonarInspection?.let{inspection->
                val survey=state.selectedSonarSurveyId?.takeIf{it!=CORRECTED_SONAR_HISTORY_ID}?.let{id->state.sonarSurveys.firstOrNull{it.id==id}}
                Surface(Modifier.align(Alignment.TopCenter).padding(start=16.dp,top=72.dp,end=16.dp).clickable{sonarInspection=null},color=MaterialTheme.colorScheme.surface.copy(alpha=.96f),shape=MaterialTheme.shapes.medium,shadowElevation=4.dp){Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(inspection.grid.measured)tr("Measured depth","实测水深")else tr("Estimated from nearby sonar samples","由附近声呐样本插值得到"),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Icon(Icons.Default.Close,tr("Close","关闭"),Modifier.size(16.dp))};Text("${"%.2f".format(inspection.grid.depthMeters)} ${tr("m","米")} · ±${"%.2f".format(inspection.grid.uncertaintyMeters)} ${tr("m","米")} · ${inspection.grid.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall);survey?.let{Text("${it.name} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(java.util.Date(it.startedAt))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            }
            if(active?.candidateDecision==CandidateDecision.AVAILABLE.name)Box(Modifier.align(Alignment.BottomCenter).padding(bottom=112.dp,start=12.dp,end=12.dp)){EstimatedCenterBanner(state,vm,active)}
        }
    }
    if (showSetup) {
        AnchorSetupSheet(state,{showSetup=false;setupReference=null},reference=setupReference){lat,lon,input->vm.arm(lat,lon,input)}
    }
    if(showPreflight)WatchPreflightSheet(state,{showPreflight=false}){showPreflight=false;showSetup=true}
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Lift anchor","起锚")) } }, dismissButton = { TextButton({ confirmLift = false }) { Text(tr("Cancel","取消")) } }, title = { Text(tr("End this anchoring session?","结束本次锚泊？")) }, text = { Text(tr("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.","起锚会永久结束本次锚泊。轨迹仍保留在历史记录中，但不能再恢复。")) })
    anchorageDetails?.let{saved->AnchorageDetailDialog(saved,{anchorageDetails=null},{vm.openAnchorageInGoogleMaps(saved)},{vm.shareAnchorageQr(saved)},{anchorageDetails=null;vm.approachSavedAnchorage(saved.id)},approachEnabled=state.active==null)}
    anchorageListDetails?.let{ids->AnchorageListDialog(
        members=ids.mapNotNull{id->state.savedAnchorages.firstOrNull{it.id==id}},
        dismiss={anchorageListDetails=null},
        actions=nearbyActions,
    )}
    AnchorCentreRecalculationDialog(state.centreRecalculation,vm)
    if(showLayers)MapLayersSheet(state,mapChartUiState,{showLayers=false},{mapType->if(mapType==BaseMapStyle.NAUTICAL.persistedValue&&!state.settings.nauticalDisclaimerAccepted)showNauticalDisclaimer=true else vm.setMapType(mapType)},{enabled->vm.setOfflineMapEnabled(enabled)},{enabled->if(enabled&&!state.settings.linzHydroDisclaimerAccepted)showLinzDisclaimer=true else vm.updateSettings(state.settings.copy(linzHydroEnabled=enabled))},{opacity->vm.updateSettings(state.settings.copy(linzHydroOpacity=opacity))},{enabled->if(enabled&&!state.settings.sonarDisclaimerAccepted)showSonarDisclaimer=true else vm.setSonarLayerEnabled(enabled)},{enabled->vm.updateSettings(state.settings.copy(showLinzDepthReference=enabled))},{enabled->vm.updateSettings(state.settings.copy(showPersonalMapReference=enabled))},{showLayers=false;vm.page(3)})
    if(showNauticalDisclaimer)AlertDialog(onDismissRequest={showNauticalDisclaimer=false},title={Text(tr("Nautical map is a visual aid","航海底图仅供辅助"))},text={Text(tr("OpenSeaMap seamarks and the quiet base style can be incomplete, delayed or unavailable. They do not replace official charts, Notices to Mariners, depth instruments or a passage plan. Anchor alarms continue independently if map tiles fail.","OpenSeaMap 航标和清淡底图可能不完整、延迟或不可用，不能替代官方海图、航海通告、测深仪或航行计划。即使地图瓦片失败，锚警仍会独立运行。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(mapType=BaseMapStyle.NAUTICAL.persistedValue,nauticalDisclaimerAccepted=true));showNauticalDisclaimer=false}){Text(tr("I understand · Use Nautical","我已了解 · 使用航海图"))}},dismissButton={TextButton({showNauticalDisclaimer=false}){Text(tr("Cancel","取消"))}})
    if(showLinzDisclaimer)AlertDialog(onDismissRequest={showLinzDisclaimer=false},title={Text(tr("LINZ hydrographic chart overlay","LINZ 水文海图叠加层"))},text={Text(tr("This chart image layer is a navigation aid only. It may be unavailable or outdated and does not replace official charts, Notices to Mariners, depth instruments or a proper passage plan.","该海图影像层仅供辅助参考，可能不可用或已过期，不能替代官方海图、航海通告、测深仪或正规的航行计划。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(linzHydroEnabled=true,linzHydroDisclaimerAccepted=true));showLinzDisclaimer=false}){Text(tr("I understand · Enable","我已了解 · 开启"))}},dismissButton={TextButton({showLinzDisclaimer=false}){Text(tr("Cancel","取消"))}})
    if(showSonarDisclaimer)SonarSafetyDisclaimerDialog({showSonarDisclaimer=false}){vm.setSonarLayerEnabled(true,acceptDisclaimer=true);showSonarDisclaimer=false}
}

@Composable
private fun MapScaleIndicator(latitude:Double,zoom:Float,modifier:Modifier=Modifier){
    val density=LocalDensity.current
    val targetPixels=with(density){104.dp.toPx()}
    val scale=remember(latitude,zoom,targetPixels){MapDistanceTools.scaleBar(latitude,zoom,targetPixels)}
    val width=with(density){scale.widthPixels.toDp()}
    Surface(modifier.testTag("map_distance_scale"),color=MaterialTheme.colorScheme.surface.copy(alpha=.90f),shape=MaterialTheme.shapes.small){
        Column(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text(scale.label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold)
            Row(Modifier.width(width).height(5.dp),verticalAlignment=Alignment.Bottom){
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
                Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.onSurface))
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun WatchPreflightSheet(state:MainUiState,dismiss:()->Unit,continueSetup:()->Unit){
 val report=state.watchSafety
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(if(report.ready)Icons.Default.VerifiedUser else if(report.canContinue)Icons.Default.WarningAmber else Icons.Default.GppBad,null,tint=if(report.ready)SafetyColors.Safe else if(report.canContinue)SafetyColors.Warning else SafetyColors.Alarm);Column{Text(tr("Watch preflight","布防前安全检查"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold);Text(when{report.ready->tr("Ready to watch","已准备好监控");report.canContinue->tr("Can continue with stated risks","可以继续，但存在明确风险");else->tr("Fix blockers before arming","解决阻断项后才能布防")},color=if(report.ready)SafetyColors.Safe else MaterialTheme.colorScheme.onSurfaceVariant)}}
  LazyColumn(Modifier.fillMaxWidth().heightIn(max=430.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(report.checks,key={it.id}){check->SafetyCheckRow(check)}}
  if(report.canContinue&&!report.ready)Text(tr("Warnings do not silently disable the watch. Continuing means you accept the listed operational risks for this session.","警告不会悄悄关闭锚警；继续表示你接受本次会话列出的运行风险。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button(continueSetup,Modifier.fillMaxWidth().testTag("preflight_continue"),enabled=report.canContinue){Text(if(report.ready)tr("Continue to anchor setup","继续设置锚点")else tr("Continue with warnings","带警告继续"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }}
}

@Composable private fun SafetyCheckRow(check:com.yokuli.anchorwatch.domain.safety.SafetyCheck){
 val color=when(check.status){com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK->SafetyColors.Safe;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING->SafetyColors.Warning;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.BLOCKER->SafetyColors.Alarm}
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(when(check.status){com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK->Icons.Default.CheckCircle;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING->Icons.Default.Warning;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.BLOCKER->Icons.Default.Cancel},null,Modifier.size(20.dp),tint=color);Column(Modifier.weight(1f)){Text(safetyTitle(check.id,check.title),fontWeight=FontWeight.Medium);Text(localizeSafetyText(check.detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);check.risk?.let{Text(localizeSafetyText(it),style=MaterialTheme.typography.bodySmall,color=color)}}}
}

@Composable internal fun safetyTitle(id:String,fallback:String)=when(id){"gps_fresh"->tr("GPS freshness","GPS 新鲜度");"gps_accuracy"->tr("GPS accuracy","GPS 精度");"nmea"->tr("NMEA source","NMEA 数据源");"notifications"->tr("Alarm notifications","报警通知");"full_screen_alarm"->tr("Full-screen alarm","全屏警报");"alarm_sound"->tr("Alarm sound","报警声音");"background"->tr("Background monitor","后台监控");"battery_optimization"->tr("Battery optimization","电池优化");"battery"->tr("Battery","电量");"network"->tr("Wi-Fi / network","Wi-Fi / 网络");"storage"->tr("Storage","存储空间");"sonar"->tr("Sonar","声呐");else->fallback}

@Composable internal fun localizeSafetyText(value:String):String{
 if(!LocalAppLanguage.current.usesChinese())return value
 return when{
  value=="No valid fix"->"没有有效定位";value.startsWith("Stale by ")->value.replace("Stale by ","已过期 ").replace(" s"," 秒")
  value=="An anchor alarm cannot measure vessel movement without a position."->"没有船位时，锚警无法测量船舶移动。";value=="Arming from an old position can put the boundary in the wrong place."->"使用旧船位布防可能会把警戒范围放错位置。"
  value.startsWith("Fresh · ")->value.replace("Fresh · ","新鲜 · ").replace(" s"," 秒");value=="Unavailable"->"不可用";value=="Position quality is unknown."->"定位质量未知。"
  value=="Accuracy is wider than a useful anchor boundary."->"定位误差已经大于实用的锚警范围。";value=="Use a wider alarm radius or wait for better accuracy."->"请扩大报警半径，或等待更好的定位精度。";value=="Satellite geometry is weak; allow more margin."->"卫星几何条件较弱，请预留更大余量。"
  value=="Not reported"->"未报告";value=="The source did not report accuracy; choose a conservative range."->"数据源没有报告精度，请使用更保守的范围。";value=="Not required for selected GPS"->"所选 GPS 不需要网络";value.startsWith("Not required for ")->"当前 GPS 数据源不需要 NMEA"
  value in setOf("Connected and delivering data","Connected with a fresh acceptable position")->if(value.startsWith("Connected with"))"已连接，且船位新鲜、质量合格" else "已连接并持续提供数据";value in setOf("CONNECTING","RECONNECTING")->if(value=="CONNECTING")"正在连接" else "正在重连";value in setOf("DISCONNECTED","CONNECTED_NO_DATA","STALE","ERROR")->when(value){"DISCONNECTED"->"未连接";"CONNECTED_NO_DATA"->"已连接但无数据";"STALE"->"数据过期";else->"错误"}
  value=="Position unavailable, stale or poor quality"->"船位不可用、已过期或质量不合格";value=="Wait for a fresh position from the current connection with acceptable fix quality and HDOP."->"请等待当前连接提供新鲜、定位质量与 HDOP 合格的船位。"
  value=="Wait for a stable live NMEA position before arming."->"请等待稳定的实时 NMEA 船位后再布防。";value=="The selected GPS source is unavailable."->"所选 GPS 数据源不可用。"
  value=="Allowed"->"已允许";value=="Permission denied"->"权限被拒绝";value=="Android may hide critical anchor alarm notifications."->"Android 可能会隐藏关键锚警通知。"
  value=="Needs action"->"需要处理";value=="Android may show only a lock-screen heads-up alert instead of opening the alarm screen."->"Android 可能只显示锁屏横幅，而不会自动打开全屏警报界面。"
  value=="Android alarm volume is muted"->"Android 警报音量为静音";value=="The alarm cannot be heard."->"警报将无法被听见。";value=="Not confirmed on this device"->"尚未在本设备确认";value=="Run the alarm test and confirm that you heard it."->"请运行警报测试并确认你能听见。";value=="Last confirmed over 30 days ago"->"上次确认已超过 30 天";value=="Retest after Android, audio or vessel setup changes."->"Android、音频或船上安装发生变化后请重新测试。";value=="Audible test confirmed"->"已确认能听见警报"
  value=="Foreground service available"->"前台服务可用";value=="Service unavailable"->"服务不可用";value=="The watch cannot continue reliably when the screen is off."->"屏幕关闭后锚警将无法可靠持续运行。"
  value=="Unrestricted"->"不受限制";value=="Android may restrict background work"->"Android 可能限制后台运行";value=="Allow unrestricted battery use for an overnight watch."->"过夜监控前请允许不受限制的电池使用。"
  value=="Confirm reliable external power before an overnight watch."->"过夜监控前请确认有可靠的外部电源。";value=="Connect reliable power before starting the watch."->"开始监控前请连接可靠电源。";value=="Keep the device on reliable power."->"请让设备持续连接可靠电源。"
  value=="No active network"->"没有活动网络";value=="The NMEA endpoint cannot be kept reachable."->"无法持续访问 NMEA 端点。";value=="Wi-Fi connected"->"Wi-Fi 已连接";value=="Connected without Wi-Fi"->"已联网但不是 Wi-Fi";value=="Confirm the NMEA server is reachable over this transport."->"请确认可通过当前网络访问 NMEA 服务器。"
  value=="Less than 10 MB free"->"可用空间不足 10 MB";value.endsWith(" MB free")->value.replace(" MB free"," MB 可用");value=="Safety events and track points may not be saved."->"安全事件与轨迹点可能无法保存。";value=="Free storage before a long watch or sonar survey."->"长时间监控或声呐调查前请释放存储空间。"
  value=="Not required for anchor watch"->"锚警不要求声呐";value=="Live depth and matching position"->"实时水深与配对船位正常";value=="Survey active but data is stale"->"调查进行中，但数据已过期";value=="Anchor watch can continue; sonar mapping will wait for same-stream data."->"锚警可以继续；声呐测绘会等待同一数据流恢复。"
  value=="Held real depth with live same-stream position"->"正在使用保留的真实水深，同源船位仍为实时";value=="Held depth is approaching its safety limit"->"保留水深正在接近安全上限";value=="A new real DPT/DBT is required before 5 minutes or 500 m of travel."->"必须在 5 分钟或移动 500 米前收到新的真实 DPT/DBT。"
  else->value
 }
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun MapLayersSheet(state:MainUiState,chartState:MapChartUiState,dismiss:()->Unit,setMapType:(Int)->Unit,setUserNautical:(Boolean)->Unit,setLinz:(Boolean)->Unit,setOpacity:(Double)->Unit,setSonar:(Boolean)->Unit,setLinzDepth:(Boolean)->Unit,setPersonalDepth:(Boolean)->Unit,manageChartFiles:()->Unit){
 val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
 val linzDiagnostics by LinzHydroDiagnostics.state.collectAsState()
 val nauticalDiagnostics by OpenSeaMapDiagnostics.state.collectAsState()
 val networkAvailable=isNetworkAvailable(androidx.compose.ui.platform.LocalContext.current)
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Text(tr("Map layers","地图图层"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
  Text(tr("Base map","底图"),style=MaterialTheme.typography.labelLarge)
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
   SegmentedButton(state.settings.mapType==1,{setMapType(1)},shape=SegmentedButtonDefaults.itemShape(0,3),modifier=Modifier.testTag("map_style_map")){Text(tr("Standard","标准"))}
   SegmentedButton(state.settings.mapType==2,{setMapType(2)},shape=SegmentedButtonDefaults.itemShape(1,3),modifier=Modifier.testTag("map_style_satellite")){Text(tr("Satellite","卫星"))}
   SegmentedButton(state.settings.mapType==3,{setMapType(3)},shape=SegmentedButtonDefaults.itemShape(2,3),modifier=Modifier.testTag("map_style_nautical")){Text(tr("Nautical","航海"))}
  }
  if(state.settings.mapType==3){
   Text(tr("Nautical source","航海图来源"),style=MaterialTheme.typography.labelLarge)
   SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
    SegmentedButton(!state.settings.offlineMapEnabled,{setUserNautical(false)},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Default online","默认在线航海图"))}
    SegmentedButton(state.settings.offlineMapEnabled,{setUserNautical(true)},enabled=state.offlineMap.installed,shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Imported MBTiles","已导入 MBTiles"))}
   }
   Text(if(state.offlineMap.installed&&state.settings.offlineMapEnabled)tr("Using ${state.offlineMap.name?:"imported MBTiles"}. Missing tiles fall through to the default online nautical map.","正在使用 ${state.offlineMap.name?:"已导入 MBTiles"}；缺少的瓦片会自动显示默认在线航海图。") else tr("OpenSeaMap seamarks on a quiet base map. This is not an official chart and does not replace passage planning.","OpenSeaMap 航标叠加于简洁底图；它不是官方海图，也不能替代正规航海计划。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(!state.offlineMap.installed)OutlinedButton(manageChartFiles,Modifier.fillMaxWidth()){Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(6.dp));Text(tr("Manage chart files","管理海图文件"))}
   TextButton({uriHandler.openUri(OpenSeaMapConfiguration.LICENSE_URL)}){Icon(Icons.AutoMirrored.Filled.OpenInNew,null);Spacer(Modifier.width(6.dp));Text(tr("OpenSeaMap attribution & licence","OpenSeaMap 署名与许可"))}
   if(nauticalDiagnostics.failures>0&&nauticalDiagnostics.successes==0L)TileFailureCard(tr("Nautical tile status","航海瓦片状态"),nauticalDiagnostics.requests,nauticalDiagnostics.successes,nauticalDiagnostics.failures,nauticalDiagnostics.lastHttpCode,nauticalDiagnostics.message)
  }
  HorizontalDivider()
  Text(tr("Overlays","叠加层"),style=MaterialTheme.typography.labelLarge)
  Column(Modifier.testTag("local_depth_section"),verticalArrangement=Arrangement.spacedBy(10.dp)){
   val availabilitySummary=when(val available=chartState.availability){
    is LocalDepthAvailability.Available->if(networkAvailable)tr("Available here · ${available.provider.displayName}","当前位置可用 · ${available.provider.displayName}")else tr("Offline · cached tiles may remain available","离线 · 已缓存瓦片可能仍可用")
    is LocalDepthAvailability.ProviderNotConfigured->tr("Supported here, but unavailable in this build","当前位置受支持，但当前构建未配置")
    LocalDepthAvailability.UnsupportedArea->tr("Not available in this area","当前区域不可用")
    LocalDepthAvailability.PositionUnknown->tr("Waiting for a boat position or map location","正在等待船位或地图位置")
   }
   Box(Modifier.testTag("local_depth_toggle")){SettingSwitch(tr("LINZ NZ chart enhancement","LINZ 新西兰海图增强"),availabilitySummary,chartState.localDepthPreferenceEnabled,chartState.availability is LocalDepthAvailability.Available,setLinz)}
   val provider=when(val available=chartState.availability){is LocalDepthAvailability.Available->available.provider;is LocalDepthAvailability.ProviderNotConfigured->available.provider;else->null}
   Text(provider?.displayName?:tr("No regional provider for this location","当前位置没有区域数据提供方"),Modifier.testTag("local_depth_provider"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(chartState.localDepthPreferenceEnabled&&chartState.availability is LocalDepthAvailability.Available){
    Text(tr("Opacity ${"%.0f".format(chartState.localDepthOpacity*100)}%","不透明度 ${"%.0f".format(chartState.localDepthOpacity*100)}%"),style=MaterialTheme.typography.labelLarge)
    Slider(chartState.localDepthOpacity.toFloat(),{setOpacity(it.toDouble())},Modifier.testTag("local_depth_opacity"),valueRange=.30f..1f)
    TextButton({uriHandler.openUri(chartState.availability.provider.licenseUrl)}){Icon(Icons.AutoMirrored.Filled.OpenInNew,null);Spacer(Modifier.width(6.dp));Text(tr("LINZ attribution & licence","LINZ 署名与许可"))}
    if(linzDiagnostics.failures>0&&linzDiagnostics.successes==0L)TileFailureCard(tr("Local depth tile status","区域水深瓦片状态"),linzDiagnostics.requests,linzDiagnostics.successes,linzDiagnostics.failures,linzDiagnostics.lastHttpCode,linzDiagnostics.message)
   }
  }
  SettingSwitch(tr("Personal sonar layer","个人声呐图层"),if(state.sonarGrid.cells.isEmpty())tr("No personal sonar data yet","暂无个人声呐数据")else tr("${state.sonarSurveys.size} surveys · ${state.sonarGrid.cells.size} grid cells · fixed 75% opacity","${state.sonarSurveys.size} 个调查 · ${state.sonarGrid.cells.size} 个网格 · 固定 75% 不透明度"),state.settings.sonarLayerEnabled,state.sonarGrid.cells.isNotEmpty(),setSonar)
  HorizontalDivider()
  Text(tr("Depth readouts","水深读数"),style=MaterialTheme.typography.labelLarge)
  SettingSwitch(tr("Current-position LINZ depth","当前位置 LINZ 水深"),tr("Vector chart reference; never presented as live sonar","矢量海图参考；绝不会冒充实时声呐"),state.settings.showLinzDepthReference,BuildConfig.LINZ_API_KEY.isNotBlank(),setLinzDepth)
  SettingSwitch(tr("Current-position personal measured depth","当前位置个人实测水深"),if(state.sonarGrid.cells.isEmpty())tr("No personal sonar grid is available","暂无个人声呐网格")else tr("Shows measured or interpolated status at the boat","显示船位处的实测或插值状态"),state.settings.showPersonalMapReference,state.sonarGrid.cells.isNotEmpty(),setPersonalDepth)
  Text(tr("Map and chart tiles are visual aids only. Their network, cache and display state never changes the anchor alarm, GPS acceptance or background watch.","地图与海图瓦片仅供视觉辅助；其网络、缓存和显示状态绝不会改变锚警、GPS 接受逻辑或后台监控。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }}
}

@Composable private fun TileFailureCard(title:String,requests:Long,successes:Long,failures:Long,httpCode:Int?,message:String){
 Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(tr("$successes/$requests tiles loaded · $failures failed · HTTP ${httpCode?:"—"}","已加载 $successes/$requests 个瓦片 · $failures 个失败 · HTTP ${httpCode?:"—"}"),style=MaterialTheme.typography.bodySmall);Text(localizeKnownMessage(message),style=MaterialTheme.typography.bodySmall)}}
}

private fun isNetworkAvailable(context:Context):Boolean{
 val manager=context.getSystemService(android.net.ConnectivityManager::class.java)?:return false
 val network=manager.activeNetwork?:return false
 val capabilities=manager.getNetworkCapabilities(network)?:return false
 return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
