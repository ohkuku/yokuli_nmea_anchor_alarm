package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.report.StreamingCircularMean
import com.yokuli.anchorwatch.domain.report.StreamingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingStatisticsTest{
    @Test fun exactAggregatesAndQuantilesDoNotTreatMissingAsZero(){
        val stats=StreamingStatistics(32)
        listOf(Double.NaN,1.0,2.0,3.0).forEach(stats::add)
        assertEquals(3L,stats.count)
        assertEquals(2.0,stats.mean()!!,.0001)
        assertEquals(1.0,stats.minimum!!,.0001)
        assertEquals(3.0,stats.maximum!!,.0001)
        assertEquals(2.0,stats.quantile(.5)!!,.0001)
    }

    @Test fun reservoirStaysBoundedForLongTrips(){
        val stats=StreamingStatistics(64)
        repeat(100_000){stats.add(it.toDouble())}
        assertEquals(100_000L,stats.count)
        assertEquals(49_999.5,stats.mean()!!,.001)
        assertTrue(stats.quantile(.95)!! in 80_000.0..100_000.0)
    }

    @Test fun circularMeanHandlesNorthWrap(){
        val mean=StreamingCircularMean();mean.add(359.0);mean.add(1.0)
        val value=mean.degrees()!!
        assertTrue(value<.01||value>359.99)
    }
}
