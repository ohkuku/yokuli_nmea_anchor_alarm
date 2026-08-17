package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.backup.BackupRestorePolicy
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeOwnerRegistry
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.health.BatteryHealthPolicy
import com.yokuli.anchorwatch.testsupport.FaultScenarioRunner
import com.yokuli.anchorwatch.testsupport.ScenarioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongRunningFaultStoryTest{
    @Test fun accelerated24HourStoryRejectsSpikeSurvivesDropoutAndDetectsRealDrag(){
        val events=buildList{
            repeat(12*60){minute->add(ScenarioEvent.Advance(60_000));add(ScenarioEvent.Position(kotlin.math.sin(minute/30.0)*18.0,kotlin.math.cos(minute/30.0)*18.0));if(minute==300){add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.GpsSpike(120.0));add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.Position(2.0))}}
            add(ScenarioEvent.Advance(16_000));add(ScenarioEvent.GpsDropoutTick);add(ScenarioEvent.Position(5.0))
            repeat(12*60){minute->add(ScenarioEvent.Advance(60_000));add(ScenarioEvent.Position(18.0+minute*.25))}
        }
        val result=FaultScenarioRunner().run(events)
        assertTrue(result.quarantinedFixes>=1)
        assertTrue(result.alarms.any{it.type==AlarmType.GPS_DATA_LOST})
        assertTrue(result.alarms.any{it.type==AlarmType.ANCHOR_RADIUS_EXCEEDED})
        assertTrue(result.acceptedFixes>1_000)
    }

    @Test fun checksumFloodNeverParsesAndNormalFeedRecovers(){
        val valid=NmeaChecksum.append("GPRMC,120000,A,3650.9100,S,17445.7980,E,0.2,12.0,170826,,,A")
        val events=buildList{repeat(10_000){add(ScenarioEvent.NmeaLine("\$GPRMC,broken*00"))};add(ScenarioEvent.NmeaLine(valid))}
        assertEquals(1,FaultScenarioRunner().run(events).parsedLines)
    }

    @Test fun runtimeResourcesRemainHeldUntilLastOwnerLeaves(){
        val registry=RuntimeOwnerRegistry();registry.set(RuntimeOwner.ANCHOR_WATCH,RuntimeRequirement(needsWakeLock=true,needsWifiLock=true));registry.set(RuntimeOwner.SONAR_MAPPING,RuntimeRequirement(needsWakeLock=true,needsNmeaTransport=true))
        assertTrue(registry.snapshot().needsWakeLock);assertTrue(registry.snapshot().needsWifiLock)
        registry.set(RuntimeOwner.ANCHOR_WATCH,null)
        assertTrue(registry.snapshot().needsWakeLock);assertFalse(registry.snapshot().needsWifiLock);assertTrue(registry.snapshot().needsNmeaTransport)
        registry.set(RuntimeOwner.SONAR_MAPPING,null);assertFalse(registry.snapshot().needsWakeLock)
    }

    @Test fun restorePolicyBlocksEveryLiveRuntime(){
        assertTrue(BackupRestorePolicy.blockingReason(true,false,false,false)!!.contains("anchor"))
        assertTrue(BackupRestorePolicy.blockingReason(false,true,false,false)!!.contains("sonar"))
        assertTrue(BackupRestorePolicy.blockingReason(false,false,true,false)!!.contains("proxy"))
        assertTrue(BackupRestorePolicy.blockingReason(false,false,false,true)!!.contains("Sharing"))
        assertNull(BackupRestorePolicy.blockingReason(false,false,false,false))
    }

    @Test fun batteryWarningIsEdgeTriggeredAndUsesRecoveryHysteresis(){
        val policy=BatteryHealthPolicy();assertFalse(policy.update(50,true).low);assertTrue(policy.update(15,true).newlyLow);assertFalse(policy.update(14,true).newlyLow);assertTrue(policy.update(19,true).low);assertFalse(policy.update(21,true).low);assertTrue(policy.update(15,true).newlyLow)
    }

    @Test fun fullRuntimeFaultMatrixRecoversWithoutUnboundedHistory(){
        val valid=NmeaChecksum.append("GPRMC,120000,A,3650.9100,S,17445.7980,E,0.2,12.0,170826,,,A")
        val events=buildList{
            add(ScenarioEvent.Position(0.0));add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.TimestampReversal())
            add(ScenarioEvent.NmeaDisconnect);add(ScenarioEvent.NoBytes(4_000));add(ScenarioEvent.NmeaReconnect);add(ScenarioEvent.NmeaLine(valid))
            add(ScenarioEvent.Depth(8.0));add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.DepthSpike(80.0));add(ScenarioEvent.Advance(2_001));add(ScenarioEvent.SonarStale)
            add(ScenarioEvent.Battery(15));add(ScenarioEvent.Battery(14));add(ScenarioEvent.Battery(22));add(ScenarioEvent.Battery(15))
            add(ScenarioEvent.ScreenOff);add(ScenarioEvent.SharingClients(4));add(ScenarioEvent.ProxyStart);add(ScenarioEvent.Advance(16_000));add(ScenarioEvent.ProxyWatchdog);add(ScenarioEvent.ScreenOn)
            add(ScenarioEvent.ProcessRestart)
        }
        val result=FaultScenarioRunner().run(events)
        assertTrue(result.rejectedFixes>=1);assertTrue(result.nmeaDisconnects>=2);assertEquals(1,result.nmeaReconnects)
        assertEquals(1,result.depthAccepted);assertTrue(result.depthQuarantined>=1);assertEquals(2,result.lowBatteryWarnings)
        assertEquals(0,result.sharingClients);assertFalse(result.proxyActive);assertTrue(result.proxyStaleStops>=1);assertTrue(result.screenOn);assertTrue(result.watchPausedAfterRestart)
        assertTrue(result.alarms.size<=1_024)
    }

    @Test fun coherentThreePointDepthSlopeIsReleasedButIsolatedSpikeStaysQuarantined(){
        val events=listOf(
            ScenarioEvent.Depth(5.0),ScenarioEvent.Advance(1_000),ScenarioEvent.DepthSpike(20.0,1.0),
            ScenarioEvent.Advance(1_000),ScenarioEvent.Depth(21.0,2.0),ScenarioEvent.Advance(1_000),ScenarioEvent.Depth(22.0,3.0),
        )
        val result=FaultScenarioRunner().run(events)
        assertTrue(result.depthQuarantined>=2);assertTrue(result.depthAccepted>=2)
    }
}
