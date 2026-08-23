package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.PressureTrendEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureTrendEstimatorTest {
    @Test fun regressionRequiresMostOfTheRequestedWindow(){
        val value=PressureTrendEstimator()
        repeat(40){minute->value.add(minute*60_000L,1_010.0-minute*.05)}
        assertNull(value.trend(39*60_000L,60*60_000L))
    }

    @Test fun regressionReportsWindowChangeOnlyAfterCoverageGate(){
        val value=PressureTrendEstimator()
        repeat(61){minute->value.add(minute*60_000L,1_010.0-minute*.05)}
        val trend=requireNotNull(value.trend(60*60_000L,60*60_000L))
        assertEquals(-3.0,trend.changeHpa,.05)
    }
}
