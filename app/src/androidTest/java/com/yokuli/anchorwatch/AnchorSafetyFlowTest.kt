package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.di.AnchorWatchEntryPoint
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorSafetyFlowTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var dao: AnchorDao
    private lateinit var preferences: SettingsRepository
    private lateinit var navigation: NavigationRepository

    @Before fun prepare() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AnchorWatchEntryPoint::class.java)
        dao = entry.dao(); preferences = entry.preferences(); navigation = entry.navigation()
        context.stopService(Intent(context, AnchorForegroundService::class.java))
        delay(250)
        navigation.disconnectAll()
        dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
        preferences.save(AppSettings(gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 2))
    }

    @After fun cleanup() = runBlocking<Unit> {
        if(::context.isInitialized)context.stopService(Intent(context, AnchorForegroundService::class.java))
        if(::navigation.isInitialized)navigation.disconnectAll()
        if(::dao.isInitialized)dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
    }

    @Test fun activeWatchDisconnectRequiresChoiceAndSystemSwitchKeepsWatchArmed() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            connectAndAwaitFix(profile)
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Anchor watch is using NMEA").assertExists()
                compose.onNodeWithText("Switch to System GPS").performClick()

                withTimeout(15_000) { preferences.settings.first { it.gpsDataSource == GpsDataSource.SYSTEM } }
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                assertEquals(sessionId, dao.active()?.id)
                val events = withTimeout(5_000) { dao.events(sessionId).first { rows -> rows.any { event -> event.type == "WATCH_GPS_SOURCE_CHANGED" } } }
                assertTrue(events.any { it.type == "WATCH_GPS_SOURCE_CHANGED" && it.detail == "NMEA_TO_SYSTEM" })
            }
        }
    }

    @Test fun activeWatchCanBePausedDisconnectedAndResumedAsTheSameSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA))
            connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Pause watch & disconnect").performClick()
                withTimeout(5_000) { while (dao.active()?.paused != true) delay(50) }
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                assertEquals(sessionId,dao.active()?.id)
                val pointsAtPause=dao.points(sessionId).first().size;delay(1_500);assertEquals(pointsAtPause,dao.points(sessionId).first().size)
                ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
                withTimeout(8_000){while(dao.active()?.paused!=false)delay(50)}
                assertEquals(sessionId,dao.active()?.id)
                assertEquals(-36.8485,dao.active()!!.anchorLatitude,0.000001)
            }
        }
    }

    @Test fun liftAnchorPermanentlyClosesTheSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch();startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.LIFT_ANCHOR))
            withTimeout(10_000){while(dao.active()!=null)delay(50)}
            val closed=dao.sessions().first().first{it.id==sessionId}
            assertTrue(!closed.active&&closed.endedAt!=null)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_LIFTED"})
        }
    }

    @Test fun cancellingDisconnectLeavesWatchAndNmeaConnectionUntouched() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            connectAndAwaitFix(liveProfile(server, autoReconnect = true))
            val sessionId = seedActiveWatch()
            val acceptedBefore = server.accepted.get()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithText("Anchor watch is using NMEA").assertDoesNotExist()
                assertEquals(sessionId, dao.active()?.id)
                assertEquals(NmeaConnectionState.CONNECTED, navigation.connectionState.value)
                assertEquals(acceptedBefore, server.accepted.get())
            }
        }
    }

    @Test fun satelliteLayerCanBeSelectedBeforeNmeaConnects() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,mapType=1))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Satellite").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Satellite").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Default").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(2,withTimeout(5_000){preferences.settings.first{it.mapType==2}}.mapType)
        }
    }

    @Test fun successfulSaveAndConnectMakesNmeaTheDefaultGpsSource() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile=liveProfile(server,true),gpsDataSource=GpsDataSource.SYSTEM,demoMode=false))
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000){compose.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Connect").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithText("Test, save & connect").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Test, save & connect").performClick()
                val selected=withTimeout(15_000){preferences.settings.first{it.gpsDataSource==GpsDataSource.NMEA}}
                assertEquals(GpsDataSource.NMEA,selected.gpsDataSource)
                assertTrue(!selected.demoMode)
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_list").performScrollToIndex(1)
                compose.onNodeWithTag("gps_source_nmea").assertIsEnabled()
            }
        }
    }

    @Test fun disconnectedNmeaSourceIsDisabledInSettings() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(1)
            compose.onNodeWithTag("gps_source_nmea").assertIsNotEnabled()
            compose.onNodeWithText("Connect the NMEA server before selecting this source.").assertExists()
            assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
        }
    }

    @Test fun demoSourceIsForcedBackToSystemWhenDeveloperModeIsOff() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.DEMO,demoMode=true))
        assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.DEMO,demoMode=false))
        val restored=withTimeout(5_000){preferences.settings.first{!it.demoMode&&it.gpsDataSource==GpsDataSource.SYSTEM}}
        assertEquals(GpsDataSource.SYSTEM,restored.gpsDataSource)
    }

    @Test fun demoModeForcesAndLocksDemoSourceEvenWhenNmeaConnects() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile=liveProfile(server,true),gpsDataSource=GpsDataSource.SYSTEM,demoMode=true))
            assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Connect").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithText("Test, save & connect").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Test, save & connect").performClick()
                withTimeout(15_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED}}
                assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_list").performScrollToIndex(1)
                compose.onNodeWithTag("gps_source_demo").assertIsNotEnabled()
                compose.onNodeWithTag("gps_source_system").assertDoesNotExist()
                compose.onNodeWithTag("gps_source_nmea").assertDoesNotExist()
            }
        }
    }

    @Test fun proxyButtonExplainsWhyItCannotStart() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            compose.onNodeWithText("Enable global GPS proxy").performClick()
            compose.onNodeWithText("Connect to the NMEA source first.").assertExists()
            compose.onNodeWithText("Select mock location app → Anchor by Yokuli.",substring=true).assertExists()
        }
    }

    @Test fun languageCanSwitchToChineseAndPersists() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("language_zh").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Yokuli锚警系统").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("锚警").assertExists()
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE,withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.SIMPLIFIED_CHINESE}}.appLanguage)
        }
    }

    @Test fun builtInAndCustomAlarmSoundChoicesPersist() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,appLanguage=AppLanguage.ENGLISH,alarmSound=AlarmSound.SYSTEM_ALARM))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(5)
            compose.onNodeWithTag("alarm_sound_SYSTEM_NOTIFICATION").performClick()
            assertEquals(AlarmSound.SYSTEM_NOTIFICATION,withTimeout(5_000){preferences.settings.first{it.alarmSound==AlarmSound.SYSTEM_NOTIFICATION}}.alarmSound)
        }
        val custom="content://com.yokuli.anchorwatch.test/alarm.ogg"
        preferences.save(preferences.settings.first().copy(alarmSound=AlarmSound.CUSTOM,customAlarmSoundUri=custom))
        val restored=withTimeout(5_000){preferences.settings.first{it.alarmSound==AlarmSound.CUSTOM}}
        assertEquals(custom,restored.customAlarmSoundUri)
    }

    @Test fun passiveLossKeepsWatchArmedAndRecordsImmediateAndTimedAlarms() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile = liveProfile(server, autoReconnect = false), gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 1))
            connectAndAwaitFix(liveProfile(server, autoReconnect = false))
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                server.closeConnections()
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                val lost = withTimeout(5_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "NMEA_CONNECTION_LOST" } } }
                assertTrue(lost.any { it.type == "NMEA_CONNECTION_LOST" })
                val alarm = withTimeout(10_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" } } }
                assertTrue(alarm.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" })
                assertEquals(sessionId, dao.active()?.id)
            }
        }
    }

    @Test fun automaticReconnectRecordsRecoveryWithoutStoppingWatch() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile = profile, gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 5))
            connectAndAwaitFix(profile)
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                val acceptedBefore = server.accepted.get()
                server.closeConnections()
                withTimeout(8_000) { while (server.accepted.get() <= acceptedBefore) delay(50) }
                val events = withTimeout(8_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "NMEA_CONNECTION_LOST" } && rows.any { it.type == "NMEA_CONNECTION_RESTORED" } } }
                assertTrue(events.any { it.type == "NMEA_CONNECTION_LOST" })
                assertTrue(events.any { it.type == "NMEA_CONNECTION_RESTORED" })
                assertEquals(sessionId, dao.active()?.id)
            }
        }
    }

    @Test fun armingDoesNotReplaceOrDropTheExistingNmeaConnection() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile = profile, gpsDataSource = GpsDataSource.NMEA))
            connectAndAwaitFix(profile)
            val acceptedBefore = server.accepted.get()
            ActivityScenario.launch(MainActivity::class.java).use {
                ContextCompat.startForegroundService(context, Intent(context, AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                    .putExtra("lat", -36.8485).putExtra("lon", 174.7633).putExtra("rode", 0.0)
                    .putExtra("depth", Double.NaN).putExtra("warning", 40.0).putExtra("alarm", 50.0))
                withTimeout(5_000) { while (dao.active() == null) delay(50) }
                delay(750)
                assertEquals(acceptedBefore, server.accepted.get())
                assertEquals(NmeaConnectionState.CONNECTED, navigation.connectionState.value)
            }
        }
    }

    @Test fun backdownStartsWithTemporaryBoundaryAndProvisionalCentre() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",-36.8485).putExtra("lon",174.7633).putExtra("rode",40.0).putExtra("depth",8.0).putExtra("bowHeight",1.5).putExtra("boatLength",10.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","BALANCED"))
            val active=withTimeout(5_000){while(dao.active()==null)delay(50);dao.active()!!}
            assertTrue(active.active&&!active.paused)
            assertEquals(AnchorCenterStatus.LEARNING.name,active.centerStatus)
            assertEquals(-36.8485,active.learningReferenceLatitude!!,0.000001)
            assertTrue(active.provisionalAnchorLatitude!=null&&active.provisionalRadiusMeters!=null)
            assertTrue(active.provisionalRadiusMeters!!>35.0)
            delay(3_000)
            val learning=dao.active()!!
            assertEquals(AnchorCenterStatus.LEARNING.name,learning.centerStatus)
            assertTrue(learning.centerSampleCount>0)
            assertTrue(dao.events(active.id).first().any{it.type=="SESSION_STARTED_CENTER_LEARNING"})
        }
    }

    @Test fun backdownCannotStartWithoutAnExplicitBowHeight() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",-36.8485).putExtra("lon",174.7633).putExtra("rode",40.0).putExtra("depth",8.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","BASIC"))
            delay(1_000)
            assertTrue(dao.active()==null)
        }
    }

    @Test fun straightBackdownNeverResolvesTheAnchorCentre() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val baseLat=-36.8485;val baseLon=174.7633;server.setFix(baseLat,baseLon)
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",baseLat).putExtra("lon",baseLon).putExtra("rode",40.0).putExtra("depth",8.0).putExtra("bowHeight",1.5).putExtra("boatLength",10.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","BALANCED"))
            val sessionId=withTimeout(5_000){while(dao.active()==null)delay(50);dao.active()!!.id}
            delay(9_000)
            assertEquals(AnchorCenterStatus.LEARNING.name,dao.active()?.centerStatus)
            for(step in 1..22){server.setFix(baseLat+step/110_540.0,baseLon);delay(1_050)}
            val stillLearning=dao.active()!!
            assertEquals(sessionId,stillLearning.id)
            assertEquals(AnchorCenterStatus.LEARNING.name,stillLearning.centerStatus)
            assertTrue(dao.events(sessionId).first().none{it.type=="ANCHOR_CENTER_RESOLVED"})
        }
    }

    @Test fun alarmRangeCanChangeWithoutReplacingTheActiveSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch();startServiceForRestore()
            val before=dao.active()!!
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS)
                .putExtra("alarm",85.0).putExtra("rode",500.0).putExtra("depth",99.0).putExtra("bowHeight",20.0).putExtra("boatLength",90.0).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","TOLERANT"))
            withTimeout(5_000){while(dao.active()?.alarmRadiusMeters!=85.0)delay(50)}
            val after=dao.active()!!
            assertEquals(sessionId,after.id)
            assertEquals(85.0,after.alarmRadiusMeters,0.01)
            assertEquals(before.rodeLengthMeters,after.rodeLengthMeters,0.01)
            assertEquals(before.waterDepthMeters,after.waterDepthMeters)
            assertEquals(before.bowRollerHeightMeters,after.bowRollerHeightMeters,0.01)
            assertEquals(before.rangeMode,after.rangeMode)
            assertEquals(before.safetyPreset,after.safetyPreset)
            assertTrue(dao.events(sessionId).first().any{it.type=="ALARM_RANGE_CHANGED"})
        }
    }

    @Test fun wideningRangeCanClearAnAlarmWithoutEndingTheSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",200.0).putExtra("rangeMode","BASIC"))
            withTimeout(5_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_CLEARED_BY_RANGE_CHANGE"}}}
            assertEquals(sessionId,dao.active()?.id)
        }
    }

    @Test fun foregroundAppShowsActionableAnchorDragDialog() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0)
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
                compose.waitUntil(5_000){compose.onAllNodesWithText("ANCHOR DRAG ALARM").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("in_app_anchor_alarm").assertExists()
                compose.onNodeWithTag("alarm_snooze_action").performClick()
                val snoozed=withTimeout(5_000){while((dao.active()?.alarmSnoozedUntil?:0L)<=System.currentTimeMillis())delay(50);dao.active()!!}
                assertEquals(sessionId,snoozed.id)
            }
        }
    }

    @Test fun resolvedAnchorOffersGoogleMapsHandoff() = runBlocking<Unit> {
        val sessionId=seedActiveWatch()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("open_anchor_google_maps").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("open_anchor_google_maps").assertExists()
            assertEquals(sessionId,dao.active()?.id)
        }
    }

    @Test fun changingRangeDuringAlarmSnoozesIfDangerStillRemains() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",20.0).putExtra("rangeMode","BASIC"))
            val updated=withTimeout(5_000){while(dao.active()?.alarmRadiusMeters!=20.0)delay(50);dao.active()!!}
            assertEquals(sessionId,updated.id)
            assertTrue((updated.alarmSnoozedUntil?:0L)>System.currentTimeMillis())
        }
    }

    @Test fun snoozePersistsAndPauseClearsThePendingReminder() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SNOOZE))
            val snoozed=withTimeout(5_000){while((dao.active()?.alarmSnoozedUntil?:0L)<=System.currentTimeMillis())delay(50);dao.active()!!}
            assertTrue(snoozed.alarmSnoozedUntil!!>=System.currentTimeMillis()+4*60_000L)
            assertTrue(dao.events(sessionId).first().any{it.type=="ALARM_SNOOZED"})
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_WATCH))
            withTimeout(5_000){while(dao.active()?.paused!=true)delay(50)}
            assertTrue(dao.active()?.alarmSnoozedUntil==null)
        }
    }

    @Test fun activeSystemWatchCanSwitchBackToFreshNmea() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.SYSTEM));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch();startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SWITCH_WATCH_SOURCE_NMEA))
            withTimeout(12_000){preferences.settings.first{it.gpsDataSource==GpsDataSource.NMEA}}
            assertEquals(sessionId,dao.active()?.id)
            assertTrue(dao.events(sessionId).first().any{it.type=="WATCH_GPS_SOURCE_CHANGED"&&it.detail=="SYSTEM_TO_NMEA"})
        }
    }

    @Test fun serviceRejectsNmeaSourceSwitchWhenServerIsDisconnected() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        val sessionId=seedActiveWatch();startServiceForRestore()
        ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SWITCH_WATCH_SOURCE_NMEA))
        val events=withTimeout(5_000){dao.events(sessionId).first{rows->rows.any{it.type=="WATCH_GPS_SOURCE_CHANGE_REJECTED"}}}
        assertTrue(events.any{it.type=="WATCH_GPS_SOURCE_CHANGE_REJECTED"&&it.detail=="NMEA_NOT_CONNECTED"})
        assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
    }

    private suspend fun connectAndAwaitFix(profile: ConnectionProfile) {
        val started = android.os.SystemClock.elapsedRealtime()
        navigation.connect(profile)
        withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.CONNECTED } }
        withTimeout(5_000) { navigation.diagnostics.first { (it.lastFixElapsed ?: 0L) >= started } }
    }

    private suspend fun seedActiveWatch(anchorLatitude:Double=-36.8485,alarmRadius:Double=50.0): Long = dao.insertSession(
        AnchorSessionEntity(
            startedAt = System.currentTimeMillis(), anchorLatitude = anchorLatitude, anchorLongitude = 174.7633,
            rodeLengthMeters = 0.0, waterDepthMeters = null, bowRollerHeightMeters = 0.0,
            gpsAntennaOffsetMeters = 0.0, expectedSwingRadiusMeters = alarmRadius,
            warningRadiusMeters = maxOf(alarmRadius*.8,alarmRadius-10).coerceAtMost(alarmRadius-.1), alarmRadiusMeters = alarmRadius,
        )
    )

    private suspend fun startServiceForRestore() {
        val activeId=dao.active()?.id
        ContextCompat.startForegroundService(context, Intent(context, AnchorForegroundService::class.java))
        if(activeId!=null)withTimeout(8_000){dao.points(activeId).first{it.isNotEmpty()}}
    }

    private fun openDisconnectDecision() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connect").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Disconnect").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Disconnect").performClick()
    }

    private fun liveProfile(server: TestNmeaServer, autoReconnect: Boolean) = ConnectionProfile(
        host = "127.0.0.1", port = server.port, autoReconnect = autoReconnect,
    )
}

