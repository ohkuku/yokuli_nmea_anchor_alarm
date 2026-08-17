package com.yokuli.anchorwatch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterDistance
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import org.junit.Rule
import org.junit.Test

class NearbyAnchorageCardTest {
    @get:Rule val compose=createComposeRule()

    private fun saved(id:Long,name:String)=SavedAnchorageEntity(
        id=id,
        name=name,
        latitude=-36.8+id*.0001,
        longitude=175.1+id*.0001,
        createdAt=id,
        updatedAt=id,
        preferredAlarmRadiusMeters=50.0,
        typicalWaterDepthMeters=6.0,
        typicalRodeLengthMeters=40.0,
        seabedType="SAND",
        rating=4,
        notes="Sheltered in settled weather",
    )

    private fun candidate(saved:SavedAnchorageEntity,distance:Double)=AnchorageClusterDistance(
        cluster=AnchorageCluster(
            id="saved:${saved.id}",
            centerLatitude=saved.latitude,
            centerLongitude=saved.longitude,
            radiusMeters=50.0,
            savedAnchorageIds=listOf(saved.id),
            displayName=saved.name,
            savedPointCount=1,
            minDepthMeters=saved.typicalWaterDepthMeters,
            maxDepthMeters=saved.typicalWaterDepthMeters,
            minRodeMeters=saved.typicalRodeLengthMeters,
            maxRodeMeters=saved.typicalRodeLengthMeters,
            minAlarmRadiusMeters=saved.preferredAlarmRadiusMeters,
            maxAlarmRadiusMeters=saved.preferredAlarmRadiusMeters,
            lastVisitedAt=null,
            radiusEstimated=false,
        ),
        distanceToCentreMeters=distance+50.0,
        distanceToAreaMeters=distance,
    )

    private val actions=SavedAnchorageCardActions({}, {}, {})

    @Test fun oneNearbySavedAnchorageIsAlreadyTheRealCardWithoutAnotherDetailsStep(){
        val saved=saved(1,"Little Bay")
        compose.setContent{YokuliTheme{
            NearbyAnchorageCard(listOf(candidate(saved,120.0)),listOf(saved),actions,{})
        }}

        compose.onNodeWithTag("saved_anchorage_card_1").assertIsDisplayed()
        compose.onNodeWithTag("saved_anchorage_approach_1").assertExists()
        compose.onNodeWithTag("saved_anchorage_maps_1").assertExists()
        compose.onNodeWithTag("saved_anchorage_share_1").assertExists()
        compose.onAllNodesWithTag("nearby_anchorage_list").assertCountEquals(0)
    }

    @Test fun multipleNearbySavedAnchoragesOpenOneListOfCompleteCards(){
        val first=saved(1,"Little Bay")
        val second=saved(2,"Pohutukawa Cove")
        val candidates=listOf(candidate(first,120.0),candidate(second,240.0))
        compose.setContent{YokuliTheme{
            var selectedIds by remember{mutableStateOf<List<Long>?>(null)}
            NearbyAnchorageCard(candidates,listOf(first,second),actions,{selectedIds=it})
            selectedIds?.let{ids->AnchorageListDialog(
                members=ids.mapNotNull{id->listOf(first,second).firstOrNull{it.id==id}},
                dismiss={selectedIds=null},
                actions=actions,
            )}
        }}

        compose.onAllNodesWithTag("saved_anchorage_card_1").assertCountEquals(0)
        compose.onNodeWithTag("nearby_anchorage_list").performClick()
        compose.onNodeWithTag("anchorage_list_item_1").assertExists()
        compose.onNodeWithTag("anchorage_list_item_2").assertExists()
        compose.onNodeWithTag("saved_anchorage_approach_1").assertExists()
        compose.onNodeWithTag("saved_anchorage_maps_2").assertExists()
        compose.onNodeWithTag("saved_anchorage_share_2").assertExists()
        compose.onAllNodesWithTag("open_anchorage_1").assertCountEquals(0)
    }
}
