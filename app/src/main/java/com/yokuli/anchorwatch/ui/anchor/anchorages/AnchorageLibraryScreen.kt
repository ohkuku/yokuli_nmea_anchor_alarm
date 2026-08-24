package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.clickable
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
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let(vm::importPhoto)}

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader(
            tr("Anchorage library", "锚地库"),
            tr("Your offline Place → Spot → Visit cruising guide.", "你的离线“地点 → 锚点 → 访问”巡航资料库。"),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { showRegions = true },
                modifier = Modifier.weight(1f).testTag("anchorage_region_selector"),
            ) {
                Icon(Icons.Default.Public, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text(
                    when(state.selectedRegionId){
                        null->tr("All regions", "全部区域")
                        UNASSIGNED_REGION_ID->tr("Unassigned places","未归类地点")
                        else->state.regions.firstOrNull{it.id==state.selectedRegionId}?.displayName?:tr("Selected region", "已选区域")
                    },
                    maxLines = 1,
                )
            }
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
            label = { Text(tr("Search places, spots and notes", "搜索地点、锚点和备注")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.query.isNotBlank()) {
                    IconButton({ vm.search("") }) { Icon(Icons.Default.Close, tr("Clear", "清除")) }
                }
            },
        )
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

    state.selectedPlace?.let { bundle ->
        // One selection opens one complete surface. The former preview ->
        // details double-hop duplicated the same Place and made Approach look
        // as though it started in a hidden second page.
        AnchoragePlaceDetailDialog(
            bundle,state.collections,{vm.selectPlace(null)},
            {spotId->vm.selectPlace(null);approachSpot(spotId)},
            openGoogleMaps,vm::shareSpot,{photoPicker.launch("image/*")},vm::deletePhoto,
            vm::photoPath,vm::setFavorite,vm::setPlanning,vm::toggleCollection,vm::cycleProtection,
        )
    }
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
                    title = tr("${aggregate.count} saved places", "${aggregate.count} 个收藏地点"),
                    snippet = tr("Zoom in to separate places", "放大以查看各地点"),
                )
            } else {
                state.visiblePlaces.firstOrNull { it.id == aggregate.placeIds.single() }?.let { place ->
                    Marker(
                        remember(place.id, place.centerLatitude, place.centerLongitude) { MarkerState(LatLng(place.centerLatitude, place.centerLongitude)) },
                        title = place.displayName,
                        snippet = tr("${place.visitCountCached + place.legacyVisitCount} visits · tap for details", "访问 ${place.visitCountCached + place.legacyVisitCount} 次 · 点击查看"),
                        onClick = { vm.selectPlace(place.id); true },
                    )
                }
            }
        }
        if (camera.position.zoom >= 13f) state.selectedPlace?.spots?.forEach { spot ->
            Marker(
                remember(spot.id, spot.latitude, spot.longitude) { MarkerState(LatLng(spot.latitude, spot.longitude)) },
                title = spot.name,
                snippet = tr("Specific anchoring spot", "具体锚点"),
            )
        }
    }
}

@Composable
private fun AnchoragePlaceList(values: List<AnchoragePlaceEntity>, open: (Long) -> Unit, modifier: Modifier) {
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(tr("No saved places in this view.", "当前范围没有收藏地点。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(
                        tr("${place.visitCountCached + place.legacyVisitCount} visits · ${place.verificationStatus.lowercase().replace('_', ' ')}", "访问 ${place.visitCountCached + place.legacyVisitCount} 次 · ${place.verificationStatus}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            label = { Text(status.name.lowercase().replace('_', ' ')) },
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
        title = { Text(tr("Browse region", "浏览区域")) },
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
                        supportingContent={Text("${region.featureType.lowercase().replace('_',' ')} · ${if(region.official)tr("official LINZ", "LINZ 官方")else tr("personal", "个人")}")},
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
    var name by remember(point){mutableStateOf("")};var spot by remember(point){mutableStateOf("Chart reference")};var notes by remember(point){mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Save planned anchorage","保存规划锚地"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Map long-press creates a planning reference, not a verified anchorage.","地图长按创建的是规划参考点，不是已验证锚地。"),color=MaterialTheme.colorScheme.tertiary);Text("%.5f, %.5f".format(point.first,point.second));OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(tr("Place name *","地点名称 *"))});OutlinedTextField(spot,{spot=it},Modifier.fillMaxWidth(),label={Text(tr("Spot name","锚点名称"))});OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text(tr("Planning notes","规划备注"))})}},confirmButton={Button({save(name,spot,notes)},enabled=name.isNotBlank()){Text(tr("Save planned Place","保存规划地点"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}
