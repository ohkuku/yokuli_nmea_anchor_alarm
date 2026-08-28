package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.sharing.LocalNmeaServerSettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.OutputSettingsRepository
import com.yokuli.anchorwatch.di.AnchorWatchEntryPoint
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPositionMode
import com.yokuli.anchorwatch.domain.model.CandidateDecision
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorMonitoringPhase
import com.yokuli.anchorwatch.domain.model.AnchorOriginMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.location.AcceptedAnchorPositionPolicy
import com.yokuli.anchorwatch.location.SystemLocationRepository
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.service.AnchorForegroundService
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import com.yokuli.anchorwatch.runtime.SystemMonotonicClock
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private lateinit var alarmUi:AlarmUiRepository
    private lateinit var sharingServer:NmeaSharingServer
    private lateinit var sonarDao:SonarDao
    private lateinit var tripDao:TripDao
    private lateinit var sonarRecorder:SonarSurveyRecorder
    private lateinit var acceptedPosition:AcceptedPositionRepository
    private lateinit var systemLocation:SystemLocationRepository
    private lateinit var runtimeDiagnostics:RuntimeDiagnosticsRepository
    private lateinit var outputSettings:OutputSettingsRepository
    private lateinit var localNmeaServerSettings:LocalNmeaServerSettingsRepository

    @Before fun prepare() = runBlocking<Unit> {
        MapRuntimePolicy.renderGoogleEngine=false
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AnchorWatchEntryPoint::class.java)
        dao = entry.dao();sonarDao=entry.sonarDao();tripDao=entry.tripDao();sonarRecorder=entry.sonarRecorder();acceptedPosition=entry.acceptedPosition();systemLocation=entry.systemLocation();runtimeDiagnostics=entry.runtimeDiagnostics();outputSettings=entry.outputSettings();localNmeaServerSettings=entry.localNmeaServerSettings();preferences = entry.preferences(); navigation = entry.navigation();alarmUi=entry.alarmUi();sharingServer=entry.sharingServer()
        val serviceWasRunning = context.stopService(Intent(context, AnchorForegroundService::class.java))
        if (serviceWasRunning) {
            withTimeout(5_000) { runtimeDiagnostics.state.first { !it.serviceReady } }
        }
        navigation.disconnectAll()
        dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
        sonarDao.active()?.let{sonarDao.finish(it.id,System.currentTimeMillis())}
        acceptedPosition.unlockSource(null)
        acceptedPosition.selectSource(GpsDataSource.SYSTEM)
        // Output settings live in a separate DataStore from AppSettings. Reset
        // them as part of every story so a previous Phone Position scenario
        // cannot make a later NMEA-selection story look like a product failure.
        outputSettings.save(NmeaDeviceOutputSettings())
        localNmeaServerSettings.requestStop()
        preferences.save(AppSettings(gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 2))
    }

    @Test fun anchorRuntimeClockSharesAndroidGpsElapsedRealtimeDomain(){
        val before=SystemClock.elapsedRealtime()
        val runtimeNow=SystemMonotonicClock.elapsedRealtime()
        val after=SystemClock.elapsedRealtime()
        assertTrue("Anchor Runtime clock $runtimeNow is outside Android GPS clock interval $before..$after",runtimeNow in before..after)
    }

    @After fun cleanup() = runBlocking<Unit> {
        MapRuntimePolicy.renderGoogleEngine=true
        if(::context.isInitialized){
            val serviceWasRunning=context.stopService(Intent(context,AnchorForegroundService::class.java))
            if(serviceWasRunning&&::runtimeDiagnostics.isInitialized){
                withTimeoutOrNull(5_000){runtimeDiagnostics.state.first{!it.serviceReady}}
            }
        }
        if(::navigation.isInitialized)navigation.disconnectAll()
        if(::systemLocation.isInitialized)systemLocation.setPreviewEnabled(false)
        if(::localNmeaServerSettings.isInitialized)localNmeaServerSettings.requestStop()
        if(::dao.isInitialized)dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
        if(::sonarDao.isInitialized)sonarDao.active()?.let{sonarDao.finish(it.id,System.currentTimeMillis())}
    }

    /** CI provisions a fresh emulator GNSS fix before device stories run. This
     * exercises the real Service → startup lease → LocationRepository → Room
     * session path; it must not depend on an NMEA connection. */
    @Test fun newArmIntegrityEpochReplacesAnIdleStaleAcceptedCache() = runBlocking<Unit>{
        val old=SystemClock.elapsedRealtime()-30_000L
        acceptedPosition.unlockSource(null)
        assertTrue(acceptedPosition.selectSource(GpsDataSource.SYSTEM))
        acceptedPosition.submit(GpsDataSource.SYSTEM,NavigationFix(
            latitude=-36.8484,longitude=174.7632,receivedElapsedRealtime=old,
            horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,
            sourceSentence="TEST_OLD_GNSS",valid=true,
        ))
        assertEquals(old,acceptedPosition.state.value.acceptedFix?.receivedElapsedRealtime)

        assertTrue(acceptedPosition.beginArmAttempt(GpsDataSource.SYSTEM))
        assertNull("A new session attempt must not inherit an idle stale accepted fix",acceptedPosition.state.value.acceptedFix)
        val fresh=SystemClock.elapsedRealtime()
        acceptedPosition.submit(GpsDataSource.SYSTEM,NavigationFix(
            latitude=-36.8485,longitude=174.7633,receivedElapsedRealtime=fresh,
            horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,
            sourceSentence="TEST_CURRENT_GNSS",valid=true,
        ))
        assertEquals(fresh,acceptedPosition.state.value.acceptedFix?.receivedElapsedRealtime)
        assertEquals("ACCEPTED",acceptedPosition.state.value.disposition)
    }

    @Test fun duplicatePrimeWithFreshAcceptedStateStillRemainsReady() = runBlocking<Unit>{
        val now=SystemClock.elapsedRealtime()
        val fix=NavigationFix(
            latitude=-36.8485,longitude=174.7633,receivedElapsedRealtime=now,
            horizontalAccuracyMeters=4.0,positionProvider=PositionProvider.ANDROID_GNSS,
            sourceSentence="TEST_DUPLICATE_GNSS",valid=true,
        )
        acceptedPosition.unlockSource(null)
        assertTrue(acceptedPosition.beginArmAttempt(GpsDataSource.SYSTEM))
        assertTrue(acceptedPosition.submit(GpsDataSource.SYSTEM,fix).isNotEmpty())
        assertTrue("A duplicate synchronous prime is expected to be deduplicated",acceptedPosition.submit(GpsDataSource.SYSTEM,fix).isEmpty())
        val readiness=AcceptedAnchorPositionPolicy.evaluate(
            state=acceptedPosition.state.value,
            requestedSource=GpsDataSource.SYSTEM,
            nowElapsedRealtime=SystemClock.elapsedRealtime(),
            maximumAgeMillis=15_000L,
            nmeaConnection=NmeaConnectionState.DISCONNECTED,
            nmeaConnectionStartedElapsedRealtime=null,
            nmeaConnectionGeneration=navigation.connectionGeneration(),
        )
        assertTrue("Readiness must use accepted state, not prime result count",readiness.ready)
    }

    @Test fun freshSystemGpsArmCreatesAnActiveSessionWithoutNmea() = runBlocking<Unit>{
        navigation.disconnectAll()
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,gpsLossSeconds=20,appLanguage=AppLanguage.ENGLISH))
        // This P0 story is about the synchronous provider -> integrity -> ARM
        // race, not emulator-console delivery. Establish the exact fresh raw
        // provider precondition deterministically in the Debug test process.
        systemLocation.publishProviderLocationForTest(Location(LocationManager.GPS_PROVIDER).apply{
            latitude=-36.8485;longitude=174.7633;accuracy=4f;time=System.currentTimeMillis()
            elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
        })
        val arm=Intent(context,AnchorForegroundService::class.java)
            .setAction(AnchorForegroundService.ARM)
            .putExtra("lat",-36.8485)
            .putExtra("lon",174.7633)
            .putExtra("rode",0.0)
            .putExtra("depth",Double.NaN)
            .putExtra("bowHeight",0.0)
            .putExtra("boatLength",Double.NaN)
            .putExtra("antennaOffset",0.0)
            .putExtra("warning",40.0)
            .putExtra("alarm",50.0)
            .putExtra("placement","CENTER_DROP")
            .putExtra("rangeMode","BASIC")
            .putExtra("safetyPreset","BALANCED")
            .putExtra("positionSource",GpsDataSource.SYSTEM.name)
            .putExtra("centerSource",AnchorCenterSource.CURRENT_POSITION.name)
            .putExtra("depthSource","MANUAL")
        ContextCompat.startForegroundService(context,arm)
        val active=withTimeoutOrNull(20_000){while(dao.active()==null)delay(50);dao.active()}
        assertNotNull("System-GPS ARM never created an active session; feedback=${runtimeDiagnostics.state.value.lastUserFeedback}",active)
        requireNotNull(active)
        assertTrue(active.active)
        assertFalse(active.paused)
        assertEquals(AnchorMonitoringPhase.ARMED.name,active.monitoringPhase)
        assertNotNull(active.monitoringActivatedAt)
        assertEquals(GpsDataSource.SYSTEM.name,active.positionSource)
        assertEquals(NmeaConnectionState.DISCONNECTED,navigation.connectionState.value)
    }

    @Test fun freshNmeaGpsArmCreatesActiveSessionImmediately() = runBlocking<Unit>{
        TestNmeaServer().use{server->
            val profile=liveProfile(server,true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,gpsLossSeconds=20,appLanguage=AppLanguage.ENGLISH))
            connectAndAwaitFix(profile)
            val liveGeneration=navigation.connectionGeneration()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.ARM)
                .putExtra("lat",0.0).putExtra("lon",0.0)
                .putExtra("rode",0.0).putExtra("depth",Double.NaN).putExtra("bowHeight",0.0)
                .putExtra("boatLength",Double.NaN).putExtra("antennaOffset",0.0)
                .putExtra("warning",40.0).putExtra("alarm",50.0)
                .putExtra("placement",AnchorPlacementMode.CENTER_DROP.name).putExtra("rangeMode",AnchorRangeMode.BASIC.name)
                .putExtra("safetyPreset",AnchorSafetyPreset.BALANCED.name).putExtra("positionSource",GpsDataSource.NMEA.name)
                .putExtra("centerSource",AnchorCenterSource.CURRENT_POSITION.name)
                .putExtra("originMode",AnchorOriginMode.CURRENT_ACCEPTED_POSITION.name).putExtra("depthSource",AnchorDepthSource.MANUAL.name))
            val active=withTimeout(10_000){while(dao.active()?.monitoringPhase!=AnchorMonitoringPhase.ARMED.name)delay(50);dao.active()!!}
            assertEquals(GpsDataSource.NMEA.name,active.positionSource)
            assertEquals(-36.8485,active.anchorLatitude,0.000001)
            assertEquals(174.7633,active.anchorLongitude,0.000001)
            assertEquals(liveGeneration,navigation.connectionGeneration())
            assertEquals(1,server.accepted.get())
        }
    }

    @Test fun manualCoordinateWaitsForFirstAcceptedGpsThenArmsWithoutMovingAnchor() = runBlocking<Unit>{
        TestNmeaServer().use{server->
            server.setEmitting(false)
            val profile=liveProfile(server,true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,gpsLossSeconds=20,appLanguage=AppLanguage.ENGLISH))
            assertTrue(navigation.connect(profile))
            withTimeout(5_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            val anchorLatitude=-36.851234;val anchorLongitude=174.771234
            val arm=Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.ARM)
                .putExtra("lat",anchorLatitude).putExtra("lon",anchorLongitude)
                .putExtra("rode",0.0).putExtra("depth",Double.NaN).putExtra("bowHeight",0.0)
                .putExtra("boatLength",Double.NaN).putExtra("antennaOffset",0.0)
                .putExtra("warning",40.0).putExtra("alarm",50.0)
                .putExtra("placement","CENTER_DROP").putExtra("rangeMode","BASIC")
                .putExtra("safetyPreset","BALANCED").putExtra("positionSource",GpsDataSource.NMEA.name)
                .putExtra("centerSource",AnchorCenterSource.MANUAL_COORDINATES.name)
                .putExtra("originMode","MANUAL_COORDINATE").putExtra("depthSource","MANUAL")
            ContextCompat.startForegroundService(context,arm)
            val waiting=withTimeout(10_000){while(dao.active()?.monitoringPhase!="WAITING_FOR_GPS")delay(50);dao.active()!!}
            assertEquals(anchorLatitude,waiting.anchorLatitude,0.0);assertEquals(anchorLongitude,waiting.anchorLongitude,0.0)
            assertTrue(waiting.monitoringActivatedAt==null);assertTrue(dao.points(waiting.id).first().isEmpty())
            server.setEmitting(true)
            val armed=withTimeout(15_000){while(dao.active()?.monitoringPhase!="ARMED")delay(50);dao.active()!!}
            assertEquals(anchorLatitude,armed.anchorLatitude,0.0);assertEquals(anchorLongitude,armed.anchorLongitude,0.0)
            assertNotNull(armed.monitoringActivatedAt)
            delay(1_000)
            assertEquals(1,dao.events(armed.id).first().count{it.type=="GPS_MONITORING_ACTIVATED"})
        }
    }

    @Test fun waitingSessionPrimesAlreadyAvailableRawFix() = runBlocking<Unit>{
        TestNmeaServer().use{server->
            val profile=liveProfile(server,true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,gpsLossSeconds=20,appLanguage=AppLanguage.ENGLISH))
            connectAndAwaitFix(profile)
            // No future provider emission is available to rescue the command.
            // ARM must synchronously prime the raw fix already in StateFlow.
            server.setEmitting(false)
            val anchorLatitude=-36.852345;val anchorLongitude=174.772345
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.ARM)
                .putExtra("lat",anchorLatitude).putExtra("lon",anchorLongitude)
                .putExtra("rode",0.0).putExtra("depth",Double.NaN).putExtra("bowHeight",0.0)
                .putExtra("boatLength",Double.NaN).putExtra("warning",40.0).putExtra("alarm",50.0)
                .putExtra("placement",AnchorPlacementMode.CENTER_DROP.name).putExtra("rangeMode",AnchorRangeMode.BASIC.name)
                .putExtra("safetyPreset",AnchorSafetyPreset.BALANCED.name).putExtra("positionSource",GpsDataSource.NMEA.name)
                .putExtra("centerSource",AnchorCenterSource.MANUAL_COORDINATES.name)
                .putExtra("originMode",AnchorOriginMode.MANUAL_COORDINATE.name).putExtra("depthSource",AnchorDepthSource.MANUAL.name))
            val active=withTimeout(5_000){while(dao.active()?.monitoringPhase!=AnchorMonitoringPhase.ARMED.name)delay(25);dao.active()!!}
            assertEquals(anchorLatitude,active.anchorLatitude,0.0);assertEquals(anchorLongitude,active.anchorLongitude,0.0)
            assertEquals(1,dao.events(active.id).first().count{it.type=="GPS_MONITORING_ACTIVATED"})
        }
    }

    @Test fun mapPickWaitsForFirstAcceptedGpsThenArmsWithoutMovingAnchor() = runBlocking<Unit>{
        TestNmeaServer().use{server->
            server.setEmitting(false)
            val profile=liveProfile(server,true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,gpsLossSeconds=20,appLanguage=AppLanguage.ENGLISH))
            assertTrue(navigation.connect(profile))
            withTimeout(5_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED_NO_DATA}}
            val anchorLatitude=-36.853456;val anchorLongitude=174.773456
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.ARM)
                .putExtra("lat",anchorLatitude).putExtra("lon",anchorLongitude)
                .putExtra("rode",40.0).putExtra("depth",8.0).putExtra("bowHeight",1.5)
                .putExtra("boatLength",10.0).putExtra("warning",56.0).putExtra("alarm",70.0)
                .putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode",AnchorRangeMode.ADVANCED.name)
                .putExtra("safetyPreset",AnchorSafetyPreset.BALANCED.name).putExtra("positionSource",GpsDataSource.NMEA.name)
                .putExtra("centerSource",AnchorCenterSource.MAP_PICK.name)
                .putExtra("originMode",AnchorOriginMode.MAP_PICK.name).putExtra("depthSource",AnchorDepthSource.MANUAL.name))
            val waiting=withTimeout(10_000){while(dao.active()?.monitoringPhase!=AnchorMonitoringPhase.WAITING_FOR_GPS.name)delay(25);dao.active()!!}
            assertEquals(anchorLatitude,waiting.anchorLatitude,0.0);assertEquals(anchorLongitude,waiting.anchorLongitude,0.0)
            assertEquals(0,waiting.centerSampleCount);assertTrue(dao.points(waiting.id).first().isEmpty())
            assertTrue(dao.events(waiting.id).first().none{it.type.startsWith("ANCHOR_CENTER_")&&it.type!="ANCHOR_CENTER_ANALYSIS_RESET"})

            server.setEmitting(true)
            val learning=withTimeout(15_000){while(dao.active()?.monitoringPhase!=AnchorMonitoringPhase.LEARNING.name)delay(25);dao.active()!!}
            assertEquals(anchorLatitude,learning.anchorLatitude,0.0);assertEquals(anchorLongitude,learning.anchorLongitude,0.0)
            delay(1_000)
            assertEquals(1,dao.events(learning.id).first().count{it.type=="GPS_MONITORING_ACTIVATED"})
        }
    }

    @Test fun activeNmeaWatchDisconnectDialogDoesNotOfferUnsafeHotSwitch() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            connectAndAwaitFix(profile)
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Anchor watch is using NMEA").assertExists()
                compose.onNodeWithText("Pause safely & release watch source").assertExists()
                compose.onNodeWithText("Switch to System GPS & disconnect").assertDoesNotExist()
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(sessionId, dao.active()?.id)
                assertEquals(GpsDataSource.NMEA,preferences.settings.first().gpsDataSource)
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
                compose.onNodeWithText("Pause safely & release watch source").performClick()
                withTimeout(5_000) { while (dao.active()?.paused != true) delay(50) }
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                assertEquals(sessionId,dao.active()?.id)
                val pointsAtPause=dao.points(sessionId).first().size;delay(1_500);assertEquals(pointsAtPause,dao.points(sessionId).first().size)
                ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
                val resumed=withTimeoutOrNull(20_000){while(dao.active()?.paused!=false)delay(50);dao.active()}
                val latestFix=navigation.fix.value
                assertNotNull(
                    "Watch did not resume; session=${dao.active()}; connection=${navigation.connectionState.value}; " +
                        "acceptedSockets=${server.accepted.get()}; fix=$latestFix; " +
                        "fixAge=${latestFix?.let{android.os.SystemClock.elapsedRealtime()-it.receivedElapsedRealtime}}; " +
                        "events=${dao.events(sessionId).first().takeLast(8)}",
                    resumed,
                )
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
                compose.onNodeWithText("Anchor watch is locked to NMEA").assertDoesNotExist()
                assertEquals(sessionId, dao.active()?.id)
                assertEquals(NmeaConnectionState.CONNECTED, navigation.connectionState.value)
                assertEquals(acceptedBefore, server.accepted.get())
            }
        }
    }

    @Test fun satelliteLayerCanBeSelectedBeforeNmeaConnects() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,mapType=1))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("map_layers").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("map_layers").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Satellite").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Satellite").performClick()
            assertEquals(2,withTimeout(5_000){preferences.settings.first{it.mapType==2}}.mapType)
        }
    }

    @Test fun mapLayerSheetHasThreeBaseStylesAndOnlyLocalDepthOpacity() = runBlocking<Unit> {
        preferences.save(AppSettings(mapType=1,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("map_layers").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("map_layers").performClick()
            val requiredTags=listOf("map_style_map","map_style_satellite","map_style_nautical","local_depth_section","local_depth_toggle","local_depth_provider")
            compose.waitUntil(5_000){requiredTags.all{tag->compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1}}
            assertEquals(0,compose.onAllNodesWithTag("sonar_opacity").fetchSemanticsNodes().size)
            assertEquals(0,compose.onAllNodesWithTag("offline_map_opacity").fetchSemanticsNodes().size)
            assertEquals(0,compose.onAllNodesWithTag("base_map_opacity").fetchSemanticsNodes().size)
            compose.onNodeWithTag("map_style_nautical").performClick()
            compose.onNodeWithText("Nautical map is a visual aid").assertExists()
            compose.onNodeWithText("I understand · Use Nautical").performClick()
            val saved=withTimeout(5_000){preferences.settings.first{it.mapType==3&&it.nauticalDisclaimerAccepted}}
            assertEquals(3,saved.mapType)
        }
    }

    @Test fun realSonarNeedsFreshNmeaAndDemoSonarNeedsARunningDemoWatch() = runBlocking<Unit> {
        navigation.disconnectAll();preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,demoMode=false,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Data").performClick();compose.onNodeWithText("Sonar").performClick()
            compose.onNodeWithText("Start sonar survey").assertIsNotEnabled()
            compose.onNodeWithText("Connect the NMEA server before starting a real sonar survey.").assertExists()
        }
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,demoMode=true,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Data").performClick();compose.onNodeWithText("Sonar").performClick()
            compose.onNodeWithText("Start sonar survey").assertIsNotEnabled()
            compose.onNodeWithText("Start or resume a Demo anchor watch first.",substring=true).assertExists()
        }
    }

    @Test fun sailMfdKeepsCoreInstrumentsInTheFirstViewportAndCockpitReallyLocks() = runBlocking<Unit> {
        // The test app database intentionally persists between connected-test
        // invocations. Remove only this test's optional built-in override so
        // it verifies the product's default six-instrument first viewport.
        tripDao.deleteDashboard("builtin-sailing")
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav_sail").performClick()
            compose.onNodeWithTag("mfd_page_OVERVIEW").assertIsDisplayed()
            // The Overview includes a real map preview which legitimately
            // consumes map gestures. Switch pages through the visible MFD
            // picker instead of assuming a centre swipe reached the pager.
            compose.onNodeWithTag("trip_page_picker").performClick()
            compose.onNodeWithTag("trip_page_picker_SAILING").performClick()
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("mfd_page_SAILING").assertIsDisplayed();true}.getOrDefault(false)}
            val coreTitles=listOf("Speed through water (STW)","Speed over ground (SOG)","Heel angle (HEEL)","Velocity made good (VMG)","Apparent wind speed (AWS)","True wind speed (TWS)")
            compose.waitForIdle()
            coreTitles.forEach{title->
                try{compose.onNodeWithTag("marine_instrument_$title").assertIsDisplayed()}
                catch(error:AssertionError){throw AssertionError("Core instrument '$title' was not fully visible.\n${compose.onRoot(useUnmergedTree=true).printToString(maxDepth=10)}",error)}
            }
            compose.onNodeWithTag("trip_page_picker").performClick()
            compose.onNodeWithTag("trip_page_picker_NAV").performClick()
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("mfd_page_NAV").assertIsDisplayed();true}.getOrDefault(false)}
            compose.onNodeWithTag("trip_cockpit_mode").performClick()
            compose.onNodeWithTag("nav_anchor").assertDoesNotExist()
            compose.onNodeWithTag("start_trip").assertDoesNotExist()
            compose.onNodeWithTag("trip_touch_lock").performClick()
            compose.onNodeWithTag("trip_touch_lock").performTouchInput {
                down(center)
                advanceEventTime(1_600L)
                up()
            }
            compose.onNodeWithTag("trip_cockpit_mode").performClick()
            compose.onNodeWithTag("nav_anchor").assertExists()
        }
    }

    @Test fun completedTripWithoutCoordinatesExplainsTheMissingRouteInRealHistory() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        val tripId=tripDao.insertSession(TripSessionEntity(
            name="No-position route story",
            startedAt=System.currentTimeMillis(),
            endedAt=System.currentTimeMillis(),
            active=false,
            boatLengthMeters=10.6,
            draftMeters=1.8,
            positionPreference="PHONE",
            headingPreference="PHONE",
            phoneMotionEnabled=false,
            mountCalibrationVersion=null,
        ))
        try{
            ActivityScenario.launch(MainActivity::class.java).use{
                compose.onNodeWithTag("nav_sail").performClick()
                compose.onNodeWithTag("sail_tab_1").performClick()
                compose.waitUntil(10_000){compose.onAllNodesWithTag("trip_history_open_$tripId").fetchSemanticsNodes().size==1}
                compose.onNodeWithTag("trip_history_open_$tripId").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithTag("trip_history_route_$tripId").fetchSemanticsNodes().size==1}
                compose.onNodeWithTag("trip_history_route_$tripId").performScrollTo()
                // The route Card is clickable and therefore merges its child
                // semantics in the accessibility tree. Inspect the unmerged
                // tree when targeting the deliberately distinct empty-state
                // child, then still require real viewport visibility and the
                // exact user-facing reason.
                compose.waitUntil(10_000){compose.onAllNodesWithTag("trip_route_empty",useUnmergedTree=true).fetchSemanticsNodes().size==1}
                compose.onNodeWithTag("trip_route_empty",useUnmergedTree=true).assertIsDisplayed()
                compose.onNodeWithText("No usable coordinates were recorded for this trip. Instrument samples and events remain available below.",useUnmergedTree=true).assertIsDisplayed()
            }
        }finally{
            tripDao.deleteCompleted(tripId)
        }
    }

    @Test fun mapCanSwitchBetweenLockedFollowAndFreeBrowsing() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("map_lock_toggle").assertExists()
            compose.onNodeWithContentDescription("Auto-return to boat · pan and zoom remain available").assertExists()
            compose.onNodeWithTag("map_lock_toggle").performClick()
            compose.onNodeWithContentDescription("Free map browsing").assertExists()
            compose.onNodeWithTag("map_recenter").assertExists().performClick()
        }
    }

    @Test fun mapDistanceToolCreatesAResultAndSecondTapClearsTheMeasurement() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("map_measure_toggle").assertExists().performClick()
            compose.onNodeWithTag("map_measure_result").assertExists()
            compose.onNodeWithContentDescription("Stop measuring and clear pins").assertExists()
            compose.onNodeWithTag("map_measure_toggle").performClick()
            compose.onNodeWithTag("map_measure_result").assertDoesNotExist()
            compose.onNodeWithContentDescription("Measure distance").assertExists()
        }
    }

    @Test fun successfulConnectSuggestsNmeaButChangesGpsOnlyAfterExplicitConsent() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile=liveProfile(server,true),gpsDataSource=GpsDataSource.SYSTEM,demoMode=false))
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Data").performClick()
                compose.onNodeWithTag("data_tab_input").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithTag("nmea_connect_input").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("nmea_connect_input").assertIsEnabled();true}.getOrDefault(false)}
                compose.onNodeWithText("127.0.0.1").assertExists()
                compose.onNodeWithText(server.port.toString()).assertExists()
                compose.onNodeWithTag("nmea_connect_input").performScrollTo().performClick()
                withTimeout(15_000){navigation.fix.first{it?.valid==true}}
                assertEquals("Connecting instruments must not change anchor GPS consent",GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
                assertEquals("Save/connect must retain one formal RX socket, not open a disposable preflight client",1,server.accepted.get())
                compose.onNodeWithTag("data_tab_vessel").performClick()
                compose.onNodeWithTag("data_gps_controls").performScrollTo()
                compose.onNodeWithTag("gps_source_nmea").assertIsEnabled()
                compose.onNodeWithTag("nmea_gps_suggestion").performScrollTo().assertExists()
                compose.onNodeWithText("Use NMEA GPS").performClick()
                val selected=withTimeout(5_000){preferences.settings.first{it.gpsDataSource==GpsDataSource.NMEA}}
                assertFalse(selected.demoMode)
            }
        }
    }

    @Test fun nmeaInputAndOutputKeepReceiveAndSendPortsOnSeparateTopLevelPages() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        outputSettings.save(NmeaDeviceOutputSettings(
            transportMode=NmeaOutputTransportMode.DEDICATED_TCP,
            outputHost="192.168.1.211",
            outputPort=10110,
        ))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Data").performClick()
            compose.onNodeWithTag("data_tab_input").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithTag("nmea_rx_port").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("nmea_rx_port").assertExists()
            compose.onNodeWithTag("data_tab_output").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithTag("nmea_product_boat_network",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("nmea_product_boat_network").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithTag("nmea_publish_phone_position",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("nmea_publish_phone_position").assertExists()
            compose.onNodeWithTag("nmea_output_list").performScrollToIndex(2)
            compose.onNodeWithTag("nmea_output_route").assertIsDisplayed()
            compose.onNodeWithTag("nmea_output_tx_host").assertExists()
            compose.onNodeWithTag("nmea_output_tx_port").assertExists()
            compose.onNodeWithText("192.168.1.211").assertExists()
            compose.onNodeWithTag("nmea_output_list").performScrollToIndex(5)
            compose.onNodeWithTag("raw_tx_stream_filter").assertExists()
            compose.onNodeWithTag("raw_tx_type_filter").assertExists()
            compose.onNodeWithTag("raw_tx_pause").assertExists()
            compose.onNodeWithTag("raw_tx_clear").assertExists()
        }
    }

    @Test fun disconnectedNmeaSourceIsDisabledInDataSources() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Data").performClick()
            compose.onNodeWithTag("data_tab_vessel").performClick()
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
                compose.onNodeWithText("Data").performClick()
                compose.onNodeWithTag("data_tab_input").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithTag("nmea_connect_input").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("nmea_connect_input").assertIsEnabled();true}.getOrDefault(false)}
                compose.onNodeWithTag("nmea_connect_input").performScrollTo().performClick()
                withTimeout(15_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED}}
                assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
                compose.onNodeWithTag("data_tab_vessel").performClick()
                compose.onNodeWithTag("gps_source_demo").assertIsNotEnabled()
                compose.onNodeWithTag("gps_source_system").assertDoesNotExist()
                compose.onNodeWithTag("gps_source_nmea").assertDoesNotExist()
            }
        }
    }

    @Test fun proxyButtonExplainsWhyItCannotStart() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Data").performClick()
            compose.onNodeWithTag("data_tab_vessel").performClick()
            compose.onNodeWithTag("data_gps_proxy_toggle").performScrollTo().performClick()
            compose.onNodeWithText("Enable global GPS proxy").performScrollTo().performClick()
            compose.onNodeWithText("Connect the NMEA server and wait for a fresh valid position before enabling the global proxy.").assertExists()
            compose.onNodeWithText("Select mock location app → Boat Watch.",substring=true).assertExists()
        }
    }

    @Test fun languageCanSwitchToChineseAndPersists() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            compose.onNodeWithTag("settings_language").performClick()
            compose.onNodeWithTag("language_en").assertExists()
            compose.onNodeWithTag("language_zh_cn").assertExists()
            compose.onNodeWithTag("language_zh_tw").assertExists()
            compose.onNodeWithTag("language_ja").assertExists()
            compose.onNodeWithTag("language_fr").assertExists()
            compose.onNodeWithTag("language_es").assertExists()
            compose.onNodeWithText("繁體中文").assertExists()
            compose.onNodeWithText("日本語").assertExists()
            compose.onNodeWithText("Français").assertExists()
            compose.onNodeWithText("Español").assertExists()
            compose.onNodeWithText("🇨🇳").assertDoesNotExist()
            compose.onNodeWithText("🇬🇧").assertDoesNotExist()
            compose.onNodeWithTag("language_zh_cn").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("锚泊").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("锚泊").assertExists()
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE,withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.SIMPLIFIED_CHINESE}}.appLanguage)
        }
    }

    @Test fun alarmSoundUiOnlyOffersAlarmAndCustomAndMigratesLegacyChoices() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,appLanguage=AppLanguage.ENGLISH,alarmSound=AlarmSound.SYSTEM_NOTIFICATION))
        assertEquals(AlarmSound.SYSTEM_ALARM,preferences.settings.first().alarmSound)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(1)
            compose.onNodeWithTag("settings_alarm").performClick()
            compose.onNodeWithTag("alarm_sound_SYSTEM_ALARM").assertExists()
            compose.onNodeWithTag("alarm_sound_CUSTOM").assertExists()
            compose.onNodeWithTag("alarm_sound_SYSTEM_NOTIFICATION").assertDoesNotExist()
            compose.onNodeWithTag("alarm_sound_SYSTEM_RINGTONE").assertDoesNotExist()
        }
        val custom="content://com.yokuli.anchorwatch.test/alarm.ogg"
        preferences.save(preferences.settings.first().copy(alarmSound=AlarmSound.CUSTOM,customAlarmSoundUri=custom))
        val restored=withTimeout(15_000){preferences.settings.first{it.alarmSound==AlarmSound.CUSTOM}}
        assertEquals(custom,restored.customAlarmSoundUri)
    }

    @Test fun settingsRootIsShortAndEveryConfigurationSectionOpens() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            val pages=listOf(
                "settings_alarm" to 1,
                "settings_vessel" to 2,
                "settings_phone_sensors" to 2,
                "settings_depth_sounder" to 2,
                "settings_map_depth" to 3,
                "settings_background" to 4,
                "settings_storage_support" to 4,
            )
            pages.forEach{(tag,index)->
                compose.onNodeWithTag("settings_list").performScrollToIndex(index)
                compose.onNodeWithTag(tag).performClick()
                compose.onNodeWithContentDescription("Back").assertExists()
                compose.onNodeWithContentDescription("Back").performClick()
            }
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            compose.onNodeWithTag("settings_language").performClick()
            compose.onNodeWithTag("language_en").assertExists().performClick()
            compose.onNodeWithText("Enable global GPS proxy").assertDoesNotExist()
            compose.onNodeWithTag("settings_positioning").assertDoesNotExist()
            compose.onNodeWithTag("settings_developer").assertDoesNotExist()
            compose.onNodeWithText("Data").performClick()
            compose.onNodeWithTag("data_tab_vessel").performClick()
            compose.onNodeWithTag("data_gps_controls").assertExists()
        }
    }

    @Test fun aboutPageShowsRealMakerCrewAndOptionalSupportConfirmation() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_support_card").assertExists().performClick()
            compose.onNodeWithText("Support is optional and does not unlock app features.",substring=true).assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("settings_list").performScrollToKey("settings_about")
            compose.onNodeWithTag("settings_about").performClick()
            compose.onNodeWithTag("about_page").assertExists()
            compose.onNodeWithText("Developed aboard SV Yokuli").assertExists()
            compose.onNodeWithTag("about_list").performScrollToKey("about_crew")
            compose.onNodeWithTag("about_crew_kuku").assertExists()
            compose.onNodeWithTag("about_crew_yoyo").assertExists()
            compose.onNodeWithTag("about_crew_lili").assertExists()
            compose.onNodeWithTag("about_list").performScrollToKey("about_support")
            compose.onNodeWithTag("about_buy_me_a_coffee").performClick()
            compose.onNodeWithText("Support is optional and does not unlock app features.",substring=true).assertExists()
            compose.onNodeWithTag("about_support_continue").assertExists()
        }
    }

    @Test fun firstRunMakerPageHasCrewAndVoyageButNeverAsksForMoney() = runBlocking<Unit> {
        preferences.save(AppSettings(onboardingCompleted=false,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("onboarding_maker").assertExists()
            compose.onNodeWithText("Developed aboard SV Yokuli").assertExists()
            compose.onNodeWithTag("onboarding_language").assertExists().performClick()
            compose.onNodeWithTag("language_zh_cn").performClick()
            withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.SIMPLIFIED_CHINESE}}
            compose.onNodeWithText("开发于 SV Yokuli 船上").assertExists()
            compose.onNodeWithTag("onboarding_language").performClick()
            compose.onNodeWithTag("language_en").performClick()
            withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.ENGLISH}}
            compose.onNodeWithTag("about_buy_me_a_coffee").assertDoesNotExist()
            compose.onNodeWithTag("onboarding_meet_crew").performClick()
            compose.onNodeWithTag("onboarding_crew").assertExists()
            compose.onNodeWithTag("onboarding_crew_kuku").assertExists()
            compose.onNodeWithTag("onboarding_crew_yoyo").assertExists()
            compose.onNodeWithText("Captain",substring=true).assertExists()
            compose.onNodeWithTag("onboarding_crew_lili").assertExists()
            compose.onNodeWithTag("onboarding_continue").performClick()
            compose.onNodeWithTag("onboarding_setup_step_0").assertExists()
            repeat(5){step->
                compose.onNodeWithTag("onboarding_setup_next").performClick()
                compose.onNodeWithTag("onboarding_setup_step_${step+1}").assertExists()
            }
            compose.onNodeWithTag("onboarding_finish").performClick()
            compose.onNodeWithTag("nav_anchor").assertExists()
            assertTrue(preferences.settings.first().onboardingCompleted)
        }
    }

    @Test fun feedbackPageBuildsAnEditableEmailRequestWithoutSendingInsideTheApp() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToKey("settings_about")
            compose.onNodeWithTag("settings_feedback").performClick()
            compose.onNodeWithTag("feedback_page").assertExists()
            compose.onNodeWithText("kuku.the.developer@gmail.com").assertExists()
            compose.onNodeWithTag("feedback_subject").assertExists()
            compose.onNodeWithTag("feedback_details").assertExists()
            compose.onNodeWithTag("feedback_open_email").assertIsEnabled()
            compose.onNodeWithText("Boat Watch does not send or track the message itself.",substring=true).assertExists()
        }
    }

    @Test fun stoppingAlarmTestCannotBeOvertakenByAPendingStartCommand() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        repeat(4){
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_ALARM_TEST))
        }
        delay(1_000)
        assertEquals(AlarmState.IDLE,alarmUi.snapshot.value.state)
        assertTrue(alarmUi.snapshot.value.type!=AlarmType.ALARM_TEST)
    }

    @Test fun alarmTestRemainsGloballyActiveUntilUserStopsItAndUsesNonModalControls() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
            withTimeout(5_000){alarmUi.snapshot.first{snapshot->snapshot.state==AlarmState.ALARM&&snapshot.type==AlarmType.ALARM_TEST}}
            compose.onNodeWithTag("alarm_test_banner").assertExists()
            compose.onNodeWithTag("confirm_alarm_audible_banner").assertIsEnabled()
            compose.onNodeWithTag("stop_alarm_test_banner").assertIsEnabled()
            delay(1_500)
            assertEquals(AlarmType.ALARM_TEST,alarmUi.snapshot.value.type)
            compose.onNodeWithTag("stop_alarm_test_banner").performClick()
            withTimeout(5_000){alarmUi.snapshot.first{snapshot->snapshot.state==AlarmState.IDLE&&snapshot.type!=AlarmType.ALARM_TEST}}
        }
    }

    @Test fun legacyDisabledHeadingFieldsUpgradeToAutomaticEvidenceWithoutChangingHistory() = runBlocking<Unit> {
        val sessionId=seedPhoneHeadingLearningWatch()
        dao.updateSession(requireNotNull(dao.session(sessionId)).copy(usePhoneHeading=false,headingEvidenceEnabled=false,headingEvidenceEpoch=0,headingEvidenceEnabledAt=null))
        dao.insertPoint(com.yokuli.anchorwatch.data.database.TrackPointEntity(sessionId=sessionId,timestamp=System.currentTimeMillis(),latitude=-36.8485,longitude=174.7633,distanceFromAnchor=0.0,sog=0.1,cog=180.0,heading=123.0,hdop=1.0,headingMeasured=true,headingSampleSequence=17,positionSource=GpsDataSource.NMEA.name,headingSource=HeadingSource.PHONE.name,headingQuality=HeadingQuality.STABLE.name,headingEpoch=4))
        startServiceForRestore()
        withTimeout(5_000){dao.sessions().first{sessions->sessions.firstOrNull{it.id==sessionId}?.let{it.usePhoneHeading&&it.headingEvidenceEnabled&&it.headingEvidenceEpoch>=1}==true}}
        val historical=dao.points(sessionId).first().single()
        // Compatibility upgrade changes only the legacy gate fields. Track
        // history remains immutable for audit/export and the centre is not moved.
        assertEquals(123.0,historical.heading?:Double.NaN,0.0)
        assertEquals(HeadingSource.PHONE.name,historical.headingSource)
        assertFalse(dao.events(sessionId).first().any{it.type=="ANCHOR_HEADING_EVIDENCE_DISABLED"})
    }

    @Test fun passiveLossKeepsWatchArmedAndRecordsImmediateAndTimedAlarms() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile = liveProfile(server, autoReconnect = false), gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 1))
            connectAndAwaitFix(liveProfile(server, autoReconnect = false))
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                server.closeConnections()
                withTimeout(5_000) { navigation.connectionState.first { it in setOf(NmeaConnectionState.RECONNECTING,NmeaConnectionState.DISCONNECTED,NmeaConnectionState.ERROR) } }
                val lost = withTimeout(5_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "NMEA_CONNECTION_LOST" } } }
                assertTrue(lost.any { it.type == "NMEA_CONNECTION_LOST" })
                val alarm = withTimeout(10_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" } } }
                assertTrue(alarm.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" })
                assertEquals(sessionId, dao.active()?.id)
            }
        }
    }

    @Test fun activeWatchCannotEnableEnvironmentalAlertsWithoutConnectedNmea() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM)
        startServiceForRestore()

        ContextCompat.startForegroundService(
            context,
            Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.UPDATE_CONDITION_GUARDS)
                .putExtra("depthGuard",true)
                .putExtra("shallowDepth",2.5),
        )
        delay(750)

        val active=requireNotNull(dao.active())
        assertEquals(sessionId,active.id)
        assertTrue(!active.depthGuardEnabled)
        assertTrue(!active.windGuardEnabled)
        assertTrue(!active.windShiftEnabled)
    }

    @Test fun disconnectedInstrumentNeverTrapsAnAlreadyEnabledEnvironmentalAlert() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM)
        dao.active()?.let{dao.updateSession(it.copy(depthGuardEnabled=true,shallowDepthAlarmMeters=2.5))}
        startServiceForRestore()

        ContextCompat.startForegroundService(
            context,
            Intent(context,AnchorForegroundService::class.java)
                .setAction(AnchorForegroundService.UPDATE_CONDITION_GUARDS)
                .putExtra("depthGuard",false),
        )

        val active=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&!it.depthGuardEnabled}}.first{it.id==sessionId}}
        assertTrue(!active.depthGuardEnabled)
        val events=withTimeout(5_000){dao.events(sessionId).first{rows->rows.any{it.type=="DEPTH_GUARD_DISABLED"}}}
        assertTrue(events.any{it.type=="DEPTH_GUARD_DISABLED"})
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
            // The release gate runs all device stories in one orchestrated job. Room and
            // the foreground service can be cold after earlier process recycling even
            // though the same story is consistently fast in the three CI shards.
            val active=withTimeout(20_000){while(dao.active()==null)delay(50);dao.active()!!}
            assertTrue(active.active&&!active.paused)
            assertEquals(AnchorCenterStatus.LEARNING.name,active.centerStatus)
            assertEquals(-36.8485,active.learningReferenceLatitude!!,0.000001)
            assertTrue(active.provisionalAnchorLatitude!=null&&active.provisionalRadiusMeters!=null)
            assertTrue(active.provisionalRadiusMeters!!>35.0)
            val learning=withTimeout(10_000){while((dao.active()?.centerSampleCount?:0)<=0)delay(50);dao.active()!!}
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

    @Test fun activeSystemWatchRemainsLockedWhenFreshNmeaConnects() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.SYSTEM));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM);ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java));delay(500)
            assertEquals(sessionId,dao.active()?.id)
            assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
            assertEquals(GpsDataSource.SYSTEM.name,dao.active()?.positionSource)
            assertTrue(dao.events(sessionId).first().none{it.type=="WATCH_GPS_SOURCE_CHANGED"})
        }
    }

    @Test fun restoredSessionKeepsItsSourceWhenNmeaIsDisconnected() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM);ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java));delay(500)
        assertEquals(GpsDataSource.SYSTEM.name,dao.active()?.positionSource)
        assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
    }

    @Test fun keepingCurrentCentreStopsEstimatorAndKeepsRadius() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=77L;val sessionId=seedCandidateWatch(candidateId);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.KEEP_CURRENT_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val kept=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateId==null&&it.centerStatus==AnchorCenterStatus.RESOLVED.name}}.first{it.id==sessionId}}
            assertEquals(-36.8485,kept.anchorLatitude,0.000001)
            assertEquals(45.0,kept.alarmRadiusMeters,0.001)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_CENTER_CURRENT_KEPT"})
        }
    }

    @Test fun acceptingEstimatedCandidateMovesOnlyCentreAndKeepsRadius() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=88L;val sessionId=seedCandidateWatch(candidateId);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACCEPT_ESTIMATED_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val accepted=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateId==null&&it.centerSource==AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name}}.first{it.id==sessionId}}
            assertEquals(-36.8484,accepted.anchorLatitude,0.000001)
            assertEquals(174.7634,accepted.anchorLongitude,0.000001)
            assertEquals(45.0,accepted.alarmRadiusMeters,0.001)
            assertEquals(AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,accepted.centerSource)
            // Provenance remains ESTIMATE even after the user adopts it as the
            // safety centre; adoption must not rewrite how the point was found.
            assertEquals(AnchorPositionMode.ESTIMATE.name,accepted.anchorPositionMode)
            assertTrue(accepted.provisionalAnchorLatitude==null&&accepted.provisionalRadiusMeters==null)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_CENTER_ACCEPTED_BY_USER"})
        }
    }

    @Test fun acceptingCandidateWhilePausedDoesNotSecretlyRestartWatchResources() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=188L;val sessionId=seedCandidateWatch(candidateId,paused=true);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACCEPT_ESTIMATED_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val accepted=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateId==null&&it.centerSource==AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name}}.first{it.id==sessionId}}
            assertTrue(accepted.paused)
            withTimeout(5_000){runtimeDiagnostics.state.first{state->com.yokuli.anchorwatch.runtime.RuntimeOwner.ANCHOR_WATCH !in state.activeOwners}}
            assertTrue(alarmUi.snapshot.value.state!=AlarmState.ARMED)
        }
    }

    @Test fun keepingCurrentCentreWhilePausedRemainsPausedUntilExplicitResume() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=189L;val sessionId=seedCandidateWatch(candidateId,paused=true);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.KEEP_CURRENT_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val kept=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateId==null&&it.centerStatus==AnchorCenterStatus.RESOLVED.name}}.first{it.id==sessionId}}
            assertTrue(kept.paused)
            withTimeout(5_000){runtimeDiagnostics.state.first{state->com.yokuli.anchorwatch.runtime.RuntimeOwner.ANCHOR_WATCH !in state.activeOwners}}
            assertTrue(alarmUi.snapshot.value.state!=AlarmState.ARMED)
        }
    }

    @Test fun completedHistoryDeletionRemovesSessionTrackAndTimeline() = runBlocking<Unit> {
        val id=dao.insertSession(AnchorSessionEntity(startedAt=1,endedAt=2,anchorLatitude=1.0,anchorLongitude=2.0,rodeLengthMeters=0.0,waterDepthMeters=null,bowRollerHeightMeters=0.0,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=20.0,warningRadiusMeters=15.0,alarmRadiusMeters=20.0,active=false))
        dao.insertPoint(com.yokuli.anchorwatch.data.database.TrackPointEntity(sessionId=id,timestamp=1,latitude=1.0,longitude=2.0,distanceFromAnchor=0.0,sog=null,cog=null,heading=null,hdop=null))
        dao.insertEvent(com.yokuli.anchorwatch.data.database.AlarmEventEntity(sessionId=id,timestamp=1,type="TEST"))
        assertEquals(1,dao.deleteCompletedSession(id));assertTrue(dao.sessions().first().none{it.id==id});assertTrue(dao.points(id).first().isEmpty());assertTrue(dao.events(id).first().isEmpty())
    }

    @Test fun sharingAndLinzPreferencesRoundTrip() = runBlocking<Unit> {
        preferences.save(AppSettings(nmeaSharingEnabled=true,nmeaSharingPort=12001,linzHydroEnabled=true,linzHydroOpacity=.55,linzHydroDisclaimerAccepted=true,offlineMapEnabled=true,offlineMapName="Harbour.mbtiles",offlineMapAttribution="Licensed test chart",alarmAudibleConfirmedAt=1234,sounderOffsetMeters=.4,showLinzDepthReference=false,showPersonalMapReference=false))
        val restored=withTimeout(5_000){preferences.settings.first{it.nmeaSharingEnabled&&it.nmeaSharingPort==12001&&it.linzHydroEnabled}}
        assertEquals(.55,restored.linzHydroOpacity,.001);assertTrue(restored.linzHydroDisclaimerAccepted);assertTrue(restored.offlineMapEnabled);assertEquals("Harbour.mbtiles",restored.offlineMapName);assertEquals("Licensed test chart",restored.offlineMapAttribution);assertEquals(1234L,restored.alarmAudibleConfirmedAt);assertEquals(.4,restored.sounderOffsetMeters,.001);assertTrue(!restored.showLinzDepthReference&&!restored.showPersonalMapReference)
    }

    @Test fun sonarSurveyPersistsRawAndNormalizedDepthAndDeletesAsOneUnit() = runBlocking<Unit> {
        val surveyId=sonarDao.insertSurvey(SonarSurveyEntity(name="Harbour pass",startedAt=1,endedAt=2,active=false,transducerDraftMeters=1.2))
        sonarDao.insertSample(DepthSampleEntity(surveyId=surveyId,timestamp=2,latitude=-36.84,longitude=174.76,baseGridX=1,baseGridY=2,sourceElapsedRealtime=1,rawDepthMeters=8.0,measuredDepthMeters=9.2,normalizedDepthMeters=null,depthReference="BELOW_TRANSDUCER",sentenceType="DBT",horizontalAccuracyMeters=2.5,gpsSource="NMEA",positionProvider="NMEA",positionAgeMillis=100))
        val sample=sonarDao.samplesNow(surveyId).single();assertEquals(8.0,sample.rawDepthMeters,.001);assertEquals(9.2,sample.measuredDepthMeters,.001);assertTrue(sample.normalizedDepthMeters==null)
        assertEquals(1,sonarDao.deleteCompleted(surveyId));assertTrue(sonarDao.samplesNow(surveyId).isEmpty())
    }

    @Test fun sonarRuntimePairsDepthOnlyWithGpsFromTheSameNmeaServer() = runBlocking<Unit> {
        TestNmeaServer().use{server->
            server.setDepth(8.0,1.0)
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,sounderOffsetMeters=.4));connectAndAwaitFix(profile)
            withTimeout(10_000){while(true){
                val now=android.os.SystemClock.elapsedRealtime();val status=sonarRecorder.status.value
                if(status.hasFreshRealDepth(now)&&status.hasFreshNmeaPosition(now))break
                delay(25)
            }}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name","Runtime test").putExtra("tideMode","OFF"))
            val survey=withTimeoutOrNull(15_000){while(sonarDao.active()==null)delay(25);sonarDao.active()}
            assertNotNull("Survey was not started; recorder=${sonarRecorder.status.value}; connection=${navigation.connectionState.value}",survey)
            requireNotNull(survey)
            val sample=withTimeoutOrNull(15_000){sonarDao.samples(survey.id).first{it.isNotEmpty()}.single()}
            assertNotNull("Fresh same-stream depth was not recorded; recorder=${sonarRecorder.status.value}",sample)
            requireNotNull(sample)
            assertEquals(8.0,sample.rawDepthMeters,.001);assertEquals(9.4,sample.measuredDepthMeters,.001);assertTrue(sample.normalizedDepthMeters==null);assertTrue(sample.usable);assertTrue(!sample.positionCorrectionApplied);assertEquals("NMEA_SERVER",sample.gpsSource);assertEquals(PositionProvider.NMEA.name,sample.positionProvider)
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_SONAR_SURVEY))
            withTimeout(5_000){while(sonarDao.active()!=null)delay(25)}
        }
    }

    @Test fun legacySharingRequestMigratesToIndependentStoppedPhoneServiceWithoutOpeningASocket() = runBlocking<Unit> {
        TestNmeaServer().use{boat->
            val profile=liveProfile(boat,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val upstreamConnections=boat.accepted.get();val port=ServerSocket(0).use{it.localPort}
            preferences.save(preferences.settings.first().copy(nmeaSharingEnabled=true,nmeaSharingPort=port))
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",true).putExtra("port",port))
            withTimeout(5_000){preferences.settings.first{!it.nmeaSharingEnabled}}
            // This migration crosses the service command queue plus two
            // DataStore transactions. Busy hosted emulators can legitimately
            // take longer than five seconds without changing the outcome.
            val migrated=withTimeout(15_000){localNmeaServerSettings.settings.first{it.configured&&it.port==port}}
            assertFalse(migrated.serverRequested)
            assertFalse(outputSettings.settings.first().publicationEnabled)
            assertFalse(outputSettings.settings.first().transportMode==NmeaOutputTransportMode.TCP_SERVER)
            assertEquals(SharingServerState.STOPPED,sharingServer.status.value.state)
            assertEquals(0,sharingServer.status.value.clientCount)
            assertEquals(upstreamConnections,boat.accepted.get())
        }
    }

    private suspend fun connectAndAwaitFix(profile: ConnectionProfile) {
        val started = android.os.SystemClock.elapsedRealtime()
        navigation.connect(profile)
        withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.CONNECTED } }
        withTimeout(5_000) { navigation.diagnostics.first { (it.lastFixElapsed ?: 0L) >= started } }
    }

    private suspend fun seedActiveWatch(anchorLatitude:Double=-36.8485,alarmRadius:Double=50.0,positionSource:GpsDataSource=GpsDataSource.NMEA): Long = dao.insertSession(
        AnchorSessionEntity(
            startedAt = System.currentTimeMillis(), anchorLatitude = anchorLatitude, anchorLongitude = 174.7633,
            rodeLengthMeters = 0.0, waterDepthMeters = null, bowRollerHeightMeters = 0.0,
            gpsAntennaOffsetMeters = 0.0, expectedSwingRadiusMeters = alarmRadius,
            warningRadiusMeters = maxOf(alarmRadius*.8,alarmRadius-10).coerceAtMost(alarmRadius-.1), alarmRadiusMeters = alarmRadius,
            positionSource = positionSource.name,
        )
    )

    private suspend fun seedCandidateWatch(candidateId:Long,paused:Boolean=false):Long=dao.insertSession(
        AnchorSessionEntity(
            startedAt=System.currentTimeMillis(),anchorLatitude=-36.8485,anchorLongitude=174.7633,
            rodeLengthMeters=45.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,
            expectedSwingRadiusMeters=40.0,warningRadiusMeters=36.0,alarmRadiusMeters=45.0,
            placementMode=AnchorPlacementMode.BACKDOWN.name,centerStatus=AnchorCenterStatus.CANDIDATE_READY.name,
            centerConfidence="HIGH",positionSource=GpsDataSource.NMEA.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,
            centerSource=AnchorCenterSource.UNKNOWN.name,provisionalAnchorLatitude=-36.8484,provisionalAnchorLongitude=174.7634,
            provisionalRadiusMeters=4.0,candidateId=candidateId,candidateCreatedAt=System.currentTimeMillis(),candidateDecision=CandidateDecision.AVAILABLE.name,
            paused=paused,
        )
    )

    private suspend fun seedPhoneHeadingLearningWatch():Long=dao.insertSession(
        AnchorSessionEntity(
            startedAt=System.currentTimeMillis(),anchorLatitude=-36.8485,anchorLongitude=174.7633,
            rodeLengthMeters=45.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,
            expectedSwingRadiusMeters=40.0,warningRadiusMeters=36.0,alarmRadiusMeters=45.0,
            placementMode=AnchorPlacementMode.BACKDOWN.name,centerStatus=AnchorCenterStatus.LEARNING.name,
            centerConfidence="LOW",positionSource=GpsDataSource.NMEA.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,
            centerSource=AnchorCenterSource.UNKNOWN.name,learningReferenceLatitude=-36.8485,learningReferenceLongitude=174.7633,
            provisionalAnchorLatitude=-36.8485,provisionalAnchorLongitude=174.7633,provisionalRadiusMeters=45.0,usePhoneHeading=true,
            estimationEpoch=4,estimationEpochStartedAt=System.currentTimeMillis(),
            headingEvidenceEnabled=true,headingEvidenceEpoch=4,headingEvidenceEnabledAt=System.currentTimeMillis(),headingEvidenceSourceId=HeadingSource.PHONE.name,
        )
    )

    private suspend fun startServiceForRestore() {
        val expectedSessionId=requireNotNull(dao.active()).id
        ContextCompat.startForegroundService(context, Intent(context, AnchorForegroundService::class.java))
        // MainActivity may have already started and restored the service before this
        // helper runs. Readiness belongs to the expected session, not to whether this
        // particular call happened to create another Android Service generation.
        // A full orchestrated suite can briefly starve the service process while
        // Compose and Room test processes are being recycled. Keep the barrier
        // session-specific, but allow enough time for an actual Android cold start.
        val restored=withTimeoutOrNull(30_000){runtimeDiagnostics.state.first{
            // serviceReady is published only after AnchorWatchRuntime.restore() has
            // completed for this exact row. RuntimeResourceManager diagnostics are
            // collected by another coroutine and may legitimately lag this barrier.
            it.serviceReady&&it.restoredSessionId==expectedSessionId
        }}
        assertNotNull("Service did not restore session $expectedSessionId; diagnostics=${runtimeDiagnostics.state.value}; active=${dao.active()}; connection=${navigation.connectionState.value}",restored)
    }

    private fun openDisconnectDecision() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Data").performClick()
        compose.onNodeWithTag("data_tab_input").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nmea_stop_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nmea_stop_input").performScrollTo().performClick()
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
    private val emitting=AtomicBoolean(true)
    private val depthSentence=AtomicReference<ByteArray?>(null)
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
            var emitted = 0
            while (scope.isActive && !socket.isClosed) {
                if(!emitting.get()){delay(100);continue}
                socket.getOutputStream().write(sentence.get())
                depthSentence.get()?.let{socket.getOutputStream().write(it)}
                socket.getOutputStream().flush()
                // Every newly accepted live socket gets a short validation burst. The App
                // must retain this same socket; it must never open a disposable preflight
                // client first. Afterwards use a realistic low visual cadence: safety code
                // still sees every sentence, while Compose tests get an idle window.
                delay(if (emitted++ < 3) 100 else 2_000)
            }
        } catch (_: Exception) {
            // Closing a connection is how loss/reconnect scenarios are triggered.
        } finally { clients.remove(socket); runCatching { socket.close() } }
    }

    fun setFix(latitude:Double,longitude:Double){sentence.set(rmc(latitude,longitude))}
    fun setEmitting(value:Boolean){emitting.set(value)}
    fun setDepth(depthMeters:Double,offsetMeters:Double){depthSentence.set((NmeaChecksum.append("IIDPT,$depthMeters,$offsetMeters")+"\r\n").toByteArray())}
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
