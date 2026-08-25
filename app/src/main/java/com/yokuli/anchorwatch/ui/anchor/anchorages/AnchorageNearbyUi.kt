package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yokuli.anchorwatch.data.anchorage.AnchorageLibraryRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageExperienceRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageNearbyPlace
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceEvent
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceState
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyPolicy
import com.yokuli.anchorwatch.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AnchorageNearbyViewModel @Inject constructor(
    private val library: AnchorageLibraryRepository,
    private val experience: AnchorageExperienceRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow<List<AnchorageNearbyPlace>>(emptyList())
    val visible = mutable.asStateFlow()
    private var job: Job? = null

    fun update(latitude: Double?, longitude: Double?, enabled: Boolean) {
        job?.cancel()
        if (!enabled || latitude == null || longitude == null) { mutable.value = emptyList(); return }
        job = viewModelScope.launch {
            delay(300)
            val candidates = library.nearby(latitude, longitude, AnchorageNearbyPolicy.REARM_DISTANCE_METERS)
            val known = candidates.mapTo(mutableSetOf()) { it.place.id }
            when(val current=experience.state.value){
                is AnchorageExperienceState.Nearby->{
                    val exited=current.placeIds-known
                    if(exited.isNotEmpty())experience.dispatch(AnchorageExperienceEvent.RearmZoneExited(exited))
                }
                is AnchorageExperienceState.DepartureCooldown->{
                    val exited=current.suppressedPlaceIds-known
                    if(exited.isNotEmpty())experience.dispatch(AnchorageExperienceEvent.RearmZoneExited(exited))
                }
                else->Unit
            }
            val inside=candidates.filter{it.distanceMeters<=AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS}
            when(val current=experience.state.value){
                AnchorageExperienceState.Browsing->if(inside.isNotEmpty())experience.dispatch(AnchorageExperienceEvent.NearbyDetected(experience.nextEpisodeId(),inside.mapTo(linkedSetOf()){it.place.id}))
                is AnchorageExperienceState.Nearby->experience.dispatch(AnchorageExperienceEvent.NearbyDetected(current.episodeId,inside.mapTo(linkedSetOf()){it.place.id}))
                else->Unit
            }
            val visibleIds=(experience.state.value as? AnchorageExperienceState.Nearby)?.placeIds.orEmpty()
            mutable.value=inside.filter{it.place.id in visibleIds}
        }
    }

    fun dismiss() { experience.dispatch(AnchorageExperienceEvent.NearbyCleared); mutable.value = emptyList() }
}

@Composable
internal fun GisNearbyAnchorageCard(
    latitude: Double?,
    longitude: Double?,
    enabled: Boolean,
    approachSpot: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: AnchorageNearbyViewModel = hiltViewModel(),
) {
    val nearby by vm.visible.collectAsState()
    var showList by remember { mutableStateOf(false) }
    LaunchedEffect(latitude, longitude, enabled) { vm.update(latitude, longitude, enabled) }
    if (nearby.isEmpty()) return
    ElevatedCard(modifier.fillMaxWidth().testTag("gis_anchorage_nearby_prompt")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Anchor, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val first = nearby.first()
                    Text(
                        if (nearby.size == 1) first.place.displayName else tr("${nearby.size} saved Places nearby", "附近有 ${nearby.size} 个收藏地点"),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (nearby.size == 1) tr("${first.spots.size} Spots · ${first.distanceMeters.toInt()} m", "${first.spots.size} 个锚点 · ${first.distanceMeters.toInt()} 米")
                        else tr("Choose a Place, then an exact Spot.", "先选择地点，再选择具体锚点。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(vm::dismiss, Modifier.testTag("gis_anchorage_nearby_dismiss")) { Icon(Icons.Default.Close, tr("Dismiss", "忽略")) }
            }
            if (nearby.size == 1 && nearby.single().spots.size == 1) {
                Button({ approachSpot(nearby.single().spots.single().id) }, Modifier.fillMaxWidth()) { Text(tr("Approach exact Spot", "接近具体锚点")) }
            } else {
                Button({ showList = true }, Modifier.fillMaxWidth()) { Text(tr("Choose Place and Spot", "选择地点和锚点")) }
            }
        }
    }
    if (showList) AlertDialog(
        onDismissRequest = { showList = false },
        title = { Text(tr("Saved Places nearby", "附近收藏地点")) },
        confirmButton = { TextButton({ showList = false }) { Text(tr("Close", "关闭")) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(nearby, key = { it.place.id }) { place ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(place.place.displayName, fontWeight = FontWeight.Bold)
                            Text(tr("${place.distanceMeters.toInt()} m to nearest Spot", "距最近锚点 ${place.distanceMeters.toInt()} 米"), style = MaterialTheme.typography.bodySmall)
                            place.spots.forEach { spot ->
                                OutlinedButton(
                                    onClick = { showList = false; approachSpot(spot.id) },
                                    modifier = Modifier.fillMaxWidth().testTag("nearby_spot_${spot.id}"),
                                ) { Text(spot.name) }
                            }
                        }
                    }
                }
            }
        },
    )
}
