package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.anchorage.gis.AnchorageRegionCandidateService
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import com.yokuli.anchorwatch.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnchorageSaveFlowState(
    val step: Int = 0,
    val sessionId: Long? = null,
    val draft: AnchorageSaveDraft? = null,
    val regionCandidates: List<AnchorageRegionCandidate> = emptyList(),
    val selectedRegionIndex: Int? = null,
    val placeMatches: List<AnchoragePlaceMatchResult> = emptyList(),
    val selectedPlaceId: Long? = null,
    val spotMatches: List<Pair<AnchorageSpotEntity, AnchorageSpotMatchResult>> = emptyList(),
    val selectedSpotId: Long? = null,
    val spotDecisionMade:Boolean = true,
    val placeName: String = "",
    val spotName: String = "Main spot",
    val placeNotes: String = "",
    val visitNotes: String = "",
    val favorite: Boolean = true,
    val assessmentEnabled:Boolean=false,
    val wouldReturn:AnchorageWouldReturn=AnchorageWouldReturn.UNKNOWN,
    val holding:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val comfort:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val shoreAccess:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val crowding:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val quietness:AnchorageAssessmentRating=AnchorageAssessmentRating.UNKNOWN,
    val assessmentNotes:String="",
    val resolving: Boolean = false,
    val saving: Boolean = false,
    val complete: Boolean = false,
    val result: AnchorageSaveResult? = null,
    val undone: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AnchorageSaveFlowViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val database: AppDatabase,
    private val regions: AnchorageRegionCandidateService,
    private val saver: AnchorageSaveRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(AnchorageSaveFlowState())
    val state = mutable.asStateFlow()

    fun begin(session: AnchorSessionEntity) {
        if (mutable.value.sessionId == session.id) return
        val draft = AnchorageSaveDraftFactory.fromSession(session)
        mutable.value = AnchorageSaveFlowState(
            sessionId = session.id,
            draft = draft,
            placeName = savedState["placeName"] ?: "",
            spotName = savedState["spotName"] ?: "Main spot",
            resolving = true,
        )
        viewModelScope.launch {
            val candidates = regions.resolve(draft.proposedLatitude, draft.proposedLongitude)
            val matches = saver.nearbyPlaceMatches(draft, null, null).take(8)
            mutable.value = mutable.value.copy(
                regionCandidates = candidates.take(8),
                selectedRegionIndex = candidates.indices.firstOrNull(),
                placeMatches = matches,
                resolving = false,
            )
        }
    }

    fun name(value: String) { savedState["placeName"] = value; mutable.value = mutable.value.copy(placeName = value) }
    fun spotName(value: String) { savedState["spotName"] = value; mutable.value = mutable.value.copy(spotName = value) }
    fun notes(value: String) { mutable.value = mutable.value.copy(placeNotes = value) }
    fun visitNotes(value: String) { mutable.value = mutable.value.copy(visitNotes = value) }
    fun favorite(value: Boolean) { mutable.value = mutable.value.copy(favorite = value) }
    fun assessmentEnabled(value:Boolean){mutable.value=mutable.value.copy(assessmentEnabled=value)}
    fun wouldReturn(value:AnchorageWouldReturn){mutable.value=mutable.value.copy(wouldReturn=value)}
    fun holding(value:AnchorageAssessmentRating){mutable.value=mutable.value.copy(holding=value)}
    fun comfort(value:AnchorageAssessmentRating){mutable.value=mutable.value.copy(comfort=value)}
    fun shoreAccess(value:AnchorageAssessmentRating){mutable.value=mutable.value.copy(shoreAccess=value)}
    fun crowding(value:AnchorageAssessmentRating){mutable.value=mutable.value.copy(crowding=value)}
    fun quietness(value:AnchorageAssessmentRating){mutable.value=mutable.value.copy(quietness=value)}
    fun assessmentNotes(value:String){mutable.value=mutable.value.copy(assessmentNotes=value.take(20_000))}
    fun selectRegion(index: Int?) { mutable.value = mutable.value.copy(selectedRegionIndex = index) }
    fun selectNewPlace() { mutable.value = mutable.value.copy(selectedPlaceId = null, selectedSpotId = null, spotMatches = emptyList(),spotDecisionMade=true) }
    fun selectPlace(id: Long) {
        val draft = mutable.value.draft ?: return
        mutable.value = mutable.value.copy(selectedPlaceId = id, selectedSpotId = null,spotDecisionMade=false,placeName="")
        viewModelScope.launch { val matches=saver.nearbySpotMatches(draft, id);mutable.value = mutable.value.copy(spotMatches = matches,spotDecisionMade=matches.isEmpty()) }
    }
    fun selectSpot(id: Long?) { mutable.value = mutable.value.copy(selectedSpotId = id,spotDecisionMade=true) }
    fun back(){mutable.value=mutable.value.copy(step=(mutable.value.step-1).coerceAtLeast(0),error=null)}
    fun next(){
        val current=mutable.value
        val matched=current.selectedPlaceId?.let{id->current.placeMatches.firstOrNull{it.place.id==id}}
        if(current.step==0&&current.placeName.trim().ifBlank{matched?.place?.name.orEmpty()}.isBlank()){
            mutable.value=current.copy(error="Place name is required.");return
        }
        if(current.step==1&&!current.spotDecisionMade){mutable.value=current.copy(error="Choose an existing Spot or explicitly create a distinct Spot.");return}
        mutable.value=current.copy(step=(current.step+1).coerceAtMost(2),error=null)
    }

    fun save() {
        val current = mutable.value
        val draft = current.draft ?: return
        val matchedPlace = current.selectedPlaceId?.let { id -> current.placeMatches.firstOrNull { it.place.id == id } }
        val displayName = current.placeName.trim().ifBlank { matchedPlace?.place?.name.orEmpty() }
        if (displayName.isBlank()) { mutable.value = current.copy(error = "Place name is required."); return }
        mutable.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                val region = current.selectedRegionIndex?.let(current.regionCandidates::getOrNull)
                val regionId = region?.externalId?.let { database.anchorageRegionDao().byExternalId(region.provider, it)?.id }
                saver.save(
                    AnchorageSaveRequest(
                        draft,
                        AnchorageSavePlaceInput(
                            existingPlaceId = current.selectedPlaceId,
                            displayName = displayName,
                            primaryRegionId = regionId,
                            personalNotes = current.placeNotes,
                            favorite = current.favorite,
                        ),
                        AnchorageSaveSpotInput(
                            existingSpotId = current.selectedSpotId,
                            name = current.spotName.trim().ifBlank { "Main spot" },
                        ),
                        current.visitNotes,
                        current.takeIf{it.assessmentEnabled}?.let{AnchoragePersonalAssessmentInput(it.wouldReturn,it.holding,it.comfort,it.shoreAccess,it.crowding,it.quietness,it.assessmentNotes)},
                    ),
                )
            }.onSuccess { result ->
                savedState.remove<String>("placeName"); savedState.remove<String>("spotName")
                mutable.value = mutable.value.copy(saving = false, result = result)
            }.onFailure { error -> mutable.value = mutable.value.copy(saving = false, error = error.message ?: "Save failed") }
        }
    }
    fun undo()=viewModelScope.launch{
        val current=mutable.value;val result=current.result?:return@launch
        runCatching{saver.undo(result,current.sessionId)}
            .onSuccess{mutable.value=current.copy(result=null,undone=true,error=null)}
            .onFailure{mutable.value=current.copy(error=it.message?:"Undo failed")}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnchorageSaveFlow(
    session: AnchorSessionEntity,
    dismiss: () -> Unit,
    complete: () -> Unit,
    vm: AnchorageSaveFlowViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val savedResult=state.result
    LaunchedEffect(session.id) { vm.begin(session) }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("anchorage_save_page"),color=MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(tr("Save to anchorage library","保存到锚地库"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);if(state.result==null)Text(tr("Step ${state.step+1} of 3","第 ${state.step+1} 步，共 3 步"),color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(dismiss){Text(tr("Close","关闭"))}}
            if(savedResult!=null){
                Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp,Alignment.CenterVertically),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                    Text(tr("Anchorage saved","锚地已保存"),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                    Text(tr("Saved as Place #${savedResult.placeId} · Spot #${savedResult.spotId}. An immutable Visit was added from this Anchor session.","已保存为地点 #${savedResult.placeId} · 锚点 #${savedResult.spotId}，并从本次锚泊会话自动创建不可变访问记录。"),style=MaterialTheme.typography.bodyLarge)
                    Button(complete,Modifier.fillMaxWidth().testTag("view_saved_anchorage")){Text(tr("Done · view in Anchorage Library","完成 · 前往锚地库查看"))}
                    OutlinedButton(vm::undo,Modifier.fillMaxWidth().testTag("undo_anchorage_save")){Text(tr("Undo this save","撤销本次保存"))}
                }
            }else{
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                item {
                    Text(
                        tr("The session position is frozen for this draft; live movement cannot change it.", "本次草稿中的会话坐标已冻结，实时移动不会改变它。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.draft?.let { draft -> item { Text("%.5f, %.5f".format(draft.proposedLatitude, draft.proposedLongitude), fontWeight = FontWeight.Medium) } }
                if(state.step==0){
                    item { Text(tr("Choose or create the Place", "选择或创建地点"), fontWeight = FontWeight.Bold) }
                    if (state.resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(state.regionCandidates.indices.toList()) { index -> val candidate=state.regionCandidates[index];FilterChip(state.selectedRegionIndex==index,{vm.selectRegion(index)},label={Text("${candidate.displayName} · ${candidate.featureType.name.lowercase()}")}) }
                    item { FilterChip(state.selectedRegionIndex == null, { vm.selectRegion(null) }, label = { Text(tr("Leave region unclassified", "暂不归类区域")) }) }
                    items(state.placeMatches){match->FilterChip(state.selectedPlaceId==match.place.id,{vm.selectPlace(match.place.id)},label={Text("${match.place.name} · ${match.distanceMeters.toInt()} m")})}
                    item { FilterChip(state.selectedPlaceId==null,vm::selectNewPlace,label={Text(tr("Create a new Place","创建新地点"))}) }
                    item { OutlinedTextField(state.placeName,vm::name,Modifier.fillMaxWidth().testTag("gis_place_name"),label={Text(tr("Place name *","地点名称 *"))},supportingText={if(state.selectedPlaceId!=null)Text(tr("Leave blank to keep the selected Place name.","留空保留所选地点名称。"))}) }
                }else if(state.step==1){
                    item { Text(tr("Match or create the exact Spot", "匹配或创建具体锚点"), fontWeight = FontWeight.Bold) }
                    items(state.spotMatches){(spot,match)->FilterChip(state.selectedSpotId==spot.id,{vm.selectSpot(spot.id)},label={Text("${spot.name} · ${match.distanceMeters.toInt()} m · ${match.match.name.lowercase().replace('_',' ')}")})}
                    item { FilterChip(state.selectedSpotId==null,{vm.selectSpot(null)},label={Text(tr("Create a distinct Spot in this Place","在该地点创建独立锚点"))}) }
                    item { OutlinedTextField(state.spotName,vm::spotName,Modifier.fillMaxWidth(),label={Text(tr("Spot name","锚点名称"))}) }
                    item { Text(tr("Distance is evidence, not an automatic duplicate rule. You decide whether uncertainty overlaps an existing Spot.","距离只是判断证据，不会自动判重；是否与已有锚点的误差范围重叠由你决定。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary) }
                }else{
                    item { Text(tr("Review automatic Visit snapshot", "确认自动生成的访问快照"), fontWeight = FontWeight.Bold) }
                    state.draft?.let{draft->item{Text(listOfNotNull(draft.depthMeters?.let{"%.1f m depth".format(it)},draft.rodeMeters?.let{"${it.toInt()} m rode"},draft.alarmRadiusMeters?.let{"${it.toInt()} m alarm radius"}).joinToString(" · ").ifBlank{"—"});Text(tr("These values come from the completed Anchor session and are not re-entered here.","这些值来自已完成的锚泊会话，无需再次填写。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    item { OutlinedTextField(state.placeNotes,vm::notes,Modifier.fillMaxWidth(),label={Text(tr("Place notes","地点备注"))}) }
                    item { OutlinedTextField(state.visitNotes,vm::visitNotes,Modifier.fillMaxWidth(),label={Text(tr("This visit notes","本次访问备注"))}) }
                    item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(tr("Favorite","收藏"));Switch(state.favorite,vm::favorite)} }
                    item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Add a personal assessment","添加个人评价"),fontWeight=FontWeight.SemiBold);Text(tr("Optional and editable later. It does not change safety protection sectors.","可选，之后仍可编辑；不会改变安全遮蔽方向。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(state.assessmentEnabled,vm::assessmentEnabled)} }
                    if(state.assessmentEnabled){
                        item{AssessmentChoiceGrid(tr("Would you return?","还会再来吗？"),AnchorageWouldReturn.entries,state.wouldReturn,vm::wouldReturn){value->when(value){AnchorageWouldReturn.YES->tr("Yes","会");AnchorageWouldReturn.MAYBE->tr("Maybe","也许");AnchorageWouldReturn.NO->tr("No","不会");AnchorageWouldReturn.UNKNOWN->tr("Unknown","未评价")}}}
                        item{AssessmentChoiceGrid(tr("Holding","抓底感受"),AnchorageAssessmentRating.entries,state.holding,vm::holding){assessmentRatingLabel(it)}}
                        item{AssessmentChoiceGrid(tr("Comfort","舒适度"),AnchorageAssessmentRating.entries,state.comfort,vm::comfort){assessmentRatingLabel(it)}}
                        item{AssessmentChoiceGrid(tr("Shore access","上岸"),AnchorageAssessmentRating.entries,state.shoreAccess,vm::shoreAccess){assessmentContextLabel(it,tr("Convenient","方便"),tr("Average","一般"),tr("Difficult","困难"))}}
                        item{AssessmentChoiceGrid(tr("Crowding","拥挤程度"),AnchorageAssessmentRating.entries,state.crowding,vm::crowding){assessmentContextLabel(it,tr("Uncrowded","不拥挤"),tr("Moderate","一般"),tr("Crowded","拥挤"))}}
                        item{AssessmentChoiceGrid(tr("Quietness","安静程度"),AnchorageAssessmentRating.entries,state.quietness,vm::quietness){assessmentContextLabel(it,tr("Quiet","安静"),tr("Mixed","一般"),tr("Noisy","嘈杂"))}}
                        item{OutlinedTextField(state.assessmentNotes,vm::assessmentNotes,Modifier.fillMaxWidth(),label={Text(tr("Assessment notes","评价备注"))},minLines=2)}
                    }
                }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(state.step>0)OutlinedButton(vm::back,Modifier.weight(1f)){Text(tr("Back","上一步"))}else Spacer(Modifier.weight(1f))
                if(state.step<2)Button(vm::next,Modifier.weight(1f)){Text(tr("Next","下一步"))}else Button(vm::save,enabled=!state.saving&&state.draft!=null,modifier=Modifier.weight(1f).testTag("confirm_gis_anchorage_save")){Text(if(state.saving)tr("Saving…","保存中…")else tr("Save","保存"))}
            }
            }
          }
        }
    }
}

@Composable
private fun <T> AssessmentChoiceGrid(title:String,values:List<T>,selected:T,select:(T)->Unit,label:@Composable (T)->String){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(title,fontWeight=FontWeight.SemiBold)
        values.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{value->FilterChip(selected==value,{select(value)},label={Text(label(value),maxLines=1)},modifier=Modifier.weight(1f))};if(row.size==1)Spacer(Modifier.weight(1f))}}
    }
}

@Composable private fun assessmentRatingLabel(value:AnchorageAssessmentRating)=when(value){AnchorageAssessmentRating.GOOD->tr("Good","好");AnchorageAssessmentRating.AVERAGE->tr("Average","一般");AnchorageAssessmentRating.POOR->tr("Poor","差");AnchorageAssessmentRating.UNKNOWN->tr("Not rated","未评价")}
@Composable private fun assessmentContextLabel(value:AnchorageAssessmentRating,good:String,average:String,poor:String)=when(value){AnchorageAssessmentRating.GOOD->good;AnchorageAssessmentRating.AVERAGE->average;AnchorageAssessmentRating.POOR->poor;AnchorageAssessmentRating.UNKNOWN->tr("Unknown","未知")}
