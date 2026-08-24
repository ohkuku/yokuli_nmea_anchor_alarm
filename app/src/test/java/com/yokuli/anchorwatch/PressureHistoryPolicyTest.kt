package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.vessel.PressureHistoryPolicy
import org.junit.Assert.*
import org.junit.Test

class PressureHistoryPolicyTest{
    @Test fun everySourceUsesOneStableUtcMinuteBucket(){
        assertEquals(100L,PressureHistoryPolicy.bucket(6_000_001L))
        assertEquals(100L,PressureHistoryPolicy.bucket(6_059_999L))
        assertEquals(101L,PressureHistoryPolicy.bucket(6_060_000L))
    }

    @Test fun nullAndInvalidValuesCannotBecomePressureHistory(){
        assertTrue(PressureHistoryPolicy.validPressure(1_013.2))
        assertFalse(PressureHistoryPolicy.validPressure(Double.NaN))
        assertFalse(PressureHistoryPolicy.validPressure(0.0))
        assertFalse(PressureHistoryPolicy.validPressure(1_500.0))
    }

    @Test fun databaseRetentionIsLongerThanEveryTrendWindow(){
        assertEquals(6*60*60_000L,PressureHistoryPolicy.TREND_RETENTION_MILLIS)
        assertTrue(PressureHistoryPolicy.DATABASE_RETENTION_MILLIS>=7L*24*60*60_000L)
    }
}
