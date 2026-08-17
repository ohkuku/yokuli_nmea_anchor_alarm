package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.testsupport.FaultScenarioRunner
import com.yokuli.anchorwatch.testsupport.ScenarioEvent
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in real-time soak. CI supplies YOKULI_SOAK_MINUTES; normal runs finish immediately. */
class RuntimeWallClockSoakTest{
    @Test fun repeatedSafetyCyclesRemainBoundedForRequestedWallClock(){
        val minutes=System.getenv("YOKULI_SOAK_MINUTES")?.toLongOrNull()?.coerceIn(0,60)?:0
        val deadline=System.nanoTime()+minutes*60_000_000_000L
        var cycles=0L
        do{
            val events=buildList{
                repeat(60){second->add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.Position(kotlin.math.sin(second/10.0)*12.0,kotlin.math.cos(second/10.0)*12.0));if(second==30)add(ScenarioEvent.GpsSpike(100.0))}
                add(ScenarioEvent.NmeaDisconnect);add(ScenarioEvent.Advance(1_000));add(ScenarioEvent.NmeaReconnect)
                add(ScenarioEvent.Depth(8.0));add(ScenarioEvent.DepthSpike(80.0));add(ScenarioEvent.Battery(50))
            }
            val result=FaultScenarioRunner().run(events)
            assertTrue(result.alarms.size<=1_024);cycles++
            if(minutes>0)Thread.sleep(250)
        }while(System.nanoTime()<deadline)
        assertTrue(cycles>=1)
    }
}
