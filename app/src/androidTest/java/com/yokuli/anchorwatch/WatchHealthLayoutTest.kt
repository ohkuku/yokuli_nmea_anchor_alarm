package com.yokuli.anchorwatch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.yokuli.anchorwatch.domain.safety.SafetyCheck
import com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus
import com.yokuli.anchorwatch.domain.safety.WatchSafetyReport
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import org.junit.Rule
import org.junit.Test

class WatchHealthLayoutTest{
    @get:Rule val compose=createComposeRule()

    @Test fun everyContinuousHealthCheckRemainsReachableInTheScrollableSheet(){
        val checks=listOf("gps_fresh","gps_accuracy","nmea","notifications","full_screen_alarm","alarm_sound","background","battery_optimization","battery","network","storage","sonar").mapIndexed{index,id->
            SafetyCheck(id,if(index%3==0)SafetyCheckStatus.WARNING else SafetyCheckStatus.OK,"Long safety check title $index","A complete status explanation that must wrap instead of being clipped on a narrow screen.","A complete risk explanation must also remain readable.")
        }
        compose.setContent{YokuliTheme{WatchHealthSheet(WatchSafetyReport(checks)) {}}}

        compose.onNodeWithTag("watch_health_sheet").assertIsDisplayed()
        compose.onNodeWithTag("watch_health_sonar").performScrollTo().assertIsDisplayed()
    }
}
