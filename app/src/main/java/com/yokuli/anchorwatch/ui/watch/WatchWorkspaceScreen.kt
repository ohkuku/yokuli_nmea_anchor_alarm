package com.yokuli.anchorwatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.CandidateValidity
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.InstrumentLayoutPolicy
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.MetricLabelRegistry
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import com.yokuli.anchorwatch.data.vessel.layout
import com.yokuli.anchorwatch.data.vessel.withLayout
import com.yokuli.anchorwatch.data.nmea.NmeaFieldObservation
import com.yokuli.anchorwatch.data.trip.DashboardTileBinding
import com.yokuli.anchorwatch.data.trip.InstrumentTileSize
import com.yokuli.anchorwatch.data.trip.InstrumentSourceOverride
import com.yokuli.anchorwatch.data.trip.TripDashboard
import com.yokuli.anchorwatch.location.vessel.DeviceBowAxis
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.yokuli.anchorwatch.map.MarineMapContext
import com.yokuli.anchorwatch.map.MarineMapPolicy

private data class TripWorkspacePage(val preset:TripInstrumentPreset?,val dashboard:TripDashboard?=null)

@Composable
internal fun TripWatchPage(state:MainUiState,vm:MainViewModel){
    var preset by rememberSaveable{mutableStateOf(TripInstrumentPreset.SAILING)}
    var customizeLayout by remember{mutableStateOf(false)}
    var startDialog by remember{mutableStateOf(false)}
    var waypointDialog by remember{mutableStateOf(false)}
    var endConfirm by remember{mutableStateOf(false)}
    val localCockpitMode=LocalSailCockpitMode.current
    val fallbackCockpitMode=rememberSaveable{mutableStateOf(false)}
    var cockpitMode by (localCockpitMode?:fallbackCockpitMode)
    var nightMode by rememberSaveable{mutableStateOf(false)}
    var touchLocked by rememberSaveable{mutableStateOf(false)}
    var selectedCustomDashboardId by rememberSaveable{mutableStateOf<String?>(null)}
    var manageDashboards by remember{mutableStateOf(false)}
    var moreActions by remember{mutableStateOf(false)}
    var pagePicker by remember{mutableStateOf(false)}
    var attitudeFrameDialog by remember{mutableStateOf(false)}
    val trip=state.activeTrip
    val healthNow by produceState(android.os.SystemClock.elapsedRealtime()){while(true){kotlinx.coroutines.delay(1_000L);value=android.os.SystemClock.elapsedRealtime()}}
    val nmeaTrafficLive=state.connection!=com.yokuli.anchorwatch.domain.model.NmeaConnectionState.DISCONNECTED&&state.diagnostics.lastPacketElapsed?.let{healthNow-it in 0L..5_000L}==true
    val imuLive=state.vesselData.attitude.freshness==VesselDataFreshness.FRESH
    val selectedDashboard=state.tripDashboards.firstOrNull{it.id==selectedCustomDashboardId}
    val builtIn=state.tripDashboards.filter{it.preset!=TripInstrumentPreset.CUSTOM}.associateBy{it.preset}
    val instrumentPages=listOf(TripWorkspacePage(null))+listOf(TripInstrumentPreset.SAILING,TripInstrumentPreset.NAV,TripInstrumentPreset.MOTION,TripInstrumentPreset.WEATHER).map{TripWorkspacePage(it,builtIn[it])}+state.tripDashboards.filter{it.preset==TripInstrumentPreset.CUSTOM}.map{TripWorkspacePage(TripInstrumentPreset.CUSTOM,it)}
    val instrumentPager=rememberPagerState(initialPage=0,pageCount={instrumentPages.size});val instrumentScope=rememberCoroutineScope()
    LaunchedEffect(instrumentPager.currentPage,instrumentPages){instrumentPages.getOrNull(instrumentPager.currentPage)?.let{page->page.preset?.let{preset=it};selectedCustomDashboardId=page.dashboard?.id}}
    LaunchedEffect(selectedCustomDashboardId,state.tripDashboards){selectedCustomDashboardId?.let{id->val index=instrumentPages.indexOfFirst{it.dashboard?.id==id};if(index>=0&&index!=instrumentPager.currentPage)instrumentPager.scrollToPage(index)}}
    LaunchedEffect(selectedCustomDashboardId,state.tripDashboards){if(selectedCustomDashboardId!=null&&selectedDashboard==null){selectedCustomDashboardId=null;preset=TripInstrumentPreset.SAILING}}
    DisposableEffect(Unit){vm.setTripLiveDisplayActive(true);onDispose{vm.setTripLiveDisplayActive(false)}}
    val inheritedTypography=MaterialTheme.typography;val inheritedShapes=MaterialTheme.shapes;val dayColors=MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme=if(nightMode)darkColorScheme(primary=Color(0xFFFF6B35),secondary=Color(0xFFFFB000),tertiary=Color(0xFFFFD166),background=Color(0xFF100704),surface=Color(0xFF1D0C07),surfaceVariant=Color(0xFF32150C),onPrimary=Color.Black,onBackground=Color(0xFFFFE4D6),onSurface=Color(0xFFFFE4D6))else dayColors,
        typography=inheritedTypography,
        shapes=inheritedShapes,
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal=10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min=48.dp),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("SAIL","帆航"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    val attitudeExpected=trip?.phoneMotionEnabled==true
                    val issues=buildList{if(!nmeaTrafficLive)add(tr("NMEA stale","NMEA 已静默"));if(state.vesselData.position.freshness!=VesselDataFreshness.FRESH)add(tr("GPS unavailable","GPS 不可用"));if(attitudeExpected&&!imuLive)add(tr("Attitude paused","姿态已暂停"))}
                    if(issues.isEmpty())Text(tr("● LIVE","● 实时"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    else Text(issues.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                }
                if(!cockpitMode)Box {
                    IconButton({moreActions=true},enabled=!touchLocked){Icon(Icons.Default.MoreVert,tr("Sail actions","航行操作"))}
                    DropdownMenu(moreActions,{moreActions=false}){
                        DropdownMenuItem({Text(tr("Save / share snapshot","保存或分享快照"))},{moreActions=false;vm.shareTripLiveSnapshot()})
                        DropdownMenuItem({Text(tr("Customize dashboard","自定义仪表页"))},{moreActions=false;customizeLayout=true})
                        DropdownMenuItem({Text(tr("Manage custom pages","管理自定义页面"))},{moreActions=false;manageDashboards=true})
                        DropdownMenuItem({Text(tr("Cockpit mode","驾驶舱模式"))},{moreActions=false;cockpitMode=true})
                    }
                }
                IconButton({cockpitMode=!cockpitMode},enabled=!touchLocked,modifier=Modifier.testTag("trip_cockpit_mode")){Icon(Icons.Default.Fullscreen,if(cockpitMode)tr("Exit cockpit","退出驾驶舱")else tr("Cockpit view","驾驶舱视图"))}
                if(!cockpitMode)IconButton({nightMode=!nightMode},enabled=!touchLocked,modifier=Modifier.testTag("trip_night_mode")){Icon(Icons.Default.DarkMode,tr("Night palette","夜间配色"))}
                if(touchLocked)Box(Modifier.size(48.dp).testTag("trip_touch_lock").pointerInput(Unit){awaitEachGesture{awaitFirstDown();if(withTimeoutOrNull(1_500L){waitForUpOrCancellation()}==null){touchLocked=false;waitForUpOrCancellation()}}},contentAlignment=Alignment.Center){Icon(Icons.Default.Lock,tr("Hold 1.5 seconds to unlock","长按 1.5 秒解锁"))}
                else IconButton({touchLocked=true},modifier=Modifier.testTag("trip_touch_lock")){Icon(Icons.Default.LockOpen,tr("Lock controls","锁定操作"))}
            }
            if(state.active!=null&&!cockpitMode)Text(tr("Anchor Watch is open · lift anchor before starting a trip","锚泊监控未结束 · 起锚后才能开始航程"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            if(cockpitMode){
                Row(Modifier.fillMaxWidth().height(32.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){instrumentPages.forEachIndexed{index,_->Text(if(index==instrumentPager.currentPage)"●" else "○",Modifier.padding(horizontal=4.dp),color=if(index==instrumentPager.currentPage)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
            }else{
                Row(Modifier.fillMaxWidth().height(44.dp),verticalAlignment=Alignment.CenterVertically){Box{TextButton({pagePicker=true},Modifier.testTag("trip_page_picker"),enabled=!touchLocked){Text(instrumentPages.getOrNull(instrumentPager.currentPage)?.let{page->page.dashboard?.title?:page.preset?.let{presetName(it)}?:tr("Overview","总览") }?:"—",fontWeight=FontWeight.SemiBold)};DropdownMenu(pagePicker,{pagePicker=false}){instrumentPages.forEachIndexed{index,page->DropdownMenuItem({Text(page.dashboard?.title?:page.preset?.let{presetName(it)}?:tr("Overview","总览"))},{pagePicker=false;instrumentScope.launch{instrumentPager.scrollToPage(index)}},modifier=Modifier.testTag("trip_page_picker_${page.preset?.name?:"OVERVIEW"}"))}}};Spacer(Modifier.weight(1f));Text("${instrumentPager.currentPage+1} / ${instrumentPages.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));instrumentPages.forEachIndexed{index,_->Text(if(index==instrumentPager.currentPage)"●" else "○",Modifier.padding(horizontal=2.dp),color=if(index==instrumentPager.currentPage)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
            }
            val visiblePage=instrumentPages.getOrNull(instrumentPager.currentPage)
            if(!cockpitMode&&trip!=null&&(visiblePage?.preset==TripInstrumentPreset.MOTION||trip.phoneMotionEnabled&&!imuLive)){
                TripAttitudeStatusBar(state,openFrame={attitudeFrameDialog=true},pause=vm::pauseTripAttitude)
            }
            HorizontalPager(instrumentPager,Modifier.fillMaxWidth().weight(1f),userScrollEnabled=!touchLocked){index->val page=instrumentPages[index];if(page.preset==null)TripOverviewPage(state,vm)else TripInstrumentViewport(state,page.preset,page.dashboard){if(!touchLocked)customizeLayout=true}}
            TripCompactControls(
                state=state,
                cockpit=cockpitMode,
                locked=touchLocked,
                start={startDialog=true},
                pauseOrResume={if(trip?.paused==true)vm.resumeTrip()else vm.pauseTrip()},
                mark={waypointDialog=true},
                end={endConfirm=true},
            )
        }
    }
    if(startDialog)TripStartDialog(state,{startDialog=false;vm.setPhoneVesselMounted(false)},{axis->vm.confirmTripAttitudeFrame(axis)}){name,phoneMotion,positionPreference->startDialog=false;if(!phoneMotion)vm.setPhoneVesselMounted(false);vm.startTrip(name,phoneMotion,positionPreference)}
    if(attitudeFrameDialog)TripAttitudeFrameDialog(state,{attitudeFrameDialog=false}){axis->vm.confirmTripAttitudeFrame(axis)}
    if(customizeLayout){
        val dashboard=instrumentPages.getOrNull(instrumentPager.currentPage)?.dashboard
        val currentTiles=dashboard?.tiles?.filter{it.tileId!=null}?:state.vesselSettings.layout(preset).map{DashboardTileBinding(tileId=it)}
        val currentFields=dashboard?.tiles?.filter{!it.nmeaFieldId.isNullOrBlank()}?:state.vesselSettings.customNmeaFieldIds.map{DashboardTileBinding(nmeaFieldId=it,recordInTrips=true)}
        TripLayoutDialog(preset,currentTiles,state.nmeaFields,currentFields,{customizeLayout=false}){value,fields->
            if(dashboard==null&&preset!=TripInstrumentPreset.CUSTOM)vm.saveTripDashboard(TripDashboard("builtin-${preset.name.lowercase()}",preset,presetNamePlain(preset),value))
            else if(dashboard==null)vm.updateVesselDataSettings(state.vesselSettings.withLayout(preset,value.mapNotNull{it.tileId}).copy(customNmeaFieldIds=fields.mapNotNull{it.nmeaFieldId}))
            else vm.saveTripDashboard(dashboard.copy(tiles=value+fields))
            customizeLayout=false
        }
    }
    if(manageDashboards)TripDashboardManagerDialog(state.tripDashboards.filter{it.preset==TripInstrumentPreset.CUSTOM},{manageDashboards=false},{vm.createTripDashboard(it)},{vm.saveTripDashboard(it)},{vm.deleteTripDashboard(it)},{vm.reorderTripDashboards(it)}){dashboard->selectedCustomDashboardId=dashboard.id;preset=TripInstrumentPreset.CUSTOM;manageDashboards=false}
    if(waypointDialog)TripWaypointDialog({waypointDialog=false}){name,note,type->waypointDialog=false;vm.markTripWaypoint(name,note,type)}
    if(endConfirm)AlertDialog(onDismissRequest={endConfirm=false},title={Text(tr("End this trip?","结束这次航程？"))},text={Text(tr("Buffered samples will be saved and the completed trip will remain in History.","缓存中的样本会先保存，完成的航程会保留在历史中。"))},confirmButton={Button({endConfirm=false;vm.endTrip()}){Text(tr("End & save","结束并保存"))}},dismissButton={TextButton({endConfirm=false}){Text(tr("Cancel","取消"))}})
}

@Composable
private fun TripCompactControls(
    state:MainUiState,
    cockpit:Boolean,
    locked:Boolean,
    start:()->Unit,
    pauseOrResume:()->Unit,
    mark:()->Unit,
    end:()->Unit,
){
    val trip=state.activeTrip
    val now by produceState(System.currentTimeMillis(),trip?.id,trip?.paused){
        while(true){value=System.currentTimeMillis();kotlinx.coroutines.delay(1_000L)}
    }
    Surface(Modifier.fillMaxWidth().testTag("trip_control_card"),tonalElevation=2.dp){
        if(trip==null){
            Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.Center){
                if(cockpit||locked)Text(if(locked)tr("LIVE · controls locked","实时 · 操作已锁定")else tr("LIVE · not recording","实时 · 未记录"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else Button(start,Modifier.testTag("start_trip"),enabled=state.active==null&&!state.settings.demoMode){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(tr("Start recording","开始记录"))}
            }
        }else{
            val openPause=(trip.pausedAt?.let{if(trip.paused)(now-it).coerceAtLeast(0L)else 0L}?:0L)
            val elapsed=(now-trip.startedAt-trip.accumulatedPausedMillis-openPause).coerceAtLeast(0L)
            val hours=elapsed/3_600_000L;val minutes=(elapsed/60_000L)%60;val seconds=(elapsed/1_000L)%60
            Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                Column(Modifier.weight(1f)){
                    Text(if(trip.paused)tr("○ PAUSED","○ 已暂停") else tr("● REC","● 记录中"),style=MaterialTheme.typography.labelLarge,color=if(trip.paused)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
                    Text("%02d:%02d:%02d · %.1f NM".format(hours,minutes,seconds,trip.distanceMeters/1_852.0),style=MaterialTheme.typography.labelSmall)
                }
                if(!locked){
                    if(!cockpit)FilledTonalIconButton(pauseOrResume,Modifier.testTag(if(trip.paused)"resume_trip" else "pause_trip")){Icon(if(trip.paused)Icons.Default.PlayArrow else Icons.Default.Pause,if(trip.paused)tr("Resume","继续")else tr("Pause","暂停"))}
                    FilledTonalIconButton(mark,Modifier.testTag("mark_waypoint"),enabled=!trip.paused&&state.vesselData.position.value!=null&&state.vesselData.position.freshness==VesselDataFreshness.FRESH){Icon(Icons.Default.Flag,tr("Mark","标记"))}
                    if(!cockpit)FilledTonalIconButton(end,Modifier.testTag("end_trip")){Icon(Icons.Default.Stop,tr("End","结束"))}
                }
            }
        }
    }
}

@Composable
private fun TripInstrumentViewport(state:MainUiState,preset:TripInstrumentPreset,dashboard:TripDashboard?,onEdit:()->Unit){
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical=8.dp).testTag("mfd_page_${preset.name}")){
        val landscape=maxWidth>maxHeight
        if(preset==TripInstrumentPreset.SAILING&&dashboard?.preset!=TripInstrumentPreset.CUSTOM){
            val core=listOf(InstrumentTileId.BOAT_SPEED,InstrumentTileId.SOG,InstrumentTileId.HEEL,InstrumentTileId.VMG,InstrumentTileId.APPARENT_WIND_SPEED,InstrumentTileId.TRUE_WIND_SPEED)
            if(landscape)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Box(Modifier.weight(.60f)){SailingCompass(state)};Box(Modifier.weight(.40f)){TripInstrumentGrid(state,preset,dashboard,core.takeIf{dashboard==null},onEdit,6)}}
            // Keep all six primary sailing instruments fully visible on a
            // portrait phone. The compass remains the visual lead, but it may
            // not consume the space required by the three instrument rows.
            else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(.32f)){SailingCompass(state,compact=true)};Box(Modifier.weight(.68f)){TripInstrumentGrid(state,preset,dashboard,core.takeIf{dashboard==null},onEdit,6)}}
        }else if(preset!=TripInstrumentPreset.CUSTOM){
            val core=when(preset){
                TripInstrumentPreset.NAV->listOf(InstrumentTileId.SOG,InstrumentTileId.COG,InstrumentTileId.HEADING,InstrumentTileId.BOAT_SPEED,InstrumentTileId.DEPTH,InstrumentTileId.UKC)
                TripInstrumentPreset.MOTION->listOf(InstrumentTileId.HEEL,InstrumentTileId.PITCH,InstrumentTileId.ROLL_RATE,InstrumentTileId.PITCH_RATE,InstrumentTileId.ROLL_PERIOD,InstrumentTileId.MOTION_SCORE)
                TripInstrumentPreset.WEATHER->listOf(InstrumentTileId.PRESSURE,InstrumentTileId.PRESSURE_TREND_1H,InstrumentTileId.PRESSURE_TREND_3H,InstrumentTileId.PRESSURE_TREND_6H,InstrumentTileId.AIR_TEMPERATURE,InstrumentTileId.WATER_TEMPERATURE)
                else->emptyList()
            }
            if(preset==TripInstrumentPreset.NAV)Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(.40f)){TripPositionMap(state)};Box(Modifier.weight(.60f)){TripInstrumentGrid(state,preset,dashboard,core.takeIf{dashboard==null},onEdit,6)}}
            else Box(Modifier.fillMaxSize()){TripInstrumentGrid(state,preset,dashboard,core.takeIf{dashboard==null},onEdit,6)}
        }else Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            TripInstrumentGrid(state,preset,dashboard,null,onEdit)
        }
    }
}

