package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity
import com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import com.yokuli.anchorwatch.tr
import java.text.DateFormat
import java.util.Date

/** One selection opens one complete action surface; Place/Spot/Visit stay database terms. */
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
    cycleProtection:(AnchorageProtectionMedium,AnchorageCompassSector)->Unit,
    edit:()->Unit,
    editPosition:(Long)->Unit,
    delete:()->Unit,
) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Scaffold(
            modifier=Modifier.fillMaxSize().testTag("saved_anchorage_detail"),
            topBar={TopAppBar(
                title={Column{Text(bundle.place.displayName,maxLines=1);val region=bundle.regionPath.joinToString(" · "){it.displayName};if(region.isNotBlank())Text(region,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}},
                navigationIcon={IconButton(dismiss,Modifier.testTag("close_saved_anchorage_detail")){Icon(Icons.Default.Close,tr("Close","关闭"))}},
                actions={
                    IconButton({setFavorite(!bundle.place.favorite)}){Icon(if(bundle.place.favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,tr("Favourite","收藏"))}
                    IconButton(edit,Modifier.testTag("edit_saved_anchorage")){Icon(Icons.Default.Edit,tr("Edit saved anchorage","编辑收藏锚地"))}
                    IconButton(delete,Modifier.testTag("delete_saved_anchorage")){Icon(Icons.Default.DeleteOutline,tr("Delete saved anchorage","删除收藏锚地"),tint=MaterialTheme.colorScheme.error)}
                },
            )},
        ){padding->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding=PaddingValues(horizontal=16.dp,vertical=14.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp),
            ){
                item{
                    SafetyReferenceNotice()
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        StatusChip(planningLabel(bundle.place.planningStatus))
                        if(bundle.place.favorite)StatusChip(tr("Favourite","收藏"))
                        val visits=bundle.place.visitCountCached+bundle.place.legacyVisitCount
                        if(visits>0)StatusChip(tr("Visited $visits times","到访 $visits 次"))
                    }
                }
                item{SectionTitle(tr("Anchoring positions","锚泊位置"),tr("Coordinates and parameters you saved for this anchorage.","你为这个锚地保存的坐标和参数。"))}
                items(bundle.spots.size,key={bundle.spots[it].id}){index->AnchoringPositionCard(bundle.spots[index],approach,openMap,shareSpot,editPosition)}
                if(bundle.spots.isEmpty())item{EmptySection(tr("No anchoring position is saved yet.","还没有保存锚泊位置。"))}
                item{SavedAnchorageNotes(bundle)}
                item{PlanningAndCollections(bundle,allCollections,setPlanning,toggleCollection)}
                item{ProtectionSection(bundle,cycleProtection)}
                item{VisitSection(bundle)}
                item{PhotoSection(bundle,addPhoto,deletePhoto,photoPath)}
            }
        }
    }
}

