package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val sessionId: Long? = null,
    val draft: AnchorageSaveDraft? = null,
    val regionCandidates: List<AnchorageRegionCandidate> = emptyList(),
    val selectedRegionIndex: Int? = null,
    val placeMatches: List<AnchoragePlaceMatchResult> = emptyList(),
    val selectedPlaceId: Long? = null,
    val spotMatches: List<Pair<AnchorageSpotEntity, AnchorageSpotMatchResult>> = emptyList(),
    val selectedSpotId: Long? = null,
    val placeName: String = "",
    val spotName: String = "Main spot",
    val placeNotes: String = "",
    val visitNotes: String = "",
    val favorite: Boolean = true,
    val resolving: Boolean = false,
    val saving: Boolean = false,
    val complete: Boolean = false,
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
    fun selectRegion(index: Int?) { mutable.value = mutable.value.copy(selectedRegionIndex = index) }
    fun selectNewPlace() { mutable.value = mutable.value.copy(selectedPlaceId = null, selectedSpotId = null, spotMatches = emptyList()) }
    fun selectPlace(id: Long) {
        val draft = mutable.value.draft ?: return
        mutable.value = mutable.value.copy(selectedPlaceId = id, selectedSpotId = null)
        viewModelScope.launch { mutable.value = mutable.value.copy(spotMatches = saver.nearbySpotMatches(draft, id)) }
    }
    fun selectSpot(id: Long?) { mutable.value = mutable.value.copy(selectedSpotId = id) }

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
                    ),
                )
            }.onSuccess {
                savedState.remove<String>("placeName"); savedState.remove<String>("spotName")
                mutable.value = mutable.value.copy(saving = false, complete = true)
            }.onFailure { error -> mutable.value = mutable.value.copy(saving = false, error = error.message ?: "Save failed") }
        }
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
    LaunchedEffect(session.id) { vm.begin(session) }
    LaunchedEffect(state.complete) { if (state.complete) complete() }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(.95f),
        title = { Text(tr("Save to anchorage library", "保存到锚地库")) },
        confirmButton = {
            Button(vm::save, enabled = !state.saving && state.draft != null, modifier = Modifier.testTag("confirm_gis_anchorage_save")) {
                Text(if (state.saving) tr("Saving…", "保存中…") else tr("Save Place, Spot & Visit", "保存地点、锚点和访问"))
            }
        },
        dismissButton = { TextButton(dismiss) { Text(tr("Cancel", "取消")) } },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 650.dp),
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
                item { Text(tr("1. Confirm region", "1. 确认所在区域"), fontWeight = FontWeight.Bold) }
                if (state.resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(state.regionCandidates.indices.toList()) { index ->
                    val candidate = state.regionCandidates[index]
                    FilterChip(
                        selected = state.selectedRegionIndex == index,
                        onClick = { vm.selectRegion(index) },
                        label = { Text("${candidate.displayName} · ${candidate.featureType.name.lowercase()}") },
                    )
                }
                item { FilterChip(state.selectedRegionIndex == null, { vm.selectRegion(null) }, label = { Text(tr("Leave unclassified", "暂不归类")) }) }
                item { Text(tr("2. Choose a nearby Place or create one", "2. 选择附近地点或新建地点"), fontWeight = FontWeight.Bold) }
                items(state.placeMatches) { match ->
                    FilterChip(
                        selected = state.selectedPlaceId == match.place.id,
                        onClick = { vm.selectPlace(match.place.id) },
                        label = { Text("${match.place.name} · ${match.distanceMeters.toInt()} m") },
                    )
                }
                item { FilterChip(state.selectedPlaceId == null, vm::selectNewPlace, label = { Text(tr("Create a new Place", "创建新的锚地地点")) }) }
                item {
                    OutlinedTextField(
                        state.placeName, vm::name, Modifier.fillMaxWidth().testTag("gis_place_name"),
                        label = { Text(tr("Place name *", "地点名称 *")) },
                        supportingText = { if (state.selectedPlaceId != null) Text(tr("Leave blank to keep the selected Place name.", "留空以保留所选地点名称。")) },
                    )
                }
                if (state.selectedPlaceId != null) {
                    item { Text(tr("3. Match the exact Spot", "3. 匹配具体锚点"), fontWeight = FontWeight.Bold) }
                    items(state.spotMatches) { (spot, match) ->
                        FilterChip(
                            state.selectedSpotId == spot.id, { vm.selectSpot(spot.id) },
                            label = { Text("${spot.name} · ${match.distanceMeters.toInt()} m · ${match.match.name.lowercase().replace('_', ' ')}") },
                        )
                    }
                    item { FilterChip(state.selectedSpotId == null, { vm.selectSpot(null) }, label = { Text(tr("Create a new Spot in this Place", "在该地点中新建锚点")) }) }
                }
                item { OutlinedTextField(state.spotName, vm::spotName, Modifier.fillMaxWidth(), label = { Text(tr("Spot name", "锚点名称")) }) }
                item { OutlinedTextField(state.placeNotes, vm::notes, Modifier.fillMaxWidth(), label = { Text(tr("Place notes", "地点备注")) }) }
                item { OutlinedTextField(state.visitNotes, vm::visitNotes, Modifier.fillMaxWidth(), label = { Text(tr("This visit notes", "本次访问备注")) }) }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("Favorite", "收藏")); Switch(state.favorite, vm::favorite) } }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
    )
}
