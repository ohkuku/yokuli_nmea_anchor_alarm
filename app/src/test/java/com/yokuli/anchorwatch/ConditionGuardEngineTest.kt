package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.*
import org.junit.Assert.*
import org.junit.Test

class ConditionGuardEngineTest{
    private val depthConfig=ConditionGuardConfig(depthGuardEnabled=true,shallowDepthAlarmMeters=2.5)
    private val windConfig=ConditionGuardConfig(windGuardEnabled=true,windWarningKnots=25.0,windAlarmKnots=35.0)

    @Test fun shallowRequiresFilteredPersistenceAndClearsWithHysteresis(){
        val engine=DepthGuardEngine();listOf(0L,1_000,2_000).forEach{engine.update(depthConfig,2.0,it,it)}
        assertFalse(engine.update(depthConfig,2.0,5_999,5_999).alarmActive)
        assertEquals(DepthGuardStatus.SHALLOW_ALARM,engine.update(depthConfig,2.0,7_000,7_000).status)
        (8_000L..21_000L step 1_000L).forEach{engine.update(depthConfig,3.0,it,it)}
        assertEquals(DepthGuardStatus.MONITORING,engine.update(depthConfig,3.0,22_000,22_000).status)
    }

    @Test fun depthLostIsSingleStateAndFreshRestoreRebuildsFilter(){
        val engine=DepthGuardEngine();listOf(0L,1_000,2_000).forEach{engine.update(depthConfig,6.0,it,it)}
        assertEquals(DepthGuardStatus.DATA_UNAVAILABLE,engine.update(depthConfig,null,null,13_001).status)
        assertEquals(DepthGuardStatus.DATA_UNAVAILABLE,engine.update(depthConfig,null,null,30_000).status)
        assertEquals(DepthGuardStatus.WAITING_FOR_DATA,engine.update(depthConfig,6.0,31_000,31_000).status)
    }

    @Test fun oneDepthSpikeCannotAlarmAndDeepBoundaryIsExplicitlyOptional(){
        val shallow=DepthGuardEngine();shallow.update(depthConfig,6.0,0,0);shallow.update(depthConfig,1.0,1_000,1_000)
        assertEquals(DepthGuardStatus.MONITORING,shallow.update(depthConfig,6.0,2_000,2_000).status)
        val withoutDeep=DepthGuardEngine();(0L..8_000L step 1_000L).forEach{withoutDeep.update(depthConfig,30.0,it,it)}
        assertEquals(DepthGuardStatus.MONITORING,withoutDeep.update(depthConfig,30.0,9_000,9_000).status)
        val withDeep=DepthGuardEngine();val enabled=depthConfig.copy(deepDepthAlarmMeters=15.0);var state=DepthGuardSnapshot()
        (0L..8_000L step 1_000L).forEach{state=withDeep.update(enabled,20.0,it,it)}
        assertEquals(DepthGuardStatus.DEEP_ALARM,state.status)
    }

    @Test fun windAlarmAndSourceSwitchRequireNewSamples(){
        val engine=WindSpeedGuardEngine();(0L..7_000L step 1_000L).forEach{engine.update(windConfig,38.0,WindSpeedSource.TRUE,it,it)}
        assertEquals(WindSpeedGuardStatus.ALARM,engine.update(windConfig,38.0,WindSpeedSource.TRUE,8_000,8_000).status)
        assertEquals(WindSpeedGuardStatus.WAITING_FOR_DATA,engine.update(windConfig,40.0,WindSpeedSource.APPARENT,9_000,9_000).status)
    }

    @Test fun singleGustDoesNotAlarmAndWarningNeedsTenSeconds(){
        val gust=WindSpeedGuardEngine();gust.update(windConfig,15.0,WindSpeedSource.TRUE,0,0);gust.update(windConfig,60.0,WindSpeedSource.TRUE,1_000,1_000)
        assertEquals(WindSpeedGuardStatus.MONITORING,gust.update(windConfig,15.0,WindSpeedSource.TRUE,2_000,2_000).status)
        val sustained=WindSpeedGuardEngine();var state=WindSpeedGuardSnapshot()
        (0L..13_000L step 1_000L).forEach{state=sustained.update(windConfig,28.0,WindSpeedSource.TRUE,it,it)}
        assertEquals(WindSpeedGuardStatus.WARNING,state.status)
    }

    @Test fun staleWindDropsAlarmAndFreshReturnNeedsANewWindow(){
        val engine=WindSpeedGuardEngine();(0L..8_000L step 1_000L).forEach{engine.update(windConfig,38.0,WindSpeedSource.TRUE,it,it)}
        assertTrue(engine.update(windConfig,null,WindSpeedSource.TRUE,null,14_000).alarmActive)
        assertEquals(WindSpeedGuardStatus.DATA_UNAVAILABLE,engine.update(windConfig,null,WindSpeedSource.TRUE,null,20_000).status)
        engine.update(windConfig,38.0,WindSpeedSource.TRUE,21_000,21_000);engine.update(windConfig,38.0,WindSpeedSource.TRUE,22_000,22_000)
        assertNotEquals(WindSpeedGuardStatus.ALARM,engine.update(windConfig,38.0,WindSpeedSource.TRUE,23_000,23_000).status)
    }

    @Test fun staleDepthCannotClearAnAlarmBeforeDataLossIsDeclared(){
        val engine=DepthGuardEngine();(0L..8_000L step 1_000L).forEach{engine.update(depthConfig,2.0,it,it)}
        assertTrue(engine.update(depthConfig,null,null,12_000).alarmActive)
        assertEquals(DepthGuardStatus.DATA_UNAVAILABLE,engine.update(depthConfig,null,null,19_001).status)
    }

    @Test fun stableCircularBaselineNeedsTwoMinutesThenShiftPersists(){
        val config=ConditionGuardConfig(windShiftEnabled=true,windShiftThresholdDegrees=70.0)
        val engine=WindShiftGuardEngine();var snapshot=WindShiftGuardSnapshot()
        for(index in 0..24){val at=index*5_000L;snapshot=engine.update(config,240.0+(index%3-1),TrueWindDirectionSource.MWD,at,at)}
        assertNotNull(snapshot.baselineDirectionDegrees)
        var now=130_000L
        repeat(18){now+=5_000;snapshot=engine.update(config,320.0,TrueWindDirectionSource.MWD,now,now)}
        assertEquals(WindShiftGuardStatus.ALARM,snapshot.status)
    }

    @Test fun circularBaselineHandlesNorthAndBriefShiftDoesNotAlarm(){
        val config=ConditionGuardConfig(windShiftEnabled=true,windShiftThresholdDegrees=70.0)
        val engine=WindShiftGuardEngine();var state=WindShiftGuardSnapshot()
        for(index in 0..24){val at=index*5_000L;state=engine.update(config,if(index%2==0)359.0 else 1.0,TrueWindDirectionSource.MWD,at,at)}
        val baseline=state.baselineDirectionDegrees?:Double.NaN;assertTrue(baseline<3.0||baseline>357.0)
        for(index in 1..10){val at=120_000L+index*2_000L;state=engine.update(config,80.0,TrueWindDirectionSource.MWD,at,at)}
        assertNotEquals(WindShiftGuardStatus.ALARM,state.status)
        engine.reset();assertEquals(WindShiftGuardStatus.LEARNING_BASELINE,engine.update(config,10.0,TrueWindDirectionSource.MWD,200_000,200_000).status)
    }
}