@Composable
private fun SafetyReferenceNotice(){
    Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.medium){
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Icon(Icons.Default.Info,null,tint=MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f)){Text(tr("Personal reference","个人收藏参考"),fontWeight=FontWeight.SemiBold);Text(tr("Conditions change. Check current depth, traffic, weather and surroundings before anchoring.","环境会变化。下锚前请重新确认当前水深、交通、天气和周围环境。"),style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable
private fun AnchoringPositionCard(spot:AnchorageSpotEntity,approach:(Long)->Unit,openMap:(Double,Double)->Unit,share:(Long)->Unit,edit:(Long)->Unit){
    ElevatedCard(Modifier.fillMaxWidth().testTag("anchorage_position_${spot.id}")){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Row(Modifier.fillMaxWidth()){
                Text(if(spot.name.equals("Main spot",true))tr("Primary anchoring position","主要锚泊位置")else spot.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                IconButton({edit(spot.id)},Modifier.testTag("edit_anchorage_position_${spot.id}")){Icon(Icons.Default.Edit,tr("Edit this position","编辑这个位置"))}
            }
            Text("%.5f, %.5f".format(spot.latitude,spot.longitude),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val facts=buildList{
                spot.typicalWaterDepthMeters?.let{add(tr("Depth %.1f m".format(it),"水深 %.1f 米".format(it)))}
                spot.typicalRodeLengthMeters?.let{add(tr("Rode ${it.toInt()} m","锚链 / 锚缆 ${it.toInt()} 米"))}
                spot.preferredAlarmRadiusMeters?.let{add(tr("Saved range ${it.toInt()} m","收藏范围 ${it.toInt()} 米"))}
                spot.coordinateUncertaintyMeters?.let{add(tr("Position ±${it.toInt()} m","位置 ±${it.toInt()} 米"))}
            }
            if(facts.isNotEmpty())Text(facts.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
            if(spot.approachNotes.isNotBlank())Text(spot.approachNotes,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button({approach(spot.id)},Modifier.weight(1f).testTag("anchorage_spot_approach_${spot.id}")){Icon(Icons.Default.Navigation,null);Spacer(Modifier.width(5.dp));Text(tr("Approach","接近"))}
                OutlinedButton({openMap(spot.latitude,spot.longitude)},Modifier.weight(1f).testTag("anchorage_spot_maps_${spot.id}")){Icon(Icons.Default.Map,null);Spacer(Modifier.width(5.dp));Text(tr("Maps","地图"))}
            }
            OutlinedButton({share(spot.id)},Modifier.fillMaxWidth().testTag("anchorage_spot_share_${spot.id}")){Icon(Icons.Default.QrCode2,null);Spacer(Modifier.width(7.dp));Text(tr("Share location & details","分享位置和资料"))}
        }
    }
}

@Composable
private fun SavedAnchorageNotes(bundle:AnchoragePlaceBundle){
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        SectionTitle(tr("About this anchorage","锚地介绍"),null)
        Text(bundle.place.description.ifBlank{tr("No description saved.","尚未保存介绍。")})
        if(bundle.place.personalNotes.isNotBlank()){Text(tr("Personal notes","个人备注"),fontWeight=FontWeight.SemiBold);Text(bundle.place.personalNotes)}
        bundle.spots.mapNotNull{it.personalNotes.ifBlank{null}}.distinct().forEach{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable
private fun PlanningAndCollections(bundle:AnchoragePlaceBundle,collections:List<AnchorageCollectionEntity>,setPlanning:(AnchoragePlanningStatus)->Unit,toggleCollection:(Long)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        SectionTitle(tr("My list","我的分类"),tr("These labels stay on this device.","这些分类只保存在本机。"))
        listOf(
                AnchoragePlanningStatus.NONE to tr("Saved","已收藏"),
                AnchoragePlanningStatus.WANT_TO_VISIT to tr("Planned","想去"),
                AnchoragePlanningStatus.BACKUP to tr("Alternative","备选"),
                AnchoragePlanningStatus.AVOID to tr("Avoid","避开"),
            ).chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){pair.forEach{(status,label)->FilterChip(bundle.place.planningStatus==status.name,{setPlanning(status)},label={Text(label)},modifier=Modifier.weight(1f))}}}
        if(collections.isNotEmpty())collections.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){pair.forEach{collection->FilterChip(bundle.collections.any{it.id==collection.id},{toggleCollection(collection.id)},label={Text(collection.name)},modifier=Modifier.weight(1f))};if(pair.size==1)Spacer(Modifier.weight(1f))}}
    }
}

@Composable
private fun ProtectionSection(bundle:AnchoragePlaceBundle,cycle:(AnchorageProtectionMedium,AnchorageCompassSector)->Unit){
    var expanded by remember(bundle.place.id){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        TextButton({expanded=!expanded},Modifier.fillMaxWidth()){
            Icon(Icons.Default.Air,null);Spacer(Modifier.width(7.dp));Text(tr("My shelter observations","我的遮蔽观察"),Modifier.weight(1f));Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
        }
        if(expanded){
            Text(tr("Optional personal notes—not a safety rating. Tap a direction to cycle its observed shelter.","可选的个人观察，不是安全评级。点击方向可切换记录的遮蔽程度。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            AnchorageProtectionMedium.entries.forEach{medium->
                Text(protectionMediumLabel(medium),fontWeight=FontWeight.SemiBold)
                AnchorageCompassSector.entries.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){pair.forEach{sector->
                    val rating=bundle.protection.firstOrNull{it.medium==medium.name&&it.sector==sector.name}?.rating?:AnchorageProtectionRating.UNKNOWN.name
                    AssistChip({cycle(medium,sector)},label={Text("${sectorLabel(sector)} · ${ratingLabel(rating)}")},modifier=Modifier.weight(1f))
                }}}
            }
        }
    }
}