private class TestNmeaServer : Closeable {
    private val server = ServerSocket(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<Socket>()
    val accepted = AtomicInteger()
    private val sentence=AtomicReference(rmc(-36.8485,174.7633))
    val port: Int get() = server.localPort

    init {
        scope.launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients += socket; accepted.incrementAndGet()
                launch { writeFixes(socket) }
            }
        }
    }

    private suspend fun writeFixes(socket: Socket) {
        try {
            while (scope.isActive && !socket.isClosed) { socket.getOutputStream().write(sentence.get()); socket.getOutputStream().flush(); delay(100) }
        } catch (_: Exception) {
            // Closing a connection is how loss/reconnect scenarios are triggered.
        } finally { clients.remove(socket); runCatching { socket.close() } }
    }

    fun setFix(latitude:Double,longitude:Double){sentence.set(rmc(latitude,longitude))}
    fun closeConnections() { clients.toList().forEach { runCatching { it.close() } } }
    override fun close() { closeConnections(); runCatching { server.close() }; scope.cancel() }

    companion object{
        private fun rmc(latitude:Double,longitude:Double):ByteArray{
            fun coordinate(value:Double,degreeWidth:Int):String{val absolute=kotlin.math.abs(value);val degrees=absolute.toInt();val minutes=(absolute-degrees)*60;return String.format(Locale.US,"%0${degreeWidth}d%07.4f",degrees,minutes)}
            val body="GPRMC,073000.00,A,${coordinate(latitude,2)},${if(latitude<0)"S" else "N"},${coordinate(longitude,3)},${if(longitude<0)"W" else "E"},0.2,180.0,150826,,,A"
            val checksum=body.fold(0){value,char->value xor char.code}
            return "\$$body*${String.format(Locale.US,"%02X",checksum)}\r\n".toByteArray()
        }
    }
}
