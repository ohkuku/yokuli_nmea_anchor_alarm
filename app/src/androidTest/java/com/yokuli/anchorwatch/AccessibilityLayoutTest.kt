package com.yokuli.anchorwatch

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityLayoutTest{
    @get:Rule val compose=createEmptyComposeRule()

    @Before fun prepare(){
        MapRuntimePolicy.renderGoogleEngine=false
        runBlocking{SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).save(AppSettings())}
        shell("pm grant com.yokuli.anchorwatch android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant com.yokuli.anchorwatch android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant com.yokuli.anchorwatch android.permission.POST_NOTIFICATIONS")
        shell("settings put system font_scale 2.0")
    }
    @After fun restore(){shell("settings put system font_scale 1.0");MapRuntimePolicy.renderGoogleEngine=true}

    @Test fun twoHundredPercentTextAndLandscapeKeepPrimaryNavigationReachable(){
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(15_000){runCatching{compose.onAllNodesWithTag("nav_data").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithTag("nav_data").performClick();compose.onNodeWithTag("data_page").assertExists()
            compose.onNodeWithTag("nav_settings").performClick();compose.onNodeWithTag("settings_list").assertExists()
        }
    }

    private fun shell(command:String){InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()}
}
