package com.yokuli.anchorwatch

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class AnchorSetupValidationTest{
    @get:Rule val compose=createComposeRule()

    @Test fun startRemainsClickableAndExplainsMissingRequiredCoordinate(){
        val now=SystemClock.elapsedRealtime()
        val fix=NavigationFix(-36.8485,174.7633,receivedElapsedRealtime=now,horizontalAccuracyMeters=3.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="TEST_GNSS",valid=true)
        compose.setContent{YokuliTheme{AnchorSetupSheet(MainUiState(systemFix=fix,fix=fix,settingsReady=true),dismiss={}){_,_,_->}}}

        compose.onNodeWithTag("known_manual").performClick()
        compose.onNodeWithTag("start_anchor_watch").performScrollTo().performClick()
        compose.onNodeWithTag("anchor_setup_validation_error").assertExists()
        compose.onNodeWithTag("anchor_coordinates").assertExists()
    }

    @Test fun anchorSetupReportsTheCentralGpsChoiceButDoesNotOfferAnotherPicker(){
        val now=SystemClock.elapsedRealtime()
        val fix=NavigationFix(-36.8485,174.7633,receivedElapsedRealtime=now,horizontalAccuracyMeters=3.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="TEST_GNSS",valid=true)
        var opened=false
        compose.setContent{YokuliTheme{AnchorSetupSheet(MainUiState(systemFix=fix,fix=fix,settingsReady=true),dismiss={},openGpsSettings={opened=true}){_,_,_->}}}

        compose.onNodeWithTag("anchor_position_source_summary").assertExists()
        compose.onNodeWithTag("setup_source_system").assertDoesNotExist()
        compose.onNodeWithTag("setup_source_nmea").assertDoesNotExist()
        compose.onNodeWithTag("anchor_open_gps_sources").performClick()
        assertTrue(opened)
    }
}
