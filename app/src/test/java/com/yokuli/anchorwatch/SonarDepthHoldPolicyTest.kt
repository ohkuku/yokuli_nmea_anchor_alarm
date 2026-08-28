package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState
import com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldTracker
import com.yokuli.anchorwatch.domain.sonar.SonarHeldPosition
import com.yokuli.anchorwatch.data.nmea.Nmea0183Parser
import com.yokuli.anchorwatch.data.nmea.NmeaUpdateRetainer
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthProvenance
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.domain.condition.DepthGuardEngine
import com.yokuli.anchorwatch.domain.condition.DepthGuardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonarDepthHoldPolicyTest {
    private fun fix(eastMeters:Double,time:Long)=NavigationFix(0.0,eastMeters/111_320.0,receivedElapsedRealtime=time,positionProvider=PositionProvider.NMEA,sourceSentence="GGA",valid=true)
    private fun observation(depth:Double,time:Long)=DepthObservation(depth,null,DepthReference.BELOW_TRANSDUCER,DepthSentenceType.DBT,time,"DBT")
    @Test fun neverSeenDepthCannotRecord(){val value=SonarDepthHoldPolicy.evaluate(false,0,0.0);assertEquals(SonarDepthHoldState.NO_DEPTH,value.state);assertFalse(value.mayRecord)}
    @Test fun invalidSentenceCanNeverBecomeTheFirstHeldDepth(){assertFalse(SonarDepthHoldPolicy.isValidRealDepth(observation(0.0,1_000)));assertFalse(SonarDepthHoldPolicy.isValidRealDepth(observation(12_001.0,1_000)));assertTrue(SonarDepthHoldPolicy.isValidRealDepth(observation(6.1,1_000)))}
    @Test fun delayedDepthFromPreviousConnectionCannotRepopulateHold(){assertFalse(SonarDepthHoldPolicy.belongsToCurrentConnection(9_999,10_000,NmeaConnectionState.CONNECTED));assertFalse(SonarDepthHoldPolicy.belongsToCurrentConnection(10_001,10_000,NmeaConnectionState.RECONNECTING));assertTrue(SonarDepthHoldPolicy.belongsToCurrentConnection(10_001,10_000,NmeaConnectionState.CONNECTED_NO_FIX))}
    @Test fun liveAndHeldDepthRemainUsable(){assertEquals(SonarDepthHoldState.LIVE,SonarDepthHoldPolicy.evaluate(true,2_000,0.0).state);assertTrue(SonarDepthHoldPolicy.evaluate(true,2_001,2.0).mayRecord)}
    @Test fun warningRemainsUsable(){val byTime=SonarDepthHoldPolicy.evaluate(true,120_001,10.0);assertEquals(SonarDepthHoldState.WARNING,byTime.state);assertTrue(byTime.mayRecord);assertEquals(SonarDepthHoldState.WARNING,SonarDepthHoldPolicy.evaluate(true,10_000,200.1).state)}
    @Test fun timeExpiryRequiresStop(){val value=SonarDepthHoldPolicy.evaluate(true,300_001,20.0);assertEquals(SonarDepthHoldState.EXPIRED_TIME,value.state);assertTrue(value.mustStop);assertFalse(value.mayRecord)}
    @Test fun distanceExpiryRequiresStop(){val value=SonarDepthHoldPolicy.evaluate(true,10_000,500.1);assertEquals(SonarDepthHoldState.EXPIRED_DISTANCE,value.state);assertTrue(value.mustStop);assertFalse(value.mayRecord)}
    @Test fun blankDbtHeartbeatCannotResetTravelledDistanceGuard(){
        val parser=Nmea0183Parser();val retainer=NmeaUpdateRetainer()
        val real=retainer.accept(parser.parse("\$SDDBT,20.0,f,6.1,M,3.3,F",false,1_000)!!,1_000,"real")
        val blank=retainer.accept(parser.parse("\$SDDBT,,f,,M,,F",false,5_000)!!,5_000,"blank")
        assertEquals(1_000,blank.depthObservation!!.receivedElapsedRealtime)
        val tracker=SonarDepthHoldTracker();tracker.acceptRealDepth(real.depthObservation!!,DepthProvenance.from(real.depthObservation,0.0),7,fix(0.0,1_000))
        var last: SonarHeldPosition? = null
        for(meters in 10..510 step 10)last=tracker.acceptPosition(fix(meters.toDouble(),5_000L+meters))
        assertNotNull(last);assertEquals(SonarDepthHoldState.EXPIRED_DISTANCE,last!!.decision.state)
    }
    @Test fun oneRealDepthDrivesSeveralGpsPositionsWithExplicitAge(){
        val tracker=SonarDepthHoldTracker();val observed=observation(6.1,1_000);tracker.acceptRealDepth(observed,DepthProvenance.from(observed,0.0),7,fix(0.0,1_000))
        val a=tracker.acceptPosition(fix(2.0,2_000))!!;val b=tracker.acceptPosition(fix(4.0,4_000))!!;val c=tracker.acceptPosition(fix(6.0,7_000))!!
        assertEquals(6.1,a.depth.provenance.finalDepthMeters,0.0);assertEquals(6.1,b.depth.provenance.finalDepthMeters,0.0);assertEquals(6.1,c.depth.provenance.finalDepthMeters,0.0)
        assertEquals(6_000,c.ageMillis);assertEquals(SonarDepthHoldState.HELD,c.decision.state)
    }
    @Test fun newRealDepthResetsAgeDistanceAndValue(){
        val tracker=SonarDepthHoldTracker();val old=observation(6.1,1_000);tracker.acceptRealDepth(old,DepthProvenance.from(old,0.0),3,fix(0.0,1_000));tracker.acceptPosition(fix(80.0,100_000))
        val fresh=observation(6.2,101_000);tracker.acceptRealDepth(fresh,DepthProvenance.from(fresh,0.0),3,fix(80.0,101_000));val result=tracker.acceptPosition(fix(81.0,101_500))!!
        assertEquals(6.2,result.depth.provenance.finalDepthMeters,0.0);assertEquals(500,result.ageMillis);assertTrue(result.depth.travelledMeters<2.0);assertEquals(SonarDepthHoldState.LIVE,result.decision.state)
    }
    @Test fun samePacketPositionMeasuredJustBeforeDepthStillPairsAfterCollectorReordering(){
        val tracker=SonarDepthHoldTracker();val depth=observation(6.1,10_003)
        tracker.acceptRealDepth(depth,DepthProvenance.from(depth,0.0),7,null)
        val paired=tracker.acceptPosition(fix(0.0,10_000))
        assertNotNull(paired);assertEquals(0L,paired!!.ageMillis);assertEquals(SonarDepthHoldState.LIVE,paired.decision.state)
        assertTrue(tracker.acceptPosition(fix(0.0,8_002))==null)
    }
    @Test fun reconnectClearsOldDepthButDelayedCollectorCannotClearNewGenerationDepth(){
        val tracker=SonarDepthHoldTracker();val old=observation(6.1,1_000);tracker.acceptRealDepth(old,DepthProvenance.from(old,0.0),2,null);assertTrue(tracker.clearForConnectionGeneration(3));assertTrue(tracker.current==null)
        val fresh=observation(6.4,2_000);tracker.acceptRealDepth(fresh,DepthProvenance.from(fresh,0.0),3,null);assertFalse(tracker.clearForConnectionGeneration(3));assertEquals(6.4,tracker.current!!.provenance.finalDepthMeters,0.0)
    }
    @Test fun oneHugeGpsStepDoesNotTriggerDistanceExpiry(){
        val tracker=SonarDepthHoldTracker();val depth=observation(6.1,1_000);tracker.acceptRealDepth(depth,DepthProvenance.from(depth,0.0),1,fix(0.0,1_000));val result=tracker.acceptPosition(fix(700.0,2_000))!!;assertTrue(result.ignoredLargeStepMeters!=null);assertEquals(0.0,result.depth.travelledMeters,0.0);assertFalse(result.decision.mustStop)
    }
    @Test fun heldMappingNeverRefreshesTheAnchorDepthGuardClock(){
        val guard=DepthGuardEngine();val config=ConditionGuardConfig(depthGuardEnabled=true,shallowDepthAlarmMeters=2.0)
        listOf(0L,1_000L,2_000L).forEach{received->guard.update(config,6.1,received,received)}
        assertEquals(DepthGuardStatus.DATA_UNAVAILABLE,guard.update(config,null,null,12_001L).status)
    }
}
