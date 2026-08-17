package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.tide.SecondaryPortCorrection
import com.yokuli.anchorwatch.data.tide.TideHeightInterpolator
import com.yokuli.anchorwatch.data.tide.TidePredictionCsvParser
import com.yokuli.anchorwatch.data.tide.TideStationCatalog
import com.yokuli.anchorwatch.data.tide.SecondaryPortCsvParser
import com.yokuli.anchorwatch.domain.tide.TideInterpolationQuality
import com.yokuli.anchorwatch.domain.tide.TideExtreme
import com.yokuli.anchorwatch.domain.tide.TideExtremeType
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TidePredictionTest{
    private val officialAucklandFixture="""
        070,Auckland,36°51'S,174°46'E
        Based on constituent set with reference date:,31-Dec-2014
        Local Std or Daylight Time,Tidal heights in metres.
        1,Th,1,2026,05:47,3.1,11:51,0.9,18:06,3.1,,
        2,Fr,1,2026,00:19,0.6,06:49,3.2,12:51,0.8,19:09,3.2
    """.trimIndent()

    @Test fun linzCsvUsesPublishedLocalDaylightTimeAndConvertsToUtc(){
        val original=TimeZone.getDefault()
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val extremes=TidePredictionCsvParser.parse(officialAucklandFixture)
            assertEquals(7,extremes.size)
            assertEquals(Instant.parse("2025-12-31T16:47:00Z"),extremes.first().instantUtc)
            assertEquals(Instant.parse("2026-01-01T11:19:00Z"),extremes[3].instantUtc)
        }finally{TimeZone.setDefault(original)}
    }

    @Test fun officialBetweenExtremesCosineMethodReturnsMidRangeAtHalfTime(){
        val low=TideExtreme(Instant.parse("2026-01-01T00:00:00Z"),.6,TideExtremeType.LOW)
        val high=TideExtreme(Instant.parse("2026-01-01T06:00:00Z"),3.2,TideExtremeType.HIGH)
        val result=TideHeightInterpolator.heightAt(Instant.parse("2026-01-01T03:00:00Z"),low,high)!!
        assertEquals(1.9,result.heightMeters,.000001)
        assertEquals(TideHeightInterpolator.METHOD,result.method)
        assertEquals(TideInterpolationQuality.RECOMMENDED_5_TO_7_HOURS,result.quality)
    }

    @Test fun officialCosineWorkedValuesMatchQuarterAndThreeQuarterTimes(){
        val low=TideExtreme(Instant.parse("2026-01-01T00:00:00Z"),.6,TideExtremeType.LOW)
        val high=TideExtreme(Instant.parse("2026-01-01T06:00:00Z"),3.2,TideExtremeType.HIGH)
        assertEquals(.9807611845,TideHeightInterpolator.heightAt(Instant.parse("2026-01-01T01:30:00Z"),low,high)!!.heightMeters,.0000001)
        assertEquals(2.8192388155,TideHeightInterpolator.heightAt(Instant.parse("2026-01-01T04:30:00Z"),low,high)!!.heightMeters,.0000001)
    }

    @Test fun intervalOutsideLinzFiveToSevenHourGuidanceIsExplicitlyDegraded(){
        val low=TideExtreme(Instant.parse("2026-01-01T00:00:00Z"),.6,TideExtremeType.LOW)
        val high=TideExtreme(Instant.parse("2026-01-01T08:00:00Z"),3.2,TideExtremeType.HIGH)
        val result=TideHeightInterpolator.heightAt(Instant.parse("2026-01-01T04:00:00Z"),low,high)!!
        assertEquals(480,result.intervalMinutes)
        assertEquals(TideInterpolationQuality.OUTSIDE_RECOMMENDED_INTERVAL,result.quality)
    }

    @Test fun currentLinzSecondaryPortCsvLayoutTracksReferencePortAndSignedHhmm(){
        val csv="""
            ,,,,,,Time Differences,,,,'Mean Spring, Neap and Sea Level Heights'
            No.,Port,°,´,°,´,Mean,Range,Mean,Range,MHWS,MHWN,MLWN,MLWS,MSL,Ratio
            6394,Marsden Point,35,50,174,30,hhmm,hhmm to hhmm,hhmm,hhmm to hhmm,2.7,2.2,0.9,0.5,1.62,
            6409,Port Charles,36,31,175,28,-0039,-0059 to -0026,-0035,-0055 to -0021,2.3,1.9,0.6,0.1,1.2,1.00
        """.trimIndent()
        val values=SecondaryPortCsvParser.parse(csv)
        val port=values.single{it.id=="port-charles"}
        assertEquals("marsden-point",port.referenceStationId)
        assertEquals(-39,port.highWaterOffsetMinutes)
        assertEquals(-35,port.lowWaterOffsetMinutes)
        assertEquals(1.62,port.referenceMeanSeaLevelMeters?:Double.NaN,.0001)
        assertEquals(-36.516666,port.latitude,.00001)
    }

    @Test fun portCharlesUsesOfficialMarsdenPointMeanOffsetsAndRangeRatio(){
        val reference=listOf(
            TideExtreme(Instant.parse("2026-08-17T00:00:00Z"),3.0,TideExtremeType.HIGH),
            TideExtreme(Instant.parse("2026-08-17T06:00:00Z"),1.0,TideExtremeType.LOW),
        )
        val portCharles=requireNotNull(TideStationCatalog.byId("port-charles"))
        val corrected=SecondaryPortCorrection.apply(reference,portCharles)
        assertEquals(Instant.parse("2026-08-16T23:21:00Z"),corrected[0].instantUtc)
        assertEquals(2.6015,corrected[0].heightMetersAboveChartDatum,.000001)
        assertEquals(Instant.parse("2026-08-17T05:25:00Z"),corrected[1].instantUtc)
        assertEquals(.7015,corrected[1].heightMetersAboveChartDatum,.000001)
        assertTrue(corrected[0].heightMetersAboveChartDatum>corrected[1].heightMetersAboveChartDatum)
    }

    @Test fun malformedRowsAndMissingFourthTideDoNotInventExtremes(){
        val csv="""
            Local Std or Daylight Time,Tidal heights in metres.
            30,Su,8,2026,01:10,3.0,07:20,broken,13:40,3.1,,
            invalid,row
        """.trimIndent()
        val rows=TidePredictionCsvParser.parse(csv)
        assertEquals(2,rows.size)
        assertTrue(rows.zipWithNext().all{(a,b)->a.instantUtc<b.instantUtc})
    }

    @Test fun dstBoundaryUsesAucklandZoneRulesRatherThanDeviceTimeZone(){
        val csv="""
            Local Std or Daylight Time,Tidal heights in metres.
            5,Su,4,2026,01:30,2.8,04:30,0.7,,,,
        """.trimIndent()
        val rows=TidePredictionCsvParser.parse(csv,"Pacific/Auckland")
        assertEquals(Instant.parse("2026-04-04T12:30:00Z"),rows[0].instantUtc)
        assertEquals(Instant.parse("2026-04-04T16:30:00Z"),rows[1].instantUtc)
    }

    @Test fun interpolationRefusesTimesOutsideItsBoundingExtremes(){
        val low=TideExtreme(Instant.parse("2026-01-01T00:00:00Z"),.6,TideExtremeType.LOW)
        val high=TideExtreme(Instant.parse("2026-01-01T06:00:00Z"),3.2,TideExtremeType.HIGH)
        assertEquals(null,TideHeightInterpolator.heightAt(Instant.parse("2025-12-31T23:59:00Z"),low,high))
    }
}
