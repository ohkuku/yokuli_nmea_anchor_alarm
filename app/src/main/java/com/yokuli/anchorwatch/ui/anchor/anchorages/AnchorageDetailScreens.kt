package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.yokuli.anchorwatch.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnchoragePlaceDetailDialog(
    bundle: AnchoragePlaceBundle,
    allCollections:List<AnchorageCollectionEntity>,
    dismiss: () -> Unit,
    approach: (Long) -> Unit,
    openMap: (Double, Double) -> Unit,
    shareSpot: (Long) -> Unit,
    addPhoto: () -> Unit,
    deletePhoto: (AnchoragePhotoEntity) -> Unit,
    photoPath: (AnchoragePhotoEntity,Boolean) -> String,
    setFavorite:(Boolean)->Unit,
    setPlanning:(AnchoragePlanningStatus)->Unit,
    toggleCollection:(Long)->Unit,
    setProtection:(AnchorageProtectionMedium,AnchorageCompassSector,AnchorageProtectionRating,AnchorageInformationSource,String)->Unit,
) {
    // A saved-place selection opens directly on its actionable Spot card(s).
    // Overview remains one tap away, but there is no duplicate preview page.
    var tab by remember(bundle.place.id) { mutableIntStateOf(1) }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("anchorage_place_detail_page"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(bundle.place.displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                        Text(
                            bundle.regionPath.joinToString(" · ") { it.displayName }.ifBlank { tr("Unclassified region", "未归类区域") },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(dismiss,Modifier.testTag("close_anchorage_place_detail")){Text(tr("Close","关闭"))}
                }
                PrimaryTabRow(tab) {
                    listOf(tr("Overview", "概览"), tr("Spots", "锚点"), tr("Visits", "访问"), tr("Photos", "照片"),tr("Notes", "备注")).forEachIndexed { index, label ->
                        Tab(tab == index, { tab = index }, text = { Text(label) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> AnchorageOverview(bundle,allCollections,setFavorite,setPlanning,toggleCollection,setProtection)
                        1 -> AnchorageSpots(bundle, approach, openMap,shareSpot)
                        2 -> AnchorageVisits(bundle)
                        3 -> AnchoragePhotos(bundle,addPhoto,deletePhoto,photoPath)
                        else -> AnchorageNotes(bundle)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchoragePhotos(bundle:AnchoragePlaceBundle,add:()->Unit,delete:(AnchoragePhotoEntity)->Unit,path:(AnchoragePhotoEntity,Boolean)->String){
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)){
        item{Button(add,Modifier.fillMaxWidth().testTag("anchorage_add_photo")){Text(tr("Add local photo","添加本地照片"))};Text(tr("Photos stay in app-private storage and are included only in a full backup.","照片保存在应用私有空间，只会包含在完整备份中。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(bundle.photos,key={it.id}){photo->
            Card{Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                val bitmap=remember(photo.id){android.graphics.BitmapFactory.decodeFile(path(photo,true))}
                bitmap?.let{Image(it.asImageBitmap(),photo.caption.ifBlank{tr("Anchorage photo","锚地照片")},Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Crop)}
                if(photo.caption.isNotBlank())Text(photo.caption)
                TextButton({delete(photo)},Modifier.fillMaxWidth()){Text(tr("Delete photo","删除照片"),color=MaterialTheme.colorScheme.error)}
            }}
        }
    }
}

@Composable
private fun AnchorageOverview(bundle: AnchoragePlaceBundle,collections:List<AnchorageCollectionEntity>,setFavorite:(Boolean)->Unit,setPlanning:(AnchoragePlanningStatus)->Unit,toggleCollection:(Long)->Unit,setProtection:(AnchorageProtectionMedium,AnchorageCompassSector,AnchorageProtectionRating,AnchorageInformationSource,String)->Unit) {
    var selectedMedium by remember(bundle.place.id){mutableStateOf(AnchorageProtectionMedium.WIND)}
    var editingSector by remember(bundle.place.id){mutableStateOf<AnchorageCompassSector?>(null)}
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(tr("Personal observations, not a safety rating.", "个人观测记录，不是安全评级。"), color = MaterialTheme.colorScheme.tertiary) }
        item { DetailLine(tr("Visits", "访问次数"), (bundle.place.visitCountCached + bundle.place.legacyVisitCount).toString()) }
        item { DetailLine(tr("Planning", "规划状态"), bundle.place.planningStatus) }
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(tr("Favorite","收藏"));Switch(bundle.place.favorite,setFavorite)}}
        item{Text(tr("Planning status","规划状态"),fontWeight=FontWeight.SemiBold);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf(AnchoragePlanningStatus.NONE,AnchoragePlanningStatus.WANT_TO_VISIT,AnchoragePlanningStatus.BACKUP,AnchoragePlanningStatus.AVOID).forEach{status->FilterChip(bundle.place.planningStatus==status.name,{setPlanning(status)},label={Text(status.name.lowercase().replace('_',' '))})}}}
        if(collections.isNotEmpty())item{Text(tr("Collections","合集"),fontWeight=FontWeight.SemiBold);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){collections.forEach{collection->FilterChip(bundle.collections.any{it.id==collection.id},{toggleCollection(collection.id)},label={Text(collection.name)})}}}
        item{Text(tr("Personal natural protection","个人记录的天然遮蔽"),fontWeight=FontWeight.SemiBold);Text(tr("Wind and swell are separate observations. Unknown never means exposed. Select a compass sector to review before saving.","风和涌浪分别记录；未知不等于暴露。选择方向后确认再保存。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)}
        item{SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
            AnchorageProtectionMedium.entries.forEachIndexed{index,medium->SegmentedButton(selectedMedium==medium,{selectedMedium=medium},SegmentedButtonDefaults.itemShape(index,2),label={Text(if(medium==AnchorageProtectionMedium.WIND)tr("Wind protection","风向遮蔽")else tr("Swell protection","涌浪遮蔽"))})}
        }}
        item{Text(protectionSummary(bundle.protection,selectedMedium),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{AnchorageProtectionCompass(selectedMedium,bundle.protection){editingSector=it}}
        bundle.rating?.let { rating -> item { DetailLine(tr("Preference", "个人偏好"), rating.overallPreference) } }
        if (bundle.facilities.isNotEmpty()) item { Text(bundle.facilities.joinToString { it.type }, style = MaterialTheme.typography.bodySmall) }
    }
    editingSector?.let{sector->
        val existing=bundle.protection.firstOrNull{it.medium==selectedMedium.name&&it.sector==sector.name}
        ProtectionSectorEditor(selectedMedium,sector,existing,{editingSector=null}){rating,source,notes->setProtection(selectedMedium,sector,rating,source,notes);editingSector=null}
    }
}

@Composable
private fun AnchorageProtectionCompass(medium:AnchorageProtectionMedium,values:List<AnchorageProtectionSectorEntity>,edit:(AnchorageCompassSector)->Unit){
    val cells=listOf(AnchorageCompassSector.NW,AnchorageCompassSector.N,AnchorageCompassSector.NE,AnchorageCompassSector.W,null,AnchorageCompassSector.E,AnchorageCompassSector.SW,AnchorageCompassSector.S,AnchorageCompassSector.SE)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
        cells.chunked(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            row.forEach{sector->Box(Modifier.weight(1f).aspectRatio(1.18f),contentAlignment=Alignment.Center){
                if(sector==null){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Anchor,null,tint=MaterialTheme.colorScheme.primary);Text(if(medium==AnchorageProtectionMedium.WIND)tr("WIND","风")else tr("SWELL","浪"),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}}
                else{
                    val stored=values.firstOrNull{it.medium==medium.name&&it.sector==sector.name}
                    val rating=runCatching{AnchorageProtectionRating.valueOf(stored?.rating?:"UNKNOWN")}.getOrDefault(AnchorageProtectionRating.UNKNOWN)
                    ProtectionSectorCell(medium,sector,rating){edit(sector)}
                }
            }}
        }}
    }
}

@Composable
private fun ProtectionSectorCell(medium:AnchorageProtectionMedium,sector:AnchorageCompassSector,rating:AnchorageProtectionRating,open:()->Unit){
    val label=protectionRatingLabel(rating)
    val container=when(rating){AnchorageProtectionRating.GOOD->Color(0xFFD7F1DF);AnchorageProtectionRating.PARTIAL->Color(0xFFFFEDB8);AnchorageProtectionRating.EXPOSED->MaterialTheme.colorScheme.errorContainer;AnchorageProtectionRating.UNKNOWN->MaterialTheme.colorScheme.surfaceVariant}
    val icon=when(rating){AnchorageProtectionRating.GOOD->Icons.Default.Shield;AnchorageProtectionRating.PARTIAL->Icons.Default.WarningAmber;AnchorageProtectionRating.EXPOSED->Icons.Default.RemoveCircleOutline;AnchorageProtectionRating.UNKNOWN->Icons.Default.HelpOutline}
    val description=(if(medium==AnchorageProtectionMedium.WIND)tr("Wind","风")else tr("Swell","涌浪"))+" ${sector.name}: $label"
    ElevatedCard(Modifier.fillMaxSize().testTag("protection_${medium.name}_${sector.name}").semantics{contentDescription=description}.clickable(onClick=open),colors=CardDefaults.elevatedCardColors(containerColor=container)){
        Column(Modifier.fillMaxSize().padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(sector.name,fontWeight=FontWeight.Bold);Icon(icon,null,Modifier.size(18.dp));Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionSectorEditor(medium:AnchorageProtectionMedium,sector:AnchorageCompassSector,existing:AnchorageProtectionSectorEntity?,dismiss:()->Unit,save:(AnchorageProtectionRating,AnchorageInformationSource,String)->Unit){
    var rating by remember(medium,sector,existing?.updatedAt){mutableStateOf(runCatching{AnchorageProtectionRating.valueOf(existing?.rating?:"UNKNOWN")}.getOrDefault(AnchorageProtectionRating.UNKNOWN))}
    var source by remember(medium,sector,existing?.updatedAt){mutableStateOf(runCatching{AnchorageInformationSource.valueOf(existing?.source?:"USER")}.getOrDefault(AnchorageInformationSource.USER))}
    var notes by remember(medium,sector,existing?.updatedAt){mutableStateOf(existing?.notes.orEmpty())}
    ModalBottomSheet(onDismissRequest=dismiss){
        LazyColumn(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{Text("${sector.name} · "+if(medium==AnchorageProtectionMedium.WIND)tr("wind protection","风向遮蔽")else tr("swell protection","涌浪遮蔽"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
            item{Text(tr("Protection *","遮蔽情况 *"),fontWeight=FontWeight.SemiBold)}
            items(AnchorageProtectionRating.entries){value->itemChoice(protectionRatingLabel(value),rating==value){rating=value}}
            item{Text(tr("Evidence *","依据 *"),fontWeight=FontWeight.SemiBold)}
            items(listOf(AnchorageInformationSource.USER,AnchorageInformationSource.OBSERVED,AnchorageInformationSource.GIS_ASSISTED)){value->itemChoice(protectionSourceLabel(value),source==value){source=value}}
            item{OutlinedTextField(notes,{notes=it.take(2_000)},Modifier.fillMaxWidth(),label={Text(tr("Notes (optional)","备注（可选）"))},minLines=2)}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(dismiss,Modifier.weight(1f)){Text(tr("Cancel","取消"))};Button({save(rating,source,notes)},Modifier.weight(1f).testTag("save_protection_sector")){Text(tr("Save","保存"))}}}
        }
    }
}

@Composable private fun itemChoice(label:String,selected:Boolean,select:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=select).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,select);Text(label)}}
@Composable private fun protectionRatingLabel(value:AnchorageProtectionRating)=when(value){AnchorageProtectionRating.GOOD->tr("Good","良好");AnchorageProtectionRating.PARTIAL->tr("Partial","部分");AnchorageProtectionRating.EXPOSED->tr("Exposed","暴露");AnchorageProtectionRating.UNKNOWN->tr("Unknown","未知")}
@Composable private fun protectionSourceLabel(value:AnchorageInformationSource)=when(value){AnchorageInformationSource.USER->tr("My judgement","我的判断");AnchorageInformationSource.OBSERVED->tr("Historical visits","历史访问");AnchorageInformationSource.GIS_ASSISTED->tr("GIS-assisted","GIS 辅助");AnchorageInformationSource.IMPORTED->tr("Imported","导入")}
@Composable private fun protectionSummary(values:List<AnchorageProtectionSectorEntity>,medium:AnchorageProtectionMedium):String{
    fun directions(rating:String)=values.filter{it.medium==medium.name&&it.rating==rating}.joinToString(", "){it.sector}
    val parts=listOfNotNull(directions("GOOD").takeIf{it.isNotBlank()}?.let{tr("$it good","$it 良好")},directions("PARTIAL").takeIf{it.isNotBlank()}?.let{tr("$it partial","$it 部分")},directions("EXPOSED").takeIf{it.isNotBlank()}?.let{tr("$it exposed","$it 暴露")})
    return parts.joinToString(" · ").ifBlank{tr("No personal protection observations yet.","尚无个人遮蔽观测。")}
}

@Composable
private fun AnchorageSpots(bundle: AnchoragePlaceBundle, approach: (Long) -> Unit, openMap: (Double, Double) -> Unit,share:(Long)->Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(bundle.spots, key = { it.id }) { spot ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if(spot.name.equals("Main spot",true))tr("Primary anchoring spot","主要锚泊点")else spot.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            spot.typicalWaterDepthMeters?.let { "%.1f m depth".format(it) },
                            spot.typicalRodeLengthMeters?.let { "${it.toInt()} m rode" },
                            spot.preferredAlarmRadiusMeters?.let { "${it.toInt()} m radius" },
                        ).joinToString(" · ").ifBlank { "—" },
                    )
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ approach(spot.id) },Modifier.weight(1f).testTag("anchorage_spot_approach_${spot.id}")) { Text(tr("Approach", "接近")) }
                        OutlinedButton({ openMap(spot.latitude, spot.longitude) },Modifier.weight(1f)) { Text(tr("Google Maps", "Google 地图")) }
                    }
                    TextButton({share(spot.id)},Modifier.fillMaxWidth()){Text(tr("Share this Spot as QR v2","以 QR v2 分享此锚点"))}
                }
            }
        }
    }
}

