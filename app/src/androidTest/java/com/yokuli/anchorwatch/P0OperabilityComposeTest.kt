package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.yokuli.anchorwatch.data.vessel.VesselDataSettings
import com.yokuli.anchorwatch.data.trip.TripReplayData
import com.yokuli.anchorwatch.domain.vessel.CandidateValidity
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.vessel.VesselPosition
import com.yokuli.anchorwatch.domain.vessel.VesselSourceCandidate
import com.yokuli.anchorwatch.domain.vessel.VesselSourceClass
import com.yokuli.anchorwatch.domain.vessel.VesselSourceIdentity
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselSourceType
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class P0OperabilityComposeTest{
    @get:Rule val compose=createComposeRule()

    @Test fun setAnchorPrimaryActionShowsAVisibleBlockerInsteadOfDoingNothing(){
        compose.setContent{
            YokuliTheme{WatchPanel(
                state=MainUiState(settingsReady=false),boatHeading=null,arm={},adjust={},manageVesselData={},resetCentreAnalysis={},conditionUpdate={_ ->},resetWindBaseline={},viewNearby={_ ->},
                nearbyActions=SavedAnchorageCardActions({_ ->},{_ ->},{_ ->}),pause={},resume={},lift={},openAnchorMap={},recalculateCentre={},reconnectNmea={},openNmea={},openGpsSources={},
            )}
        }
        compose.onNodeWithTag("set_anchor_primary").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Anchor Watch cannot start yet").assertIsDisplayed()
        compose.onNodeWithText("Safety settings are still loading. Wait for the Ready status, then try again.").assertIsDisplayed()
    }

    @Test fun rootWorkspaceSwipeDoesNotChangeSection(){
        compose.setContent{
            MaterialTheme{
                val pager=rememberPagerState(initialPage=0,pageCount={2})
                ClickOnlyWorkspacePager(pager,Modifier.fillMaxSize().testTag("root_pager")){page->Text("Root page $page",Modifier.testTag("root_page_$page"))}
            }
        }
        compose.onNodeWithTag("root_page_0").assertIsDisplayed()
        compose.onNodeWithTag("root_pager").performTouchInput{swipeLeft()}
        compose.onNodeWithTag("root_page_0").assertIsDisplayed()
    }

    @Test fun anchorageApproachReplacesCurrentWorkspaceInsteadOfBecomingItsChild(){
        compose.setContent{
            YokuliTheme{
                AppDestinationLayer(
                    fullscreenDestination=true,
                    workspace={Text("Current workspace",Modifier.testTag("current_workspace"))},
                    fullscreenHost={Text("Full-screen approach",Modifier.testTag("approach_destination"))},
                )
            }
        }
        compose.onNodeWithTag("approach_destination").assertIsDisplayed()
        compose.onNodeWithTag("current_workspace").assertDoesNotExist()
    }

    @Test fun tripPhoneGpsChoiceRequiresAndUsesAnEligiblePhoneCandidate(){
        val source=VesselSourceIdentity(id="phone:gnss",sourceType=VesselSourceType.PHONE_SENSOR,displayName="Android GNSS")
        val candidate=VesselSourceCandidate(
            metric=VesselMetricId.POSITION,value=VesselPosition(-36.8485,174.7633),source=source,
            sourceClass=VesselSourceClass.PHONE_GNSS,receivedElapsedRealtime=1_000L,validity=CandidateValidity.ELIGIBLE,
        )
        var startedWith:VesselSourcePreference?=null
        compose.setContent{
            YokuliTheme{TripStartDialog(
                state=MainUiState(vesselSettings=VesselDataSettings(positionPreference=VesselSourcePreference.PHONE),vesselData=VesselDataSnapshot(candidates=mapOf(VesselMetricId.POSITION to listOf(candidate)))),
                dismiss={},start={_,_,preference->startedWith=preference},
            )}
        }
        compose.onNodeWithTag("trip_position_phone").performClick()
        compose.onNodeWithTag("start_trip_recording").assertIsEnabled().performClick()
        assertEquals(VesselSourcePreference.PHONE,startedWith)
    }

    @Test fun tripPhoneGpsChoiceIsBlockedWhenAndroidGnssHasNoEligibleFix(){
        compose.setContent{YokuliTheme{TripStartDialog(
            state=MainUiState(vesselSettings=VesselDataSettings(positionPreference=VesselSourcePreference.PHONE)),dismiss={},start={_,_,_->},
        )}}
        compose.onNodeWithTag("start_trip_recording").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for Android GNSS").assertIsDisplayed()
    }

    @Test fun completedTripWithoutCoordinatesShowsAnExplicitRouteReason(){
        compose.setContent{YokuliTheme{TripReportRouteMap(TripReplayData(emptyList(),emptyList()))}}
        compose.onNodeWithTag("trip_route_empty").assertIsDisplayed()
        compose.onNodeWithText("No usable coordinates were recorded for this trip. Instrument samples and events remain available below.").assertIsDisplayed()
    }
}
