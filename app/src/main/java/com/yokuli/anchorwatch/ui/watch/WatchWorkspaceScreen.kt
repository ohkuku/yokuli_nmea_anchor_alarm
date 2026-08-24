package com.yokuli.anchorwatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.google.maps.android.compose.rememberCameraPositionState
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val trip=state.activeTrip
    val healthNow by produceState(android.os.SystemClock.elapsedRealtime()){while(true){kotlinx.coroutines.delay(1_000L);value=android.os.SystemClock.elapsedRealtime()}}
    val nmeaTrafficLive=state.connection!=com.yokuli.anchorwatch.domain.model.NmeaConnectionState.DISCONNECTED&&state.diagnostics.lastPacketElapsed?.let{healthNow-it in 0L..5_000L}==true
    val imuLive=state.vesselData.attitude.freshness==VesselDataFreshness.FRESH
    val selectedDashboard=state.tripDashboards.firstOrNull{it.id==selectedCustomDashboardId}
    val builtIn=state.tripDashboards.filter{it.preset!=TripInstrumentPreset.CUSTOM}.associateBy{it.preset}
    val instrumentPages=listOf(TripInstrumentPreset.SAILING,TripInstrumentPreset.NAV,TripInstrumentPreset.MOTION,TripInstrumentPreset.WEATHER).map{it to builtIn[it]}+state.tripDashboards.filter{it.preset==TripInstrumentPreset.CUSTOM}.map{TripInstrumentPreset.CUSTOM to it}
    val instrumentPager=rememberPagerState(initialPage=0,pageCount={instrumentPages.size});val instrumentScope=rememberCoroutineScope()
    LaunchedEffect(instrumentPager.currentPage,instrumentPages){instrumentPages.getOrNull(instrumentPager.currentPage)?.let{(pagePreset,dashboard)->preset=pagePreset;selectedCustomDashboardId=dashboard?.id}}
    LaunchedEffect(selectedCustomDashboardId,state.tripDashboards){selectedCustomDashboardId?.let{id->val index=instrumentPages.indexOfFirst{it.second?.id==id};if(index>=0&&index!=instrumentPager.currentPage)instrumentPager.scrollToPage(index)}}
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
                    val issues=buildList{if(!nmeaTrafficLive)add(tr("NMEA stale","NMEA 已静默"));if(state.vesselData.position.freshness!=VesselDataFreshness.FRESH)add(tr("GPS unavailable","GPS 不可用"));if(!imuLive)add(tr("IMU unavailable","IMU 不可用"))}
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
                Row(Modifier.fillMaxWidth().height(44.dp),verticalAlignment=Alignment.CenterVertically){Box{TextButton({pagePicker=true},enabled=!touchLocked){Text(instrumentPages.getOrNull(instrumentPager.currentPage)?.let{(pagePreset,dashboard)->dashboard?.title?:presetName(pagePreset)}?:"—",fontWeight=FontWeight.SemiBold)};DropdownMenu(pagePicker,{pagePicker=false}){instrumentPages.forEachIndexed{index,(pagePreset,dashboard)->DropdownMenuItem({Text(dashboard?.title?:presetName(pagePreset))},{pagePicker=false;instrumentScope.launch{instrumentPager.animateScrollToPage(index)}})}}};Spacer(Modifier.weight(1f));Text("${instrumentPager.currentPage+1} / ${instrumentPages.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));instrumentPages.forEachIndexed{index,_->Text(if(index==instrumentPager.currentPage)"●" else "○",Modifier.padding(horizontal=2.dp),color=if(index==instrumentPager.currentPage)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
            }
            HorizontalPager(instrumentPager,Modifier.fillMaxWidth().weight(1f),userScrollEnabled=!touchLocked){index->val (pagePreset,dashboard)=instrumentPages[index];TripInstrumentViewport(state,pagePreset,dashboard){if(!touchLocked)customizeLayout=true}}
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
    if(startDialog)TripStartDialog(state,{startDialog=false}){name,phoneMotion->startDialog=false;vm.startTrip(name,phoneMotion)}
    if(customizeLayout){
        val dashboard=instrumentPages.getOrNull(instrumentPager.currentPage)?.second
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
    expanded?.let{tile->Dialog(onDismissRequest={expanded=null},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){Surface(Modifier.fillMaxSize().testTag("mfd_fullscreen_instrument"),color=MaterialTheme.colorScheme.background){Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)){IconButton({expanded=null},Modifier.align(Alignment.TopEnd)){Icon(Icons.Default.Close,tr("Close","关闭"))};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(tile.title,style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(24.dp));Text(tile.value,style=MaterialTheme.typography.displayLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.height(24.dp));Text("${sourceName(tile.source)} · ${freshnessName(tile.freshness)}",style=MaterialTheme.typography.titleLarge,color=if(tile.freshness==VesselDataFreshness.FRESH)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)}}}}}
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

private fun <T,R> VesselObservation<T>.mapValue(transform:(T)->R?):VesselObservation<R> = VesselObservation(value=value?.let(transform),source=source,observedAtUtcMillis=observedAtUtcMillis,receivedElapsedRealtime=receivedElapsedRealtime,quality=quality,freshness=freshness,provenance=provenance)

private data class TripTileState(val title:String,val value:String,val source:VesselDataSource,val freshness:VesselDataFreshness,val receivedElapsedRealtime:Long?=null)

