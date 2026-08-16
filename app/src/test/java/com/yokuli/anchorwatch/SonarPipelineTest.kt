package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.Nmea0183Parser
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.domain.sonar.DepthCandidate
import com.yokuli.anchorwatch.domain.sonar.DepthDisposition
import com.yokuli.anchorwatch.domain.sonar.DepthIntegrityFilter
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import com.yokuli.anchorwatch.domain.sonar.DepthNormalizer
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.SonarGridSample
import com.yokuli.anchorwatch.data.database.SonarGridCellEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonarPipelineTest {
    @Test fun dptAndDbtRetainSentenceTypeReferenceAndOffset(){
        val parser=Nmea0183Parser()
        val dpt=parser.parse(NmeaChecksum.append("SDDPT,12.3,0.8"),true,100)!!.depthObservation!!
        assertEquals(DepthSentenceType.DPT,dpt.sentenceType);assertEquals(DepthReference.BELOW_SURFACE,dpt.reference);assertEquals(.8,dpt.offsetMeters!!,.001);assertEquals(13.1,dpt.belowSurfaceMeters(2.0),.001)
        val dbt=parser.parse(NmeaChecksum.append("SDDBT,40.4,f,12.3,M,6.7,F"),true,200)!!.depthObservation!!
        assertEquals(DepthSentenceType.DBT,dbt.sentenceType);assertEquals(DepthReference.BELOW_TRANSDUCER,dbt.reference);assertEquals(14.3,dbt.belowSurfaceMeters(2.0),.001)
        val noOffset=parser.parse(NmeaChecksum.append("SDDPT,7.2"),true,300)!!.depthObservation!!
        assertEquals(7.2,noOffset.rawDepthMeters,.001);assertTrue(noOffset.offsetMeters==null);assertEquals(DepthReference.BELOW_TRANSDUCER,noOffset.reference)
        assertTrue(parser.parse(NmeaChecksum.append("SDDPT,not-a-depth"),true,400)==null)
        assertTrue(parser.parse("\$SDDPT,7.2*00",true,500)==null)
        assertTrue(parser.parse(NmeaChecksum.append("SDDBT,40.4,f,,M,6.7,F"),true,600)==null)
    }

    @Test fun isolatedSpikeIsQuarantinedButThreePointSlopeIsAccepted(){
        val filter=DepthIntegrityFilter();fun sample(depth:Double,time:Long)=DepthCandidate(0.0,0.0,time,depth,depth,2.0)
        assertEquals(DepthDisposition.ACCEPTED,filter.evaluate(sample(10.0,0)).disposition)
        assertEquals(DepthDisposition.QUARANTINED_SPIKE,filter.evaluate(sample(20.0,1_000)).disposition)
        assertEquals(DepthDisposition.QUARANTINED_SPIKE,filter.evaluate(sample(30.0,2_000)).disposition)
        val released=filter.evaluate(sample(40.0,3_000));assertEquals(DepthDisposition.ACCEPTED_STEEP_SLOPE,released.disposition);assertEquals(listOf(1_000L,2_000L,3_000L),released.releasedElapsedTimestamps)
    }

    @Test fun tideOffNeverPretendsToBeNormalizedAndManualUsesReferenceGeometry(){
        val parser=Nmea0183Parser();val observation=parser.parse(NmeaChecksum.append("SDDPT,12.0,-1.0"),true,100)!!.depthObservation!!
        val off=DepthNormalizer.normalize(observation,DepthReference.UNKNOWN,2.0,1.0,TideMode.OFF,1.5)
        assertEquals(11.0,off.measuredDepthMeters,.001);assertEquals(DepthReference.BELOW_KEEL,off.measuredReference);assertTrue(off.chartDatumDepthMeters==null)
        val manual=DepthNormalizer.normalize(observation,DepthReference.UNKNOWN,2.0,1.0,TideMode.MANUAL,1.5)
        assertEquals(12.5,manual.chartDatumDepthMeters!!,.001)
    }

    @Test fun gridUsesRobustCellDepthAndLabelsShortRangeIdw(){
        fun point(east:Double,north:Double,depth:Double)=SonarGridSample(north/110_540.0,east/111_320.0,depth,2.0)
        val measured=SonarGrid.build(listOf(point(1.0,1.0,10.0),point(1.5,1.0,10.2),point(1.0,1.5,100.0)))
        val inspected=measured.inspect(1.0/110_540.0,1.0/111_320.0)!!
        assertTrue(inspected.measured);assertTrue(inspected.depthMeters<11.0)
        val interpolation=SonarGrid.build(listOf(point(6.0,0.0,10.0),point(-6.0,0.0,12.0),point(0.0,6.0,11.0))).inspect(0.0,0.0)
        assertNotNull(interpolation);assertFalse(interpolation!!.measured);assertTrue(interpolation.depthMeters in 9.0..13.0)
        assertTrue(SonarGrid.build(listOf(point(0.0,0.0,10.0))).inspect(0.0,30.0/111_320.0)==null)
        val indexed=SonarGrid.build(listOf(point(-321.0,-321.0,7.0),point(321.0,321.0,9.0)))
        val projected=SonarGrid.project(-321.0/110_540.0,-321.0/111_320.0)
        assertEquals(1,indexed.cellsInBounds(projected.first-10,projected.first+10,projected.second-10,projected.second+10).size)
    }

    @Test fun persistedGridAppliesOnlyTheChangedCellInPlace(){
        val first=SonarGridCellEntity("SURVEY",7,10,20,5.0,8.0,.2,3,1)
        val grid=SonarGrid.fromPersisted(listOf(first))
        val identity=grid
        grid.applyCell(11,20,SonarGridCellEntity("SURVEY",7,11,20,5.0,9.0,.3,2,2))
        assertTrue(grid===identity);assertEquals(2,grid.cells.size);assertEquals(9.0,grid.cells[11L to 20L]!!.depthMeters,.001)
        grid.applyCell(10,20,null)
        assertEquals(setOf(11L to 20L),grid.cells.keys)
    }
}
