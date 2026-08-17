package com.yokuli.anchorwatch

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityLayoutTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Before fun prepare(){MapRuntimePolicy.renderGoogleEngine=false;shell("settings put system font_scale 2.0")}
    @After fun restore(){shell("settings put system font_scale 1.0");MapRuntimePolicy.renderGoogleEngine=true}

    @Test fun twoHundredPercentTextAndLandscapeKeepPrimaryNavigationReachable(){
        compose.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitForIdle()
        compose.onNodeWithText("Data").performClick();compose.onNodeWithText("Personal sonar mapping").assertExists()
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Alarm & notifications").assertExists()
    }

    private fun shell(command:String){InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()}
}