@Composable private fun tripTile(id:InstrumentTileId,state:MainUiState,override:InstrumentSourceOverride=InstrumentSourceOverride.VESSEL_DEFAULT):TripTileState{
    val data=state.vesselData
    fun numeric(title:String,value:VesselObservation<Double>,format:(Double)->String)=TripTileState(title,value.value?.let(format)?:"—",value.source,value.freshness,value.receivedElapsedRealtime)
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
    val data=state.vesselData;val awa=data.apparentWind.angleDegrees.value;val twa=data.trueWind.angleDegrees.value
    val primary=MaterialTheme.colorScheme.primary;val secondary=MaterialTheme.colorScheme.tertiary;val grid=MaterialTheme.colorScheme.outline.copy(alpha=.55f)
    ElevatedCard(Modifier.fillMaxSize().testTag("sail_compass")){
        Column(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=if(compact)3.dp else 6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=if(compact)Arrangement.SpaceEvenly else Arrangement.spacedBy(3.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("HDG ${data.headingTrueDegrees.value?.let{"%03.0f°".format(it)}?:"—"}",fontWeight=FontWeight.SemiBold);Text("COG ${data.cogTrueDegrees.value?.let{"%03.0f°".format(it)}?:"—"}",fontWeight=FontWeight.SemiBold)}
            Text("TWD ${data.trueWind.directionDegrees.value?.let{"%03.0f°T".format(it)}?:"—"}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(if(compact)54.dp else 112.dp),contentAlignment=Alignment.Center){
                Canvas(Modifier.size(if(compact)52.dp else 108.dp)){
                    val center=Offset(size.width/2,size.height/2);val radius=size.minDimension*.46f
                    drawCircle(grid,radius,center,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    repeat(12){index->val angle=Math.toRadians(index*30.0);val outer=Offset(center.x+kotlin.math.sin(angle).toFloat()*radius,center.y-kotlin.math.cos(angle).toFloat()*radius);val inner=Offset(center.x+kotlin.math.sin(angle).toFloat()*(radius-10f),center.y-kotlin.math.cos(angle).toFloat()*(radius-10f));drawLine(grid,inner,outer,2f)}
                    fun arrow(angleDegrees:Double,color:Color,width:Float){val angle=Math.toRadians(angleDegrees);val tip=Offset(center.x+kotlin.math.sin(angle).toFloat()*(radius-15f),center.y-kotlin.math.cos(angle).toFloat()*(radius-15f));drawLine(color,center,tip,width);drawCircle(color,width,tip)}
                    twa?.let{arrow(it,secondary,5f)};awa?.let{arrow(it,primary,7f)}
                    drawLine(Color.White,Offset(center.x,center.y+20f),Offset(center.x,center.y-radius*.50f),5f)
                }
                Text("▲",Modifier.align(Alignment.TopCenter),color=MaterialTheme.colorScheme.onSurface)
            }
            if(!compact){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Text("AWA ${awa?.let(::windSideAngle)?:"—"} · ${data.apparentWind.speedKnots.value?.let{"%.1f kn".format(it)}?:"—"}",color=primary);Text("TWA ${twa?.let(::windSideAngle)?:"—"} · ${data.trueWind.speedKnots.value?.let{"%.1f kn".format(it)}?:"—"}",color=secondary)}
                Text("BSP ${data.speedThroughWaterKnots.value?.let{"%.1f kn".format(it)}?:"—"} · SOG ${data.sogKnots.value?.let{"%.1f kn".format(it)}?:"—"}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            }
        }
    }
}

private fun windSideAngle(value:Double)="%.0f° %s".format(kotlin.math.abs(value),if(value<0)"P" else "S")

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
private fun TripStartDialog(state:MainUiState,dismiss:()->Unit,start:(String,Boolean)->Unit){
    var name by remember{mutableStateOf("")}
    val phoneMotionReady=state.phoneSensorCapabilities.attitudeAvailable&&state.vesselMountCalibration.calibratedAt>0L
    var phoneMotion by remember(phoneMotionReady){mutableStateOf(phoneMotionReady)}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Start Trip Watch","开始航程监控"))},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(name,{name=it},label={Text(tr("Trip name (optional)","航程名称（可选）"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text(tr("Recording readiness","记录就绪检查"),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                TripReadinessRow(tr("Position","位置"),state.vesselData.position.value!=null,"${sourceName(state.vesselData.position.source)} · ${freshnessName(state.vesselData.position.freshness)}")
                TripReadinessRow(tr("Heading","船首向"),state.vesselData.headingTrueDegrees.value!=null,"${sourceName(state.vesselData.headingTrueDegrees.source)} · ${freshnessName(state.vesselData.headingTrueDegrees.freshness)}")
                TripReadinessRow(tr("Depth / wind","水深 / 风"),state.vesselData.depthMeters.value!=null||state.vesselData.trueWind.speedKnots.value!=null,tr("Optional; gaps are retained","可选；缺失会保留为空档"))
                TripReadinessRow(tr("Phone pressure","手机气压"),state.phoneSensorCapabilities.pressureAvailable,if(state.phoneSensorCapabilities.pressureAvailable)tr("Recorded independently","将独立记录") else tr("No barometer","没有气压计"))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){
                        Text(tr("Phone vessel motion","手机船体运动"),style=MaterialTheme.typography.bodyMedium)
                        Text(if(phoneMotionReady)tr("Mount calibration is ready","安装姿态已校准") else tr("Unavailable until the phone is mounted and calibrated in Settings","请先将手机固定，并在设置中完成安装校准"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=phoneMotion,enabled=phoneMotionReady,onCheckedChange={phoneMotion=it},modifier=Modifier.testTag("trip_phone_motion"))
                }
                Text(tr("You may start with missing optional instruments. Their gaps remain explicit; the app will not invent replacement values.","可在可选仪表缺失时开始；缺口会被明确保留，应用不会编造替代值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton={Button({start(name,phoneMotion)}){Text(tr("Start recording","开始记录"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
    )
}

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
