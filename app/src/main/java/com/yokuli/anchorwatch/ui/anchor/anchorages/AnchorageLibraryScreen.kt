package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.AnchorageQrScannerScreen
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.PageHeader
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import com.yokuli.anchorwatch.tr
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlin.math.cos
import kotlin.math.pow

@Composable
fun AnchorageLibraryScreen(
    openGoogleMaps: (Double, Double) -> Unit,
    approachSpot: (Long) -> Unit,
    vm: AnchorageLibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val planningPoint by vm.planningPoint.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    var showRegions by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar=remember{SnackbarHostState()}
    val actionMessage=when(state.action){
        AnchorageLibraryAction.SAVED->tr("Saved anchorage updated.","收藏锚地已更新。")
        AnchorageLibraryAction.DELETED->tr("Saved anchorage deleted.","收藏锚地已删除。")
        AnchorageLibraryAction.ACTIVE_WATCH_BLOCKS_DELETE->tr("This anchorage is linked to the active Anchor Watch. Lift or end that watch before deleting it.","这个锚地正关联当前锚警。请先起锚或结束该锚警，再删除。")
        AnchorageLibraryAction.FAILED->tr("The saved anchorage could not be changed. Nothing was removed.","无法修改收藏锚地，未删除任何内容。")
        null->null
    }
    LaunchedEffect(state.action,actionMessage){if(actionMessage!=null){snackbar.showSnackbar(actionMessage);vm.clearAction()}}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let(vm::importPhoto)}

    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageHeader(
            tr("Saved anchorages", "收藏锚地"),
            tr("Your private, offline places to revisit, approach and share.", "可再次查看、接近和分享的私人离线锚地。"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if(state.regions.isNotEmpty()||state.allPlaces.any{it.primaryRegionId==null})OutlinedButton(onClick={showRegions=true},modifier=Modifier.weight(1f).testTag("anchorage_region_selector")){
                Icon(Icons.Default.Public,null);Spacer(Modifier.width(5.dp));Text(when(state.selectedRegionId){null->tr("Everywhere","全部区域");UNASSIGNED_REGION_ID->tr("No region","未归类");else->state.regions.firstOrNull{it.id==state.selectedRegionId}?.displayName?:tr("Region","区域")},maxLines=1)
            } else Spacer(Modifier.weight(1f))
            IconButton({ showFilters = true }, Modifier.testTag("anchorage_filters")) {
                Icon(Icons.Default.FilterAlt, tr("Filters", "筛选"))
            }
            IconButton({ showQrScanner = true }, Modifier.testTag("scan_anchorage_qr")) {
                Icon(Icons.Default.QrCodeScanner, tr("Scan QR", "扫描二维码"))
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::search,
            modifier = Modifier.fillMaxWidth().testTag("anchorage_search"),
            singleLine = true,
            label = { Text(tr("Search saved anchorages and notes", "搜索收藏锚地和备注")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.query.isNotBlank()) {
                    IconButton({ vm.search("") }) { Icon(Icons.Default.Close, tr("Clear", "清除")) }
                }
            },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){
            val all=!state.filters.favoriteOnly&&!state.filters.visitedOnly&&state.filters.planningStatus==null
            FilterChip(all,{vm.setFilters(AnchorageFilterState())},label={Text(tr("All","全部"))})
            FilterChip(state.filters.favoriteOnly,{vm.setFilters(AnchorageFilterState(favoriteOnly=true))},label={Text(tr("Favourites","收藏"))})
            FilterChip(state.filters.visitedOnly,{vm.setFilters(AnchorageFilterState(visitedOnly=true))},label={Text(tr("Visited","去过"))})
            FilterChip(state.filters.planningStatus==AnchoragePlanningStatus.WANT_TO_VISIT,{vm.setFilters(AnchorageFilterState(planningStatus=AnchoragePlanningStatus.WANT_TO_VISIT))},label={Text(tr("Planned","想去"))})
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.displayMode == AnchorageDisplayMode.MAP,
                onClick = { vm.setMode(AnchorageDisplayMode.MAP) },
                label={Text(tr("Map", "地图"),maxLines=1)},
                modifier = Modifier.weight(1f).testTag("anchorage_mode_map"),
            )
            FilterChip(
                selected = state.displayMode == AnchorageDisplayMode.LIST,
                onClick = { vm.setMode(AnchorageDisplayMode.LIST) },
                label={Text(tr("List", "列表"),maxLines=1)},
                modifier = Modifier.weight(1f).testTag("anchorage_mode_list"),
            )
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.displayMode == AnchorageDisplayMode.MAP && BuildConfig.MAPS_CONFIGURED ->
                AnchorageMap(state, vm, Modifier.weight(1f))
            else -> AnchoragePlaceList(state.visiblePlaces, vm::selectPlace, Modifier.weight(1f))
        }
    }
    SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    state.selectedPlace?.let { bundle ->
        // One selection opens one complete surface. The former preview ->
        // details double-hop duplicated the same Place and made Approach look
        // as though it started in a hidden second page.
        AnchoragePlaceDetailDialog(
            bundle,state.collections,{vm.selectPlace(null)},
            {spotId->vm.selectPlace(null);approachSpot(spotId)},
            openGoogleMaps,vm::shareSpot,{photoPicker.launch("image/*")},vm::deletePhoto,
            vm::photoPath,vm::setFavorite,vm::setPlanning,vm::toggleCollection,vm::cycleProtection,
            {showEditor=true},{confirmDelete=true},
        )
    }
    if(showEditor)state.selectedPlace?.let{bundle->AnchorageEditorDialog(bundle,{showEditor=false}){name,description,notes,spotId,spotName,approachNotes,spotNotes,depth,rode,radius->vm.updateSelected(name,description,notes,spotId,spotName,approachNotes,spotNotes,depth,rode,radius);showEditor=false}}
    if(confirmDelete)AlertDialog(
        onDismissRequest={confirmDelete=false},
        title={Text(tr("Delete saved anchorage?","删除收藏锚地？"))},
        text={Text(tr("Its saved positions, local notes, photos and visit links will be removed from this device. Anchor Watch reports remain in History.","本机保存的位置、备注、照片和到访关联将被移除；锚警报告仍保留在历史中。"))},
        confirmButton={Button({confirmDelete=false;vm.deleteSelected()},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),modifier=Modifier.testTag("confirm_delete_saved_anchorage")){Text(tr("Delete","删除"))}},
        dismissButton={TextButton({confirmDelete=false}){Text(tr("Cancel","取消"))}},
    )
    if (showFilters) AnchorageFiltersSheet(state.filters, { showFilters = false }) {
        vm.setFilters(it); showFilters = false
    }
    if (showRegions) RegionSelectorDialog(
        state.regions, state.selectedRegionId,state.allPlaces.any{it.primaryRegionId==null},
        { showRegions = false }, { vm.setRegion(it); showRegions = false },
    )
    if (showQrScanner) Dialog(
        onDismissRequest = { showQrScanner = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AnchorageQrScannerScreen(
            onClose = { showQrScanner = false },
            onSave = { value -> showQrScanner = false; vm.importLegacyQr(value) },
            onSaveV2 = { value -> showQrScanner = false; vm.importV2Qr(value) },
        )
    }
    planningPoint?.let{point->PlannedAnchorageDialog(point,{vm.cancelPlan()},vm::savePlan)}
}