@Composable
private fun AnchorageVisits(bundle: AnchoragePlaceBundle) {
    if (bundle.visits.isEmpty()) {
        Box(Modifier.padding(12.dp)) { Text(tr("No recorded visits yet.", "还没有访问记录。")) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(bundle.visits, key = { it.id }) { visit ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(visit.startedAt)), fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            visit.waterDepthMeters?.let { "%.1f m depth".format(it) },
                            visit.rodeLengthMeters?.let { "${it.toInt()} m rode" },
                            visit.maxExcursionMeters?.let { "${it.toInt()} m max excursion" },
                            "${visit.alarmCount} alarms",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (visit.userNotes.isNotBlank()) Text(visit.userNotes, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (visit.anchorSessionId == null) tr("Saved visit snapshot; source session unavailable.", "访问摘要已保留；原会话不可用。")
                        else tr("Linked to immutable Anchor report #${visit.anchorSessionId}.", "已关联不可变锚泊报告 #${visit.anchorSessionId}。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnchorageNotes(bundle: AnchoragePlaceBundle) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(bundle.place.description.ifBlank { tr("No objective description saved.", "尚未保存客观描述。") }) }
        item { Spacer(Modifier.height(12.dp)); Text(bundle.place.personalNotes.ifBlank { tr("No personal notes saved.", "尚未保存个人备注。") }) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
