package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeTripOwnerTest{
    @Test fun anchorStartupGpsLeaseHandsOffWithoutAZeroOwnerGap(){
        val registry=RuntimeOwnerRegistry()
        registry.set(RuntimeOwner.ANCHOR_STARTUP,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true))
        assertTrue(registry.snapshot().needsSystemLocation)
        registry.set(RuntimeOwner.ANCHOR_WATCH,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsPhoneMotion=true))
        registry.set(RuntimeOwner.ANCHOR_STARTUP,null)
        val active=registry.snapshot();assertEquals(setOf(RuntimeOwner.ANCHOR_WATCH),active.owners);assertTrue(active.needsSystemLocation);assertTrue(active.needsWakeLock)
    }
    @Test fun tripAndLiveHubDemandsComposeWithoutReleasingEachOther(){val registry=RuntimeOwnerRegistry();registry.set(RuntimeOwner.VESSEL_HUB_UI,RuntimeRequirement(needsSystemLocation=true,needsPhoneMotion=true));registry.set(RuntimeOwner.TRIP_WATCH,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsWifiLock=true,needsPhonePressure=true));assertTrue(registry.snapshot().needsWakeLock);registry.set(RuntimeOwner.VESSEL_HUB_UI,null);val active=registry.snapshot();assertTrue(active.needsSystemLocation);assertTrue(active.needsPhonePressure);assertEquals(setOf(RuntimeOwner.TRIP_WATCH),active.owners)}

    @Test fun runningPhoneOnlyTripCanClaimNmeaTransportWithoutLosingOtherDemands(){
        val registry=RuntimeOwnerRegistry()
        registry.set(RuntimeOwner.TRIP_WATCH,RuntimeRequirement(needsSystemLocation=true,needsWakeLock=true,needsPhoneHeading=true))
        assertFalse(registry.snapshot().needsNmeaTransport)

        registry.set(RuntimeOwner.TRIP_WATCH,RuntimeRequirement(needsSystemLocation=true,needsNmeaTransport=true,needsWakeLock=true,needsWifiLock=true,needsPhoneHeading=true))
        val active=registry.snapshot()
        assertTrue(active.needsNmeaTransport)
        assertTrue(active.needsWifiLock)
        assertTrue(active.needsSystemLocation)
        assertTrue(active.needsPhoneHeading)
    }

    @Test fun anchorTelemetryCanReleaseSensorsWithoutWeakeningAnchorResources(){val registry=RuntimeOwnerRegistry();registry.set(RuntimeOwner.ANCHOR_WATCH,RuntimeRequirement(needsWakeLock=true));registry.set(RuntimeOwner.ANCHOR_TELEMETRY,RuntimeRequirement(needsPhoneMotion=true,needsPhonePressure=true));registry.set(RuntimeOwner.ANCHOR_TELEMETRY,null);val active=registry.snapshot();assertTrue(active.needsWakeLock);assertFalse(active.needsPhoneMotion);assertEquals(setOf(RuntimeOwner.ANCHOR_WATCH),active.owners)}
}