@Composable
private fun VisitSection(bundle:AnchoragePlaceBundle){
    var expanded by remember(bundle.place.id){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        TextButton({expanded=!expanded},Modifier.fillMaxWidth().testTag("anchorage_visits_toggle")){
            Icon(Icons.Default.History,null);Spacer(Modifier.width(7.dp));Text(tr("Visits (${bundle.visits.size})","到访记录（${bundle.visits.size}）"),Modifier.weight(1f));Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
        }
        if(expanded&&bundle.visits.isEmpty())EmptySection(tr("No recorded visits yet.","还没有到访记录。"))
        if(expanded)bundle.visits.forEach{visit->Card{Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(DateFormat.getDateTimeInstance().format(Date(visit.startedAt)),fontWeight=FontWeight.SemiBold)
            Text(buildList{visit.waterDepthMeters?.let{add(tr("Depth %.1f m".format(it),"水深 %.1f 米".format(it)))};visit.rodeLengthMeters?.let{add(tr("Rode ${it.toInt()} m","锚链 / 锚缆 ${it.toInt()} 米"))};visit.maxExcursionMeters?.let{add(tr("Max movement ${it.toInt()} m","最大移动 ${it.toInt()} 米"))};add(tr("${visit.alarmCount} alarms","${visit.alarmCount} 次警报"))}.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
            if(visit.userNotes.isNotBlank())Text(visit.userNotes,style=MaterialTheme.typography.bodySmall)
        }}}
    }
}

@Composable
private fun PhotoSection(bundle:AnchoragePlaceBundle,add:()->Unit,delete:(AnchoragePhotoEntity)->Unit,path:(AnchoragePhotoEntity,Boolean)->String){
    var expanded by remember(bundle.place.id){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        TextButton({expanded=!expanded},Modifier.fillMaxWidth()){
            Icon(Icons.Default.PhotoLibrary,null);Spacer(Modifier.width(7.dp));Text(tr("Local photos (${bundle.photos.size})","本地照片（${bundle.photos.size}）"),Modifier.weight(1f));Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
        }
        if(expanded){
            Button(add,Modifier.fillMaxWidth().testTag("anchorage_add_photo")){Text(tr("Add local photo","添加本地照片"))}
            Text(tr("Photos stay in app-private storage and are shared only through a full backup.","照片保存在应用私有空间，只会通过完整备份分享。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            bundle.photos.forEach{photo->Card{Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                val bitmap=remember(photo.id){android.graphics.BitmapFactory.decodeFile(path(photo,true))}
                bitmap?.let{Image(it.asImageBitmap(),photo.caption.ifBlank{tr("Anchorage photo","锚地照片")},Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Crop)}
                if(photo.caption.isNotBlank())Text(photo.caption)
                TextButton({delete(photo)},Modifier.fillMaxWidth()){Text(tr("Delete photo","删除照片"),color=MaterialTheme.colorScheme.error)}
            }}}
        }
    }
}

