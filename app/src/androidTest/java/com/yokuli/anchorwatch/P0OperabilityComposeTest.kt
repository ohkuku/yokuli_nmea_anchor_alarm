package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.yokuli.anchorwatch.data.vessel.VesselDataSettings
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
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
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataQuality
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselReference
import com.yokuli.anchorwatch.domain.vessel.VesselWindObservation
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun centreLearningProgressIsNotCappedByTheBoundedMapTrail(){
        val active=AnchorSessionEntity(
            startedAt=1L,
            anchorLatitude=-36.8485,
            anchorLongitude=174.7633,
            rodeLengthMeters=40.0,
            waterDepthMeters=8.0,
            bowRollerHeightMeters=1.0,
            gpsAntennaOffsetMeters=0.0,
            expectedSwingRadiusMeters=40.0,
            warningRadiusMeters=65.0,
            alarmRadiusMeters=80.0,
            placementMode="BACKDOWN",
            centerStatus=AnchorCenterStatus.LEARNING.name,
            estimationEpoch=1,
            estimationEpochStartedAt=1L,
        )
        compose.setContent{
            YokuliTheme{WatchPanel(
                state=MainUiState(active=active,activeLearningPointCount=6_125),boatHeading=null,arm={},adjust={},manageVesselData={},resetCentreAnalysis={},conditionUpdate={_ ->},resetWindBaseline={},viewNearby={_ ->},
                nearbyActions=SavedAnchorageCardActions({_ ->},{_ ->},{_ ->}),pause={},resume={},lift={},openAnchorMap={},recalculateCentre={},reconnectNmea={},openNmea={},openGpsSources={},
            )}
        }
        compose.onNodeWithText("-- m / 80 m temporary boundary • 6125 fixes").assertIsDisplayed()
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
        // Give this normally full-screen report fragment a real viewport. A
        // bare sub-composable host can briefly expose no semantics tree while
        // the emulator is still attaching the test window.
        compose.setContent{YokuliTheme{Box(Modifier.fillMaxSize()){TripReportRouteMap(TripReplayData(emptyList(),emptyList()))}}}
        compose.waitUntil(5_000){compose.onAllNodesWithTag("trip_route_empty").fetchSemanticsNodes().size==1}
        // This is a report sub-composable normally hosted by a scrollable
        // screen. createComposeRule's bare host can report the root window as
        // not foreground-visible on some emulator shards even while the Card
        // is fully measured. Require real non-zero layout bounds instead of a
        // host-window visibility heuristic.
        val emptyRoute=compose.onNodeWithTag("trip_route_empty").fetchSemanticsNode()
        assertTrue(emptyRoute.boundsInRoot.width>0f)
        assertTrue(emptyRoute.boundsInRoot.height>0f)
        compose.onNodeWithText("No usable coordinates were recorded for this trip. Instrument samples and events remain available below.").assertExists()
    }

    @Test fun absoluteDirectionInstrumentKeepsTrueAndRelativeWindFramesExplicit(){
        fun value(number:Double,reference:VesselReference)=VesselObservation(value=number,source=VesselDataSource.BOAT_NMEA,quality=VesselDataQuality.GOOD,freshness=VesselDataFreshness.FRESH,reference=reference)
        val data=VesselDataSnapshot(
            headingTrueDegrees=value(47.0,VesselReference.TrueNorth),
            cogTrueDegrees=value(52.0,VesselReference.GroundReferenced),
            sogKnots=value(3.0,VesselReference.GroundReferenced),
            trueWind=VesselWindObservation(directionDegrees=value(118.0,VesselReference.TrueNorth),angleDegrees=value(-32.0,VesselReference.VesselRelative)),
            apparentWind=VesselWindObservation(angleDegrees=value(24.0,VesselReference.VesselRelative)),
        )
        compose.setContent{YokuliTheme{AbsoluteDirectionInstrument(MainUiState(vesselData=data))}}
        compose.onNodeWithTag("absolute_direction_rose").assertIsDisplayed()
        compose.onNodeWithText("HDG 047°T").assertIsDisplayed()
        compose.onNodeWithText("TWD 118°T · FROM").assertIsDisplayed()
        compose.onNodeWithText("AWA 24° S").assertIsDisplayed()
        compose.onNodeWithText("TWA 32° P").assertIsDisplayed()
    }
}