@OptIn(FlowPreview::class)
@Composable
private fun AnchorageMap(state: AnchorageLibraryUiState, vm: AnchorageLibraryViewModel, modifier: Modifier) {
    val first = state.visiblePlaces.firstOrNull() ?: state.allPlaces.firstOrNull()
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            first?.let { LatLng(it.centerLatitude, it.centerLongitude) } ?: LatLng(-36.2, 175.4),
            if (first == null) 6f else 10f,
        )
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.position }.debounce(250).collectLatest { position ->
            val longitudeSpan = 360.0 / 2.0.pow(position.zoom.toDouble())
            val latitudeSpan = (longitudeSpan * cos(Math.toRadians(position.target.latitude)).coerceAtLeast(.2)).coerceAtMost(170.0)
            vm.updateViewport(
                AnchorageViewport(
                    (position.target.latitude - latitudeSpan / 2).coerceAtLeast(-90.0),
                    normalize(position.target.longitude - longitudeSpan / 2),
                    (position.target.latitude + latitudeSpan / 2).coerceAtMost(90.0),
                    normalize(position.target.longitude + longitudeSpan / 2),
                ),
            )
        }
    }
    val models = state.visiblePlaces.map { place ->
        AnchorageMapPlace(
            place.id, place.centerLatitude, place.centerLongitude, place.displayName, place.favorite,
            runCatching { AnchoragePlanningStatus.valueOf(place.planningStatus) }.getOrDefault(AnchoragePlanningStatus.NONE),
            place.visitCountCached + place.legacyVisitCount, 0,
        )
    }
    val aggregates = AnchorageVisualClusterer.aggregate(models, camera.position.zoom)
    GoogleMap(
        modifier.fillMaxWidth().testTag("anchorage_library_map"),
        cameraPositionState = camera,
        uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false),
        onMapLongClick = { vm.planAt(it.latitude,it.longitude) },
    ) {
        aggregates.forEach { aggregate ->
            if (aggregate.count > 1) {
                Marker(
                    remember(aggregate.latitude, aggregate.longitude) { MarkerState(LatLng(aggregate.latitude, aggregate.longitude)) },
                    title = tr("${aggregate.count} saved anchorages", "${aggregate.count} 个收藏锚地"),
                    snippet = tr("Zoom in to separate them", "放大以分别查看"),
                )
            } else {
                state.visiblePlaces.firstOrNull { it.id == aggregate.placeIds.single() }?.let { place ->
                    Marker(
                        remember(place.id, place.centerLatitude, place.centerLongitude) { MarkerState(LatLng(place.centerLatitude, place.centerLongitude)) },
                        title = place.displayName,
                        snippet = tr("Tap for details and actions", "点击查看详情和操作"),
                        onClick = { vm.selectPlace(place.id); true },
                    )
                }
            }
        }
        if (camera.position.zoom >= 13f) state.selectedPlace?.spots?.forEach { spot ->
            Marker(
                remember(spot.id, spot.latitude, spot.longitude) { MarkerState(LatLng(spot.latitude, spot.longitude)) },
                title = spot.name,
                snippet = tr("Saved anchoring position", "收藏的锚泊位置"),
            )
        }
    }
}