@Composable
private fun TripInstrumentGrid(state:MainUiState,preset:TripInstrumentPreset,dashboard:TripDashboard?=null,forcedIds:List<InstrumentTileId>?=null,onEdit:()->Unit={},maxTiles:Int?=null){
    var expanded by remember(preset,dashboard?.id){mutableStateOf<TripTileState?>(null)}
    val data=state.vesselData
    val allTiles=forcedIds?.map{TripGridItem(tripTile(it,state),InstrumentTileSize.SMALL)}?:dashboard?.tiles?.mapNotNull{binding->
        (binding.tileId?.let{tripTile(it,state,binding.sourceOverride?:InstrumentSourceOverride.VESSEL_DEFAULT)}?:binding.nmeaFieldId?.let{id->state.nmeaFields.firstOrNull{it.key.stableId==id}?.let{field->customNmeaTile(field,binding)}})?.let{TripGridItem(it,binding.size)}
    }?:run{
        val standard=state.vesselSettings.layout(preset).map{TripGridItem(tripTile(it,state),InstrumentTileSize.MEDIUM)}
        val discovered=if(preset==TripInstrumentPreset.CUSTOM)state.vesselSettings.customNmeaFieldIds.mapNotNull{id->state.nmeaFields.firstOrNull{it.key.stableId==id}?.let{field->TripGridItem(customNmeaTile(field),InstrumentTileSize.MEDIUM)}}else emptyList()
        standard+discovered
    }
    val tiles=maxTiles?.let(allTiles::take)?:allTiles
    val rows=buildList<List<TripGridItem>>{var pending=mutableListOf<TripGridItem>();fun flush(){if(pending.isNotEmpty()){add(pending.toList());pending=mutableListOf()}};tiles.forEach{item->if(item.size in setOf(InstrumentTileSize.WIDE,InstrumentTileSize.LARGE,InstrumentTileSize.HERO)){flush();add(listOf(item))}else{pending+=item;if(pending.size==2)flush()}};flush()}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(tiles.isEmpty())Text(tr("No instruments are visible in this preset. Use Customize to add one.","此预设没有显示仪表，请点“自定义”添加。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{item->MarineInstrumentTile(title=item.tile.title,value=item.tile.value,sourceState="${sourceName(item.tile.source)} · ${freshnessName(item.tile.freshness)}",fresh=item.tile.freshness==VesselDataFreshness.FRESH,receivedElapsedRealtime=item.tile.receivedElapsedRealtime,size=item.size,modifier=Modifier.weight(1f),onClick={expanded=item.tile},onLongClick=onEdit)};if(row.size==1&&row.single().size in setOf(InstrumentTileSize.SMALL,InstrumentTileSize.MEDIUM))Spacer(Modifier.weight(1f))}}
    }
    expanded?.let{tile->Dialog(onDismissRequest={expanded=null},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){Surface(Modifier.fillMaxSize().testTag("mfd_fullscreen_instrument"),color=MaterialTheme.colorScheme.background){Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)){IconButton({expanded=null},Modifier.align(Alignment.TopEnd)){Icon(Icons.Default.Close,tr("Close","关闭"))};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(tile.title,style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(24.dp));Text(tile.value,style=MaterialTheme.typography.displayLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.height(24.dp));Text("${sourceName(tile.source)} · ${freshnessName(tile.freshness)}",style=MaterialTheme.typography.titleLarge,color=if(tile.freshness==VesselDataFreshness.FRESH)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary);tile.sourceDetail?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}}
}

private data class TripGridItem(val tile:TripTileState,val size:InstrumentTileSize)

@Composable
private fun TripDashboardManagerDialog(
    dashboards:List<TripDashboard>,
    dismiss:()->Unit,
    create:(String)->Unit,
    save:(TripDashboard)->Unit,
    delete:(String)->Unit,
    reorder:(List<String>)->Unit,
    open:(TripDashboard)->Unit,
){
    var newName by remember{mutableStateOf("")}
    var order by remember(dashboards.map{it.id}){mutableStateOf(dashboards.map{it.id})}
    val titles=remember{mutableStateMapOf<String,String>()}
    LaunchedEffect(dashboards){dashboards.forEach{titles.putIfAbsent(it.id,it.title)};titles.keys.retainAll(dashboards.mapTo(mutableSetOf()){it.id})}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Dashboard pages","仪表页面"))},
        text={Column(Modifier.heightIn(max=520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(tr("Built-in pages remain available. Create separate custom pages for a race, passage, engine check or any live NMEA fields you choose.","内置页面会一直保留。可为比赛、远航、机舱检查或任意实时 NMEA 字段建立独立自定义页面。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(newName,{newName=it.take(120)},label={Text(tr("New page name","新页面名称"))},singleLine=true,modifier=Modifier.weight(1f).testTag("new_trip_dashboard_name"))
                Button({create(newName);newName=""},enabled=newName.isNotBlank()){Text(tr("Add","添加"))}
            }
            if(dashboards.isEmpty())Text(tr("No custom pages yet.","还没有自定义页面。"),style=MaterialTheme.typography.bodySmall)
            order.mapNotNull{id->dashboards.firstOrNull{it.id==id}}.forEachIndexed{index,dashboard->
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        OutlinedTextField(titles[dashboard.id]?:dashboard.title,{titles[dashboard.id]=it.take(120)},label={Text(tr("Page name","页面名称"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            TextButton({open(dashboard)}){Text(tr("Open","打开"))}
                            Spacer(Modifier.weight(1f))
                            TextButton({if(index>0){order=order.toMutableList().also{list->val moving=list.removeAt(index);list.add(index-1,moving)}}},enabled=index>0){Text("↑")}
                            TextButton({if(index<order.lastIndex){order=order.toMutableList().also{list->val moving=list.removeAt(index);list.add(index+1,moving)}}},enabled=index<order.lastIndex){Text("↓")}
                            IconButton({delete(dashboard.id);order=order.filterNot{it==dashboard.id}}){Icon(Icons.Default.DeleteForever,tr("Delete dashboard page","删除仪表页面"),tint=MaterialTheme.colorScheme.error)}
                        }
                    }
                }
            }
        }},
        confirmButton={Button({dashboards.forEach{dashboard->val title=titles[dashboard.id]?.trim().orEmpty();if(title.isNotBlank()&&title!=dashboard.title)save(dashboard.copy(title=title))};reorder(order);dismiss()}){Text(tr("Save order","保存顺序"))}},
        dismissButton={TextButton(dismiss){Text(tr("Close","关闭"))}},
    )
}

private fun <T,R> VesselObservation<T>.mapValue(transform:(T)->R?):VesselObservation<R> = VesselObservation(value=value?.let(transform),source=source,observedAtUtcMillis=observedAtUtcMillis,receivedElapsedRealtime=receivedElapsedRealtime,quality=quality,freshness=freshness,provenance=provenance,sourceIdentity=sourceIdentity,sourceClass=sourceClass,reference=reference,provenanceDetail=provenanceDetail,conflict=conflict,sourceHeartbeatElapsedRealtime=sourceHeartbeatElapsedRealtime,selectionReason=selectionReason)

private data class TripTileState(val title:String,val value:String,val source:VesselDataSource,val freshness:VesselDataFreshness,val receivedElapsedRealtime:Long?=null,val sourceDetail:String?=null)

@Composable private fun tripTile(id:InstrumentTileId,state:MainUiState,override:InstrumentSourceOverride=InstrumentSourceOverride.VESSEL_DEFAULT):TripTileState{
    val data=state.vesselData
    fun numeric(title:String,value:VesselObservation<Double>,format:(Double)->String)=TripTileState(title,value.value?.let(format)?:"—",value.source,value.freshness,value.receivedElapsedRealtime,value.provenance?.takeIf{it.isNotBlank()}?:value.sourceIdentity?.displayName)
    if(id==InstrumentTileId.HEADING&&override in setOf(InstrumentSourceOverride.BOAT,InstrumentSourceOverride.PHONE)){
        val now=android.os.SystemClock.elapsedRealtime()
        if(override==InstrumentSourceOverride.BOAT){
            val trueValue=state.nmeaInstruments.headingTrue;val magneticValue=state.nmeaInstruments.headingMagnetic;val selected=trueValue?:magneticValue
            val fresh=selected?.second?.let{now-it in 0L..5_000L}==true
            val suffix=if(trueValue!=null)"°T" else "°M"
            return TripTileState(instrumentName(id),selected?.first?.let{"%03.0f%s".format(it,suffix)}?:"—",VesselDataSource.BOAT_NMEA,if(fresh)VesselDataFreshness.FRESH else if(selected!=null)VesselDataFreshness.STALE else VesselDataFreshness.UNAVAILABLE,selected?.second)
        }
        val trueValue=state.phoneHeading.liveTrueHeadingDegrees;val magneticValue=state.phoneHeading.liveMagneticHeadingDegrees;val value=trueValue?:magneticValue;val received=state.phoneHeading.receivedElapsedRealtime;val fresh=received?.let{now-it in 0L..1_500L}==true
        return TripTileState(instrumentName(id),value?.let{"%03.0f%s".format(it,if(trueValue!=null)"°T" else "°M")}?:"—",VesselDataSource.PHONE_MAGNETOMETER,if(fresh)VesselDataFreshness.FRESH else if(value!=null)VesselDataFreshness.STALE else VesselDataFreshness.UNAVAILABLE,received)
    }
    return when(id){
        InstrumentTileId.SOG->numeric(tr("SOG","对地航速"),data.sogKnots){"%.1f kn".format(it)}
        InstrumentTileId.BOAT_SPEED->numeric(tr("Boat speed","船速"),data.speedThroughWaterKnots){"%.1f kn".format(it)}
        InstrumentTileId.COG->numeric(tr("COG","对地航向"),data.cogTrueDegrees){"%03.0f°".format(it)}
        InstrumentTileId.HEADING->numeric(tr("Heading","船首向"),data.headingTrueDegrees){"%03.0f°T".format(it)}
        InstrumentTileId.DEPTH->numeric(tr("Depth","水深"),data.depthMeters){"%.1f m".format(it)}
        InstrumentTileId.UKC->numeric(tr("UKC","龙骨下余量"),data.derived.underKeelClearanceMeters){"%.1f m".format(it)}
        InstrumentTileId.POSITION->{val value=data.position.value;TripTileState(tr("Position","位置"),value?.let{"%.5f\n%.5f".format(it.latitude,it.longitude)}?:"—",data.position.source,data.position.freshness,data.position.receivedElapsedRealtime)}
        InstrumentTileId.TRUE_WIND_SPEED->numeric(tr("True wind","真风速"),data.trueWind.speedKnots){"%.1f kn".format(it)}
        InstrumentTileId.TRUE_WIND_DIRECTION->numeric(tr("TWD","真风向"),data.trueWind.directionDegrees){"%03.0f°T".format(it)}
        InstrumentTileId.APPARENT_WIND_SPEED->numeric(tr("Apparent wind","视风速"),data.apparentWind.speedKnots){"%.1f kn".format(it)}
        InstrumentTileId.APPARENT_WIND_ANGLE->numeric(tr("AWA","视风角"),data.apparentWind.angleDegrees,::windSideAngle)
        InstrumentTileId.TRUE_WIND_ANGLE->numeric(tr("TWA","真风角"),data.trueWind.angleDegrees,::windSideAngle)
        InstrumentTileId.HEEL->numeric(tr("Heel","横倾"),data.attitude.mapValue{it.heelDegrees}){"%+.1f°".format(it)}
        InstrumentTileId.PITCH->numeric(tr("Pitch","纵倾"),data.attitude.mapValue{it.pitchDegrees}){"%+.1f°".format(it)}
        InstrumentTileId.ROLL_RATE->numeric(tr("Roll rate","横摇角速度"),data.attitude.mapValue{it.rollRateDegreesPerSecond}){"%+.1f°/s".format(it)}
        InstrumentTileId.PITCH_RATE->numeric(tr("Pitch rate","纵摇角速度"),data.attitude.mapValue{it.pitchRateDegreesPerSecond}){"%+.1f°/s".format(it)}
        InstrumentTileId.ROLL_PERIOD->numeric(tr("Roll period","横摇周期"),data.motion.mapValue{it.dominantRollPeriodSeconds}){"%.1f s".format(it)}
        InstrumentTileId.MOTION_SCORE->numeric(tr("Motion score","运动评分"),data.motion.mapValue{it.score}){"%.0f".format(it)}
        InstrumentTileId.IMPACT_COUNT->numeric(tr("Impact candidates","冲击候选"),data.motion.mapValue{it.impactCandidateCount.toDouble()}){"%.0f".format(it)}
        InstrumentTileId.PRESSURE->numeric(tr("Pressure","气压"),data.pressureHpa){"%.1f hPa".format(it)}
        InstrumentTileId.PRESSURE_TREND_1H->numeric(tr("Pressure · 1 h","气压 · 1 小时"),data.derived.pressureTrend1hHpa){"%+.1f hPa".format(it)}
        InstrumentTileId.PRESSURE_TREND_3H->numeric(tr("Pressure · 3 h","气压 · 3 小时"),data.derived.pressureTrend3hHpa){"%+.1f hPa".format(it)}
        InstrumentTileId.PRESSURE_TREND_6H->numeric(tr("Pressure · 6 h","气压 · 6 小时"),data.derived.pressureTrend6hHpa){"%+.1f hPa".format(it)}
        InstrumentTileId.RATE_OF_TURN->numeric(tr("Rate of turn","转向率"),data.rateOfTurnDegreesPerMinute){"%+.1f°/min".format(it)}
        InstrumentTileId.RUDDER_ANGLE->numeric(tr("Rudder","舵角"),data.rudderAngleDegrees){"%+.1f°".format(it)}
        InstrumentTileId.WATER_TEMPERATURE->numeric(tr("Water temperature","水温"),data.waterTemperatureCelsius){"%.1f °C".format(it)}
        InstrumentTileId.AIR_TEMPERATURE->numeric(tr("Air temperature","气温"),data.airTemperatureCelsius){"%.1f °C".format(it)}
        InstrumentTileId.CURRENT_SET->numeric(tr("Current set","流向"),if(data.currentSetTrueDegrees.value!=null)data.currentSetTrueDegrees else data.derived.estimatedCurrentSetTrueDegrees){"%03.0f°T".format(it)}
        InstrumentTileId.CURRENT_DRIFT->numeric(tr("Current drift","流速"),if(data.currentDriftKnots.value!=null)data.currentDriftKnots else data.derived.estimatedCurrentDriftKnots){"%.1f kn".format(it)}
        InstrumentTileId.CROSS_TRACK_ERROR->numeric(tr("Cross-track error","横向偏差"),data.crossTrackErrorNauticalMiles){"%+.2f NM".format(it)}
        InstrumentTileId.WAYPOINT_BEARING->numeric(tr("Waypoint bearing","航点方位"),data.waypointBearingTrueDegrees){"%03.0f°T".format(it)}
        InstrumentTileId.WAYPOINT_DISTANCE->numeric(tr("Waypoint distance","航点距离"),data.waypointDistanceNauticalMiles){"%.2f NM".format(it)}
        InstrumentTileId.TOTAL_LOG->numeric(tr("Total log","总航程计"),data.totalLogNauticalMiles){"%.1f NM".format(it)}
        InstrumentTileId.TRIP_LOG->numeric(tr("Trip log","分段航程计"),data.tripLogNauticalMiles){"%.1f NM".format(it)}
        InstrumentTileId.VMG->numeric(tr("VMG to wind","迎风有效航速"),data.derived.vmgToWindKnots){"%+.1f kn".format(it)}
        InstrumentTileId.VMC->numeric(tr("VMC to waypoint","向航点有效航速"),data.derived.vmcToWaypointKnots){"%+.1f kn".format(it)}
    }.copy(title=instrumentName(id))
}

private fun customNmeaTile(field:NmeaFieldObservation,binding:DashboardTileBinding=DashboardTileBinding(nmeaFieldId=field.key.stableId)):TripTileState{
    val name=binding.label?.takeIf{it.isNotBlank()}?:field.key.transducerName?.takeIf{it.isNotBlank()}?:"${field.key.sentenceType} · ${field.key.semantic.name.lowercase().replace('_',' ')}"
    val unit=binding.unitOverride?.takeIf{it.isNotBlank()}?:field.unit
    val value=binding.transformed(field.value)?.let{"%.2f%s".format(it,unit?.let{valueUnit->" $valueUnit"}.orEmpty())}?:field.text?:"—"
    val freshness=if(field.isFresh(android.os.SystemClock.elapsedRealtime()))VesselDataFreshness.FRESH else VesselDataFreshness.STALE
    return TripTileState(name,value,VesselDataSource.BOAT_NMEA,freshness,field.receivedElapsedRealtime)
}

@Composable private fun TripLayoutDialog(preset:TripInstrumentPreset,current:List<DashboardTileBinding>,discovered:List<NmeaFieldObservation>,currentFields:List<DashboardTileBinding>,dismiss:()->Unit,save:(List<DashboardTileBinding>,List<DashboardTileBinding>)->Unit){
    var visible by remember(preset,current){mutableStateOf(current)}
    var fields by remember(preset,currentFields){mutableStateOf(currentFields)}
    var editingFieldId by remember{mutableStateOf<String?>(null)}
    val catalog=InstrumentLayoutPolicy.catalog(preset)
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Customize ${preset.name.lowercase()} instruments","自定义 ${preset.name.lowercase()} 仪表"))},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(tr("Show or hide instruments, then move visible items up or down.","显示或隐藏仪表，并调整已显示项目的顺序。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            visible.forEachIndexed{index,binding->val id=requireNotNull(binding.tileId);TripLayoutRow(id,true,binding.size,binding.sourceOverride?:InstrumentSourceOverride.VESSEL_DEFAULT,{visible=visible.filterIndexed{itemIndex,_->itemIndex!=index}},{if(index>0)visible=visible.toMutableList().also{list->list[index]=list[index-1].also{list[index-1]=list[index]}}},{if(index<visible.lastIndex)visible=visible.toMutableList().also{list->list[index]=list[index+1].also{list[index+1]=list[index]}}},{size->visible=visible.toMutableList().also{it[index]=binding.copy(size=size)}},{source->visible=visible.toMutableList().also{it[index]=binding.copy(sourceOverride=source)}},index>0,index<visible.lastIndex,true)}
            catalog.filterNot{id->visible.any{it.tileId==id}}.forEach{id->TripLayoutRow(id,false,InstrumentTileSize.MEDIUM,InstrumentSourceOverride.VESSEL_DEFAULT,{visible=visible+DashboardTileBinding(tileId=id)},{},{},{},{},false,false,true)}
            if(visible.any{it.tileId==InstrumentTileId.HEADING}&&visible.size<24)TextButton({val next=if(visible.none{it.tileId==InstrumentTileId.HEADING&&it.sourceOverride==InstrumentSourceOverride.BOAT})InstrumentSourceOverride.BOAT else InstrumentSourceOverride.PHONE;visible=visible+DashboardTileBinding(tileId=InstrumentTileId.HEADING,sourceOverride=next)}){Text(tr("Add comparison heading tile","添加对比船首向仪表"))}
            if(preset==TripInstrumentPreset.CUSTOM){
                HorizontalDivider(Modifier.padding(vertical=6.dp));Text(tr("Live NMEA fields discovered in the last 30 seconds","最近 30 秒发现的实时 NMEA 字段"),style=MaterialTheme.typography.titleSmall)
                if(discovered.isEmpty())Text(tr("No recent NMEA fields. Connect the boat stream, then reopen this editor.","暂无近期 NMEA 字段。连接船载数据流后重新打开此页面。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                discovered.distinctBy{it.key.stableId}.forEach{field->
                    val id=field.key.stableId;val binding=fields.firstOrNull{it.nmeaFieldId==id}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(binding!=null,{checked->fields=if(checked)fields+DashboardTileBinding(nmeaFieldId=id) else fields.filterNot{it.nmeaFieldId==id};if(checked)editingFieldId=id else if(editingFieldId==id)editingFieldId=null});Column(Modifier.weight(1f)){Text(field.key.transducerName?:"${field.key.talker}${field.key.sentenceType} · ${field.key.fieldIndex}");Text("${field.key.semantic.name.lowercase().replace('_',' ')}${field.unit?.let{" · $it"}.orEmpty()} · ${if(field.isFresh(android.os.SystemClock.elapsedRealtime()))tr("Fresh","实时")else tr("Stale","已过期")}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({editingFieldId=if(editingFieldId==id)null else id},enabled=binding!=null){Text(tr("Configure","配置"))}}
                    if(binding!=null&&editingFieldId==id)CustomNmeaBindingEditor(field,binding){updated->fields=fields.map{if(it.nmeaFieldId==id)updated else it}}
                }
            }
            TextButton({visible=catalog.map{DashboardTileBinding(tileId=it)}},Modifier.fillMaxWidth()){Text(tr("Restore preset defaults","恢复预设默认值"))}
        }
    },confirmButton={Button({save(visible,fields.distinctBy{it.nmeaFieldId}.take(24))}){Text(tr("Save layout","保存布局"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable private fun CustomNmeaBindingEditor(field:NmeaFieldObservation,binding:DashboardTileBinding,update:(DashboardTileBinding)->Unit){
    fun number(value:String,fallback:Double)=value.toDoubleOrNull()?.takeIf{it.isFinite()}?:fallback
    ElevatedCard(Modifier.fillMaxWidth().padding(start=36.dp,bottom=6.dp)){
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(tr("Advanced raw field · display only, never an Anchor safety input","高级原始字段 · 仅用于显示，绝不会进入锚警安全输入"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
            Text(tr("Last sentence: ${field.rawSentence.take(100)}","最近语句：${field.rawSentence.take(100)}"),style=MaterialTheme.typography.labelSmall)
            Text(tr("Preview: ${customNmeaTile(field,binding).value}","预览：${customNmeaTile(field,binding).value}"),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(binding.label.orEmpty(),{update(binding.copy(label=it.take(120)))},label={Text(tr("Label","名称"))},singleLine=true,modifier=Modifier.weight(1f));OutlinedTextField(binding.unitOverride.orEmpty(),{update(binding.copy(unitOverride=it.take(40)))},label={Text(tr("Unit","单位"))},singleLine=true,modifier=Modifier.weight(1f))}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(binding.scale.toString(),{update(binding.copy(scale=number(it,binding.scale)))},label={Text(tr("Scale","倍率"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f));OutlinedTextField(binding.offset.toString(),{update(binding.copy(offset=number(it,binding.offset)))},label={Text(tr("Offset","偏移"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))}
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){InstrumentTileSize.entries.forEachIndexed{index,size->SegmentedButton(binding.size==size,{update(binding.copy(size=size))},shape=SegmentedButtonDefaults.itemShape(index,InstrumentTileSize.entries.size)){Text(size.name.lowercase().replaceFirstChar{it.titlecase()})}}}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Record this field in Trips","在航程中记录此字段"));Text(tr("Maximum 2 Hz; full raw NMEA is not stored.","最高 2 Hz；不会保存完整原始 NMEA。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(binding.recordInTrips,{update(binding.copy(recordInTrips=it))})}
        }
    }
}

@Composable private fun TripLayoutRow(id:InstrumentTileId,shown:Boolean,size:InstrumentTileSize,source:InstrumentSourceOverride,toggle:()->Unit,up:()->Unit,down:()->Unit,resize:(InstrumentTileSize)->Unit,updateSource:(InstrumentSourceOverride)->Unit,canUp:Boolean,canDown:Boolean,allowSize:Boolean){
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(shown,{toggle()});Text(instrumentName(id),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);if(allowSize&&shown)TextButton({resize(InstrumentTileSize.entries[(size.ordinal+1)%InstrumentTileSize.entries.size])}){Text(size.name.take(1))};TextButton(up,enabled=canUp){Text("↑")};TextButton(down,enabled=canDown){Text("↓")}}
        if(shown&&id==InstrumentTileId.HEADING)SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start=36.dp,bottom=4.dp)){listOf(InstrumentSourceOverride.VESSEL_DEFAULT,InstrumentSourceOverride.BOAT,InstrumentSourceOverride.PHONE).forEachIndexed{index,value->SegmentedButton(source==value,{updateSource(value)},shape=SegmentedButtonDefaults.itemShape(index,3)){Text(when(value){InstrumentSourceOverride.VESSEL_DEFAULT->tr("Vessel default","船舶默认");InstrumentSourceOverride.BOAT->tr("Boat","船载");else->tr("Phone","手机")})}}}
    }
}

@Composable private fun instrumentName(id:InstrumentTileId)=MetricLabelRegistry.get(id).let{label->"${MetricLabelRegistry.localizedName(id,LocalAppLanguage.current)} (${label.acronym})"}

@Composable private fun presetName(value:TripInstrumentPreset)=when(value){TripInstrumentPreset.SAILING->tr("Sail","帆航");TripInstrumentPreset.NAV->tr("Nav","导航");TripInstrumentPreset.MOTION->tr("Motion","运动");TripInstrumentPreset.WEATHER->tr("Weather","天气");TripInstrumentPreset.CUSTOM->tr("Custom","自定义")}
private fun presetNamePlain(value:TripInstrumentPreset)=value.name.lowercase().replaceFirstChar{it.titlecase()}

@Composable private fun SailingCompass(state:MainUiState,compact:Boolean=false){
    AbsoluteDirectionInstrument(state,compact)
}

private fun windSideAngle(value:Double)="%.0f° %s".format(kotlin.math.abs(value),if(value<0)"P" else "S")

internal enum class AdaptiveMarineLayoutMode{COMPACT_SQUARE,COMPACT_PORTRAIT,REGULAR_PORTRAIT,WIDE}
internal object AdaptiveMarineLayoutPolicy{
    fun classify(widthDp:Float,heightDp:Float)=when{
        widthDp<=400f&&heightDp<=400f&&widthDp/heightDp in .85f..1.15f->AdaptiveMarineLayoutMode.COMPACT_SQUARE
        widthDp>heightDp->AdaptiveMarineLayoutMode.WIDE
        widthDp<=380f||heightDp<=700f->AdaptiveMarineLayoutMode.COMPACT_PORTRAIT
        else->AdaptiveMarineLayoutMode.REGULAR_PORTRAIT
    }
}

@Composable private fun TripOverviewPage(state:MainUiState,vm:MainViewModel){
    val trip=state.activeTrip
    val mapData by produceState<com.yokuli.anchorwatch.data.trip.TripMapData?>(null,trip?.id,trip?.waypointCount){value=trip?.let{vm.tripMapData(it.id,com.yokuli.anchorwatch.data.trip.TripTrackRenderPolicy.OVERVIEW_BUDGET)}}
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical=6.dp).testTag("mfd_page_OVERVIEW")){
        val mode=AdaptiveMarineLayoutPolicy.classify(maxWidth.value,maxHeight.value)
        val track=state.tripTrack.takeIf{it.tripId==trip?.id}?.rendered(com.yokuli.anchorwatch.data.trip.TripTrackRenderPolicy.OVERVIEW_BUDGET)?:mapData?.segments.orEmpty()
        when(mode){
            AdaptiveMarineLayoutMode.WIDE->Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TripOverviewPreviewMap(state,track,mapData?.waypoints.orEmpty(),trip!=null,vm::openLiveTripMap,Modifier.weight(.47f).fillMaxHeight())
                AbsoluteDirectionInstrument(state,modifier=Modifier.weight(.30f).fillMaxHeight())
                OverviewMetrics(state,false,Modifier.weight(.23f).fillMaxHeight())
            }
            AdaptiveMarineLayoutMode.COMPACT_SQUARE->Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(5.dp)){
                TripOverviewPreviewMap(state,track,mapData?.waypoints.orEmpty(),trip!=null,vm::openLiveTripMap,Modifier.weight(.28f).fillMaxWidth())
                Row(Modifier.weight(.72f),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                    AbsoluteDirectionInstrument(state,compact=true,modifier=Modifier.weight(.56f).fillMaxHeight())
                    OverviewMetrics(state,true,Modifier.weight(.44f).fillMaxHeight())
                }
            }
            else->Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                TripOverviewPreviewMap(state,track,mapData?.waypoints.orEmpty(),trip!=null,vm::openLiveTripMap,Modifier.weight(.43f).fillMaxWidth())
                Row(Modifier.weight(.57f),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    AbsoluteDirectionInstrument(state,modifier=Modifier.weight(.55f).fillMaxHeight())
                    OverviewMetrics(state,false,Modifier.weight(.45f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable private fun TripOverviewPreviewMap(state:MainUiState,segments:List<com.yokuli.anchorwatch.data.trip.TripTrackSegment>,waypoints:List<com.yokuli.anchorwatch.data.database.TripWaypointEntity>,enabled:Boolean,open:()->Unit,modifier:Modifier){
    val current=state.vesselData.position.value?.let{LatLng(it.latitude,it.longitude)}
    val points=segments.flatMap{segment->segment.points}.mapNotNull{point->if(point.hasPosition)LatLng(point.latitude!!,point.longitude!!)else null}
    ElevatedCard(modifier.testTag("sail_overview_map_preview")){
        Box(Modifier.fillMaxSize()){
            if(BuildConfig.MAPS_CONFIGURED&&(current!=null||points.isNotEmpty())){
                val initial=current?:points.last();val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(initial,14f)};var loaded by remember{mutableStateOf(false)}
                val frameKey=points.lastOrNull() to current
                LaunchedEffect(loaded,frameKey){if(!loaded)return@LaunchedEffect;kotlinx.coroutines.delay(900L);val frame=(points+listOfNotNull(current));if(frame.size==1)camera.animate(CameraUpdateFactory.newLatLngZoom(frame.first(),15f))else runCatching{val bounds=com.google.android.gms.maps.model.LatLngBounds.builder().also{builder->frame.forEach(builder::include)}.build();camera.animate(CameraUpdateFactory.newLatLngBounds(bounds,34))}}
                GoogleMap(Modifier.fillMaxSize(),cameraPositionState=camera,onMapLoaded={loaded=true},uiSettings=MarineMapPolicy.uiSettings(MarineMapContext.SAIL_PREVIEW)){
                    segments.forEachIndexed{index,segment->val route=segment.points.mapNotNull{point->if(point.hasPosition)LatLng(point.latitude!!,point.longitude!!)else null};if(route.size>1)Polyline(route,color=MaterialTheme.colorScheme.primary,width=5f,zIndex=2f,tag="overview-$index")}
                    points.firstOrNull()?.let{Marker(remember(it){MarkerState(it)},title=tr("Trip start","航程起点"))}
                    waypoints.takeLast(12).forEach{waypoint->val target=LatLng(waypoint.latitude,waypoint.longitude);Marker(remember(waypoint.id){MarkerState(target)},title=waypoint.name)}
                    current?.let{Marker(remember(it){MarkerState(it)},title=tr("Vessel","船位"))}
                }
            }else{
                Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.OpenInFull,null);Text(if(current==null)tr("Waiting for the Trip-selected position","正在等待航程选定的位置")else tr("Map unavailable in this build","当前构建无法显示地图"),style=MaterialTheme.typography.bodySmall)}
            }
            Box(Modifier.fillMaxSize().clickable(enabled=enabled,onClick=open).testTag("open_live_trip_map"))
            Surface(Modifier.align(Alignment.BottomEnd).padding(7.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface.copy(alpha=.90f)){Text(if(enabled)tr("Tap to open full map ↗","点击打开完整地图 ↗")else tr("Start a Trip to open map","开始航程后打开地图"),Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
        }
    }
}

@Composable private fun OverviewMetrics(state:MainUiState,compact:Boolean,modifier:Modifier){
    val data=state.vesselData
    Column(modifier,verticalArrangement=Arrangement.spacedBy(if(compact)3.dp else 6.dp)){
        OverviewMetric("SOG",data.sogKnots.value?.let{"%.1f kn".format(it)}?:"—",data.sogKnots.freshness,Modifier.weight(1f))
        OverviewMetric("STW",data.speedThroughWaterKnots.value?.let{"%.1f kn".format(it)}?:"—",data.speedThroughWaterKnots.freshness,Modifier.weight(1f))
        OverviewMetric(if(data.trueWind.speedKnots.value!=null)"TWS" else "AWS",(data.trueWind.speedKnots.value?:data.apparentWind.speedKnots.value)?.let{"%.1f kn".format(it)}?:"—",if(data.trueWind.speedKnots.value!=null)data.trueWind.speedKnots.freshness else data.apparentWind.speedKnots.freshness,Modifier.weight(1f))
        OverviewMetric("DEPTH",data.depthMeters.value?.let{"%.1f m".format(it)}?:"—",data.depthMeters.freshness,Modifier.weight(1f))
    }
}

@Composable private fun OverviewMetric(label:String,value:String,freshness:VesselDataFreshness,modifier:Modifier){
    Surface(modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,tonalElevation=2.dp){Column(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=3.dp),verticalArrangement=Arrangement.Center){Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1);if(freshness!=VesselDataFreshness.FRESH)Text(if(freshness==VesselDataFreshness.STALE)tr("stale","已过期")else tr("unavailable","不可用"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)}}
}

@Composable private fun sourceName(value:VesselDataSource)=when(value){VesselDataSource.BOAT_NMEA->tr("Boat","船载 NMEA");VesselDataSource.PHONE_GNSS->tr("Phone GPS","手机 GPS");VesselDataSource.PHONE_IMU,VesselDataSource.PHONE_MAGNETOMETER->tr("Phone sensor","手机传感器");VesselDataSource.PHONE_BAROMETER->tr("Phone barometer","手机气压计");VesselDataSource.DERIVED->tr("Derived","推算");VesselDataSource.DEMO->tr("Demo","演示");VesselDataSource.NONE->tr("No source","无数据源")}
@Composable private fun freshnessName(value:VesselDataFreshness)=when(value){VesselDataFreshness.FRESH->tr("live","实时");VesselDataFreshness.HELD->tr("held","保持值");VesselDataFreshness.STALE->tr("stale","已过期");VesselDataFreshness.UNAVAILABLE->tr("unavailable","不可用")}

@Composable
private fun TripPositionMap(state:MainUiState){
    val position=state.vesselData.position.value
    ElevatedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(tr("Live position","实时船位"),style=MaterialTheme.typography.titleSmall)
            if(position==null)Text(tr("Waiting for a Boat or Phone position. Trip recording may continue with gaps.","正在等待船载或手机定位；航程可继续记录，但会保留缺口。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else{
                Text("${"%.6f".format(position.latitude)}, ${"%.6f".format(position.longitude)} · ${sourceName(state.vesselData.position.source)}",style=MaterialTheme.typography.bodySmall)
                if(BuildConfig.MAPS_CONFIGURED){
                    val target=LatLng(position.latitude,position.longitude)
                    val camera=rememberCameraPositionState{this.position=CameraPosition.fromLatLngZoom(target,14f)}
                    LaunchedEffect(target){camera.animate(CameraUpdateFactory.newLatLng(target))}
                    GoogleMap(Modifier.fillMaxWidth().height(210.dp),cameraPositionState=camera,uiSettings=MapUiSettings(compassEnabled=false,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false)){Marker(state=remember(target){MarkerState(target)},title=tr("Boat","船位"))}
                }
            }
        }
    }
}

@Composable
internal fun TripStartDialog(state:MainUiState,dismiss:()->Unit,confirmAttitude:(DeviceBowAxis)->Unit={},start:(String,Boolean,VesselSourcePreference)->Unit){
    var name by remember{mutableStateOf("")}
    var positionPreference by remember(state.vesselSettings.positionPreference){mutableStateOf(state.vesselSettings.positionPreference.takeUnless{it==VesselSourcePreference.DERIVED}?:VesselSourcePreference.AUTO)}
    val positionCandidates=state.vesselData.candidates[VesselMetricId.POSITION].orEmpty()
    val boatPositionReady=positionCandidates.any{it.sourceClass==VesselSourceClass.BOAT_NMEA&&it.validity==CandidateValidity.ELIGIBLE}
    val phonePositionReady=positionCandidates.any{it.sourceClass==VesselSourceClass.PHONE_GNSS&&it.validity==CandidateValidity.ELIGIBLE}
    val positionReady=when(positionPreference){
        VesselSourcePreference.AUTO->state.vesselData.position.value!=null&&state.vesselData.position.freshness==VesselDataFreshness.FRESH
        VesselSourcePreference.BOAT->boatPositionReady
        VesselSourcePreference.PHONE->phonePositionReady
        VesselSourcePreference.DERIVED->false
    }
    val phoneMotionAvailable=state.phoneSensorCapabilities.attitudeAvailable
    val phoneMotionReady=phoneMotionAvailable&&state.vesselMountCalibration.attitudeFrameConfirmed&&state.phoneVesselMountState==PhoneVesselMountState.VESSEL_MOUNTED
    var phoneMotion by remember{mutableStateOf(false)}
    var attitudeAxis by remember(state.vesselMountCalibration.bowAxis){mutableStateOf(state.vesselMountCalibration.bowAxis)}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Start Trip Watch","开始航程监控"))},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(name,{name=it},label={Text(tr("Trip name (optional)","航程名称（可选）"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text(tr("Recording readiness","记录就绪检查"),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(tr("Trip position source *","航程位置来源 *"),style=MaterialTheme.typography.labelLarge)
                Text(tr("This is a per-trip recording choice and does not change the Anchor Watch GPS or the Data → Vessel default.","这是本次航程专用的记录选择，不会改变锚警 GPS，也不会修改“数据 → 船舶”的默认设置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                    listOf(VesselSourcePreference.AUTO,VesselSourcePreference.BOAT,VesselSourcePreference.PHONE).forEach{value->
                        FilterChip(
                            selected=positionPreference==value,
                            onClick={positionPreference=value},
                            label={Text(when(value){VesselSourcePreference.AUTO->tr("Auto","自动");VesselSourcePreference.BOAT->tr("Boat NMEA","船载 NMEA");VesselSourcePreference.PHONE->tr("Phone GPS","手机 GPS");else->""},maxLines=1)},
                            modifier=Modifier.weight(1f).testTag("trip_position_${value.name.lowercase()}"),
                        )
                    }
                }
                TripReadinessRow(tr("Selected position","选定位置"),positionReady,when(positionPreference){VesselSourcePreference.AUTO->"${sourceName(state.vesselData.position.source)} · ${freshnessName(state.vesselData.position.freshness)}";VesselSourcePreference.BOAT->if(boatPositionReady)tr("Fresh boat NMEA position","船载 NMEA 位置实时")else tr("No eligible boat position","没有合格的船载位置");VesselSourcePreference.PHONE->if(phonePositionReady)tr("Fresh Android GNSS position","Android GNSS 位置实时")else tr("Waiting for Android GNSS","正在等待 Android GNSS");else->tr("Unavailable","不可用")})
                TripReadinessRow(tr("Heading","船首向"),state.vesselData.headingTrueDegrees.value!=null,"${sourceName(state.vesselData.headingTrueDegrees.source)} · ${freshnessName(state.vesselData.headingTrueDegrees.freshness)}")
                TripReadinessRow(tr("Depth / wind","水深 / 风"),state.vesselData.depthMeters.value!=null||state.vesselData.trueWind.speedKnots.value!=null,tr("Optional; gaps are retained","可选；缺失会保留为空档"))
                TripReadinessRow(tr("Phone pressure","手机气压"),state.phoneSensorCapabilities.pressureAvailable,if(state.phoneSensorCapabilities.pressureAvailable)tr("Recorded independently","将独立记录") else tr("No barometer","没有气压计"))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){
                        Text(tr("Record sailing attitude","记录航行姿态"),style=MaterialTheme.typography.bodyMedium)
                        Text(if(!phoneMotionAvailable)tr("This phone has no compatible rotation sensor","此手机没有兼容的旋转传感器") else if(phoneMotionReady)tr("Phone-to-vessel frame confirmed for this trip","已为本航程确认手机与船体坐标关系") else tr("Optional · confirm after placing the phone on the vessel","可选 · 将手机放到船上后再确认"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=phoneMotion,enabled=phoneMotionAvailable,onCheckedChange={phoneMotion=it},modifier=Modifier.testTag("trip_phone_motion"))
                }
                if(phoneMotion){
                    Surface(color=if(phoneMotionReady)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                        Text(tr("Place the phone screen plane parallel to the vessel reference plane. Do not level the current boat attitude to zero; the App will retain the heel that exists now.","让手机屏幕平面与船体参考平面平行。不要把船此刻的倾斜归零；App 会保留当前真实横倾。"),style=MaterialTheme.typography.bodySmall)
                        Text(tr("Which phone edge points to the bow?","手机哪一边指向船艏？"),style=MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){DeviceBowAxis.entries.forEachIndexed{index,value->SegmentedButton(attitudeAxis==value,{attitudeAxis=value},shape=SegmentedButtonDefaults.itemShape(index,DeviceBowAxis.entries.size)){Text(tripBowAxisLabel(value))}}}
                        Button({confirmAttitude(attitudeAxis)},Modifier.fillMaxWidth().testTag("trip_confirm_attitude_frame"),enabled=phoneMotionAvailable){Text(if(phoneMotionReady)tr("Confirm again","重新确认")else tr("Phone is placed · confirm","手机已放好 · 确认"))}
                        Text(if(phoneMotionReady)tr("Ready. Before picking up the phone, tap Pause attitude; place it back and confirm to begin a new valid segment.","已就绪。拿起手机前请点“暂停姿态”；放回后重新确认即可开始新的有效段。") else tr("Confirmation is required before Start when attitude recording is selected.","选择记录姿态后，必须先确认才能开始航程。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }}
                }
                Text(tr("You may start with missing optional instruments. Their gaps remain explicit; the app will not invent replacement values.","可在可选仪表缺失时开始；缺口会被明确保留，应用不会编造替代值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton={Button({start(name,phoneMotion,positionPreference)},enabled=positionReady&&(!phoneMotion||phoneMotionReady),modifier=Modifier.testTag("start_trip_recording")){Text(tr("Start recording","开始记录"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
    )
}

@Composable
private fun TripAttitudeStatusBar(state:MainUiState,openFrame:()->Unit,pause:()->Unit){
    val trip=state.activeTrip?:return
    val recording=trip.phoneMotionEnabled&&state.phoneVesselMountState==PhoneVesselMountState.VESSEL_MOUNTED&&state.vesselData.attitude.freshness==VesselDataFreshness.FRESH
    val invalidated=state.phoneVesselMountState==PhoneVesselMountState.MOUNT_SUSPECT
    Surface(Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("trip_attitude_status"),color=when{recording->MaterialTheme.colorScheme.primaryContainer;invalidated->MaterialTheme.colorScheme.errorContainer;else->MaterialTheme.colorScheme.tertiaryContainer},shape=MaterialTheme.shapes.medium){
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f)){
                Text(when{recording->tr("Attitude segment recording","正在记录姿态段");invalidated->tr("Previous attitude frame invalid","上一个姿态参考已失效");else->tr("Attitude not entering Trip Report","姿态未计入航程报告")},fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                Text(when{recording->tr("Heel, pitch and motion are valid for this segment","本段横倾、纵倾和运动数据有效");invalidated->tr("Place the phone back and confirm a new segment","请放回手机并确认新的姿态段");else->tr("Other trip data continues normally","其他航程数据继续正常记录")},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(recording)TextButton(pause){Text(tr("Pause","暂停"))}else Button(openFrame){Text(tr("Place & confirm","放好并确认"))}
        }
    }
}

@Composable
private fun TripAttitudeFrameDialog(state:MainUiState,dismiss:()->Unit,confirm:(DeviceBowAxis)->Unit){
    var axis by remember(state.vesselMountCalibration.bowAxis){mutableStateOf(state.vesselMountCalibration.bowAxis)}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Confirm Trip attitude frame","确认航程姿态坐标"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("Place the phone screen plane parallel to the vessel reference plane. The App uses gravity to retain the vessel's current heel; this is not a zero adjustment.","让手机屏幕平面与船体参考平面平行。App 会根据重力保留船当前的真实横倾；这不是归零操作。"))
            Text(tr("Which edge points to the bow?","哪一边指向船艏？"),fontWeight=FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){DeviceBowAxis.entries.forEachIndexed{index,value->SegmentedButton(axis==value,{axis=value},shape=SegmentedButtonDefaults.itemShape(index,DeviceBowAxis.entries.size)){Text(tripBowAxisLabel(value))}}}
            Text(tr("Before moving the phone, tap Pause attitude. Reconfirm after placing it again. The App does not guess pick-up from ordinary vessel motion.","移动手机前请先点“暂停姿态”，放回后重新确认。App 不会把船舶正常运动擅自判断为拿起手机。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={Button({confirm(axis);dismiss()},enabled=state.phoneSensorCapabilities.attitudeAvailable,modifier=Modifier.testTag("trip_reconfirm_attitude")){Text(tr("Phone is placed · confirm","手机已放好 · 确认"))}},
        dismissButton={TextButton(dismiss){Text(tr("Not now","暂不"))}},
    )
}

@Composable private fun tripBowAxisLabel(value:DeviceBowAxis)=when(value){DeviceBowAxis.TOP->tr("Top","上边");DeviceBowAxis.BOTTOM->tr("Bottom","下边");DeviceBowAxis.LEFT->tr("Left","左边");DeviceBowAxis.RIGHT->tr("Right","右边")}

@Composable
private fun TripReadinessRow(label:String,ready:Boolean,detail:String){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(if(ready)"●" else "○",color=if(ready)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.bodyMedium);Text(detail,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable
private fun TripWaypointDialog(dismiss:()->Unit,save:(String,String,String)->Unit){
    var name by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var type by remember{mutableStateOf("GENERAL")}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Mark waypoint","标记航点"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text(tr("Name","名称"))},singleLine=true);OutlinedTextField(note,{note=it},label={Text(tr("Note","备注"))});Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("GENERAL","SAIL_CHANGE","WEATHER","HAZARD").forEach{value->FilterChip(type==value,{type=value},label={Text(value.lowercase().replace('_',' '))})}}}},confirmButton={Button({save(name,note,type)}){Text(tr("Save","保存"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}