@Composable
internal fun AnchorageEditorDialog(bundle:AnchoragePlaceBundle,preferredSpotId:Long?,dismiss:()->Unit,save:(String,String,String,Long?,String,String,String,Double?,Double?,Double?)->Unit){
    val spot=bundle.spots.firstOrNull{it.id==preferredSpotId}?:bundle.spots.firstOrNull()
    var name by remember(bundle.place.id){mutableStateOf(bundle.place.displayName)}
    var description by remember(bundle.place.id){mutableStateOf(bundle.place.description)}
    var notes by remember(bundle.place.id){mutableStateOf(bundle.place.personalNotes)}
    var spotName by remember(spot?.id){mutableStateOf(spot?.name.orEmpty())}
    var approach by remember(spot?.id){mutableStateOf(spot?.approachNotes.orEmpty())}
    var spotNotes by remember(spot?.id){mutableStateOf(spot?.personalNotes.orEmpty())}
    var depth by remember(spot?.id){mutableStateOf(spot?.typicalWaterDepthMeters?.toString().orEmpty())}
    var rode by remember(spot?.id){mutableStateOf(spot?.typicalRodeLengthMeters?.toString().orEmpty())}
    var radius by remember(spot?.id){mutableStateOf(spot?.preferredAlarmRadiusMeters?.toString().orEmpty())}
    val numbersValid=listOf(depth,rode,radius).all{it.isBlank()||it.toDoubleOrNull()?.let{value->value>0.0}==true}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Edit saved anchorage","编辑收藏锚地"))},text={LazyColumn(Modifier.heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(tr("Name *","名称 *"))},singleLine=true)}
        item{OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),label={Text(tr("Description","介绍"))},minLines=2)}
        item{OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text(tr("Personal notes","个人备注"))},minLines=2)}
        spot?.let{item{HorizontalDivider();Text(tr("Primary anchoring position","主要锚泊位置"),fontWeight=FontWeight.SemiBold)};item{OutlinedTextField(spotName,{spotName=it},Modifier.fillMaxWidth(),label={Text(tr("Position name *","位置名称 *"))},singleLine=true)};item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){NumberField(depth,{depth=it},tr("Depth m","水深 米"),Modifier.weight(1f));NumberField(rode,{rode=it},tr("Rode m","锚链 米"),Modifier.weight(1f));NumberField(radius,{radius=it},tr("Range m","范围 米"),Modifier.weight(1f))}};item{OutlinedTextField(approach,{approach=it},Modifier.fillMaxWidth(),label={Text(tr("Approach notes","接近备注"))})};item{OutlinedTextField(spotNotes,{spotNotes=it},Modifier.fillMaxWidth(),label={Text(tr("Position notes","位置备注"))})}}
    }},confirmButton={Button({save(name,description,notes,spot?.id,spotName,approach,spotNotes,depth.toDoubleOrNull(),rode.toDoubleOrNull(),radius.toDoubleOrNull())},enabled=name.isNotBlank()&&(spot==null||spotName.isNotBlank())&&numbersValid,modifier=Modifier.testTag("save_anchorage_edits")){Text(tr("Save changes","保存修改"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable private fun NumberField(value:String,change:(String)->Unit,label:String,modifier:Modifier)=OutlinedTextField(value,change,modifier,label={Text(label)},singleLine=true,isError=value.isNotBlank()&&value.toDoubleOrNull()?.let{it>0.0}!=true)
@Composable private fun SectionTitle(title:String,supporting:String?){Column{Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);supporting?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun StatusChip(text:String)=Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.extraLarge){Text(text,Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)}
@Composable private fun EmptySection(text:String)=Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Text(text,Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}

@Composable private fun planningLabel(raw:String)=when(runCatching{AnchoragePlanningStatus.valueOf(raw)}.getOrDefault(AnchoragePlanningStatus.NONE)){AnchoragePlanningStatus.NONE->tr("Saved","已收藏");AnchoragePlanningStatus.WANT_TO_VISIT,AnchoragePlanningStatus.PLANNED->tr("Planned","想去");AnchoragePlanningStatus.COMMON->tr("Regular","常用");AnchoragePlanningStatus.BACKUP->tr("Alternative","备选");AnchoragePlanningStatus.AVOID->tr("Avoid","避开");AnchoragePlanningStatus.ARCHIVED->tr("Archived","已归档")}
@Composable private fun protectionMediumLabel(value:AnchorageProtectionMedium)=when(value){AnchorageProtectionMedium.WIND->tr("Wind shelter","挡风");AnchorageProtectionMedium.SWELL->tr("Swell shelter","挡涌浪")}
@Composable private fun sectorLabel(value:AnchorageCompassSector)=when(value){AnchorageCompassSector.N->tr("N","北");AnchorageCompassSector.NE->tr("NE","东北");AnchorageCompassSector.E->tr("E","东");AnchorageCompassSector.SE->tr("SE","东南");AnchorageCompassSector.S->tr("S","南");AnchorageCompassSector.SW->tr("SW","西南");AnchorageCompassSector.W->tr("W","西");AnchorageCompassSector.NW->tr("NW","西北")}
@Composable private fun ratingLabel(raw:String)=when(runCatching{AnchorageProtectionRating.valueOf(raw)}.getOrDefault(AnchorageProtectionRating.UNKNOWN)){AnchorageProtectionRating.UNKNOWN->tr("Unknown","未知");AnchorageProtectionRating.EXPOSED->tr("Exposed","无遮蔽");AnchorageProtectionRating.PARTIAL->tr("Partial","部分遮蔽");AnchorageProtectionRating.GOOD->tr("Good","良好")}