@Composable
private fun AnchoragePlaceList(values: List<AnchoragePlaceEntity>, open: (Long) -> Unit, modifier: Modifier) {
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(tr("No saved anchorages match this view.", "当前视图没有匹配的收藏锚地。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values, key = { it.id }) { place ->
            Card(
                onClick = { open(place.id) },
                modifier = Modifier.fillMaxWidth().testTag("anchorage_place_${place.id}"),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(place.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (place.favorite) Icon(Icons.Default.Favorite, tr("Favorite", "收藏"), tint = MaterialTheme.colorScheme.primary)
                    }
                    val visits=place.visitCountCached+place.legacyVisitCount
                    Text(if(visits>0)tr("Visited $visits times","到访 $visits 次")else libraryPlanningLabel(place.planningStatus),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if (place.personalNotes.isNotBlank()) Text(place.personalNotes, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AnchorageFiltersSheet(value: AnchorageFilterState, dismiss: () -> Unit, save: (AnchorageFilterState) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Anchorage filters", "锚地筛选")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(draft.favoriteOnly, { draft = draft.copy(favoriteOnly = !draft.favoriteOnly) }, label = { Text(tr("Favorites", "收藏")) })
                FilterChip(draft.visitedOnly, { draft = draft.copy(visitedOnly = !draft.visitedOnly) }, label = { Text(tr("Visited", "去过")) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AnchoragePlanningStatus.WANT_TO_VISIT, AnchoragePlanningStatus.BACKUP, AnchoragePlanningStatus.AVOID).forEach { status ->
                        FilterChip(
                            draft.planningStatus == status,
                            { draft = draft.copy(planningStatus = if (draft.planningStatus == status) null else status) },
                            label = { Text(when(status){AnchoragePlanningStatus.WANT_TO_VISIT->tr("Planned","想去");AnchoragePlanningStatus.BACKUP->tr("Alternative","备选");else->tr("Avoid","避开")}) },
                        )
                    }
                }
            }
        },
        confirmButton = { Button({ save(draft) }) { Text(tr("Apply", "应用")) } },
        dismissButton = { TextButton(dismiss) { Text(tr("Cancel", "取消")) } },
    )
}

@Composable
private fun RegionSelectorDialog(regions: List<AnchorageRegionEntity>, selected: Long?, hasUnassigned:Boolean, dismiss: () -> Unit, select: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Filter by region", "按区域筛选")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(tr("All regions", "全部区域")) },
                        leadingContent = { RadioButton(selected == null, { select(null) }) },
                    )
                }
                items(regions,key={it.id}) { region ->
                    ListItem(
                        headlineContent = { Text(region.displayName) },
                        supportingContent={Text(if(region.official)tr("Official map region", "官方地图区域")else tr("Personal region", "个人区域"))},
                        leadingContent = { RadioButton(selected == region.id, { select(region.id) }) },
                        modifier=Modifier.clickable{select(region.id)},
                    )
                }
                if(hasUnassigned)item{
                    ListItem(
                        headlineContent={Text(tr("Unassigned places","未归类地点"))},
                        supportingContent={Text(tr("Saved or imported places that have not been matched to a region yet.","尚未匹配到区域的收藏或导入地点。"))},
                        leadingContent={RadioButton(selected==UNASSIGNED_REGION_ID,{select(UNASSIGNED_REGION_ID)})},
                        modifier=Modifier.clickable{select(UNASSIGNED_REGION_ID)}.testTag("anchorage_region_unassigned"),
                    )
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text(tr("Close", "关闭")) } },
    )
}

