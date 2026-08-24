package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.runtime.output.PublicationOwnershipGate
import org.junit.Assert.*
import org.junit.Test

class PublicationOwnershipGateTest{
    @Test fun backupStaysStandbyThenTakesOverAndReturnsAfterRecoveryHold(){
        val gate=PublicationOwnershipGate(takeoverDelayMillis=3_000,recoveryMillis=2_000)
        assertEquals(PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,gate.evaluate(PublicationPolicy.BACKUP,true,false,0).ownership)
        assertEquals(PublisherOwnershipState.TAKEOVER_PENDING,gate.evaluate(PublicationPolicy.BACKUP,false,false,1_000).ownership)
        assertFalse(gate.evaluate(PublicationPolicy.BACKUP,false,false,3_999).publish)
        assertTrue(gate.evaluate(PublicationPolicy.BACKUP,false,false,4_000).publish)
        assertTrue(gate.evaluate(PublicationPolicy.BACKUP,true,false,5_000).publish)
        val returned=gate.evaluate(PublicationPolicy.BACKUP,true,false,7_000)
        assertFalse(returned.publish);assertEquals(PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT,returned.ownership)
    }

    @Test fun alwaysPublishesButMakesDuplicateConflictVisible(){
        val decision=PublicationOwnershipGate(3_000).evaluate(PublicationPolicy.ALWAYS,externalPresent=true,sourceConflict=true,now=0)
        assertTrue(decision.publish);assertEquals(PublisherOwnershipState.SOURCE_CONFLICT,decision.ownership);assertEquals(NmeaSuppressionReason.SOURCE_CONFLICT,decision.suppression)
    }

    @Test fun offResetsAnyPendingTakeover(){
        val gate=PublicationOwnershipGate(1_000);gate.evaluate(PublicationPolicy.BACKUP,false,false,0);gate.evaluate(PublicationPolicy.OFF,false,false,500)
        assertEquals(PublisherOwnershipState.TAKEOVER_PENDING,gate.evaluate(PublicationPolicy.BACKUP,false,false,2_000).ownership)
    }
}