private fun normalize(value: Double): Double = ((value + 540) % 360) - 180

@Composable
private fun PlannedAnchorageDialog(point:Pair<Double,Double>,dismiss:()->Unit,save:(String,String,String)->Unit){
    var name by remember(point){mutableStateOf("")};var spot by remember(point){mutableStateOf("")};var notes by remember(point){mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Save a planned anchorage","保存规划锚地"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("This is a chart reference for a future visit, not a verified safe anchoring position.","这是以后到访的海图参考，不代表已经验证安全的锚泊位置。"),color=MaterialTheme.colorScheme.tertiary);Text("%.5f, %.5f".format(point.first,point.second));OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(tr("Anchorage name *","锚地名称 *"))});OutlinedTextField(spot,{spot=it},Modifier.fillMaxWidth(),label={Text(tr("Position name","位置名称"))});OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text(tr("Planning notes","规划备注"))})}},confirmButton={Button({save(name,spot,notes)},enabled=name.isNotBlank()){Text(tr("Save anchorage","保存锚地"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable private fun libraryPlanningLabel(raw:String)=when(runCatching{AnchoragePlanningStatus.valueOf(raw)}.getOrDefault(AnchoragePlanningStatus.NONE)){AnchoragePlanningStatus.WANT_TO_VISIT,AnchoragePlanningStatus.PLANNED->tr("Planned","想去");AnchoragePlanningStatus.BACKUP->tr("Alternative","备选");AnchoragePlanningStatus.AVOID->tr("Avoid","避开");AnchoragePlanningStatus.COMMON->tr("Regular anchorage","常用锚地");AnchoragePlanningStatus.ARCHIVED->tr("Archived","已归档");AnchoragePlanningStatus.NONE->tr("Saved reference","收藏参考")}
