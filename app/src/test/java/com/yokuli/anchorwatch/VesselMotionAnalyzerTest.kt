package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.vessel.*
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class VesselMotionAnalyzerTest{
    @Test fun boundedSyntheticRollProducesExplicitMotionScoreAndStablePeriod(){
        val analyzer=VesselMotionAnalyzer();var result=VesselMotion()
        for(index in 0..6_000){val seconds=index*.05;val heel=5.0*sin(2*Math.PI*seconds/8.0);val rate=5.0*2*Math.PI/8.0*kotlin.math.cos(2*Math.PI*seconds/8.0);result=analyzer.add(VesselMotionPoint((seconds*1_000).toLong(),heel,1.0*sin(2*Math.PI*seconds/5.0),rate,1.2,0.04))}
        assertNotNull(result.score);assertTrue(result.score!! in 1.0..100.0);assertEquals(8.0,result.dominantRollPeriodSeconds!!,.4);assertTrue(result.rollPeriodConfidence in setOf(MotionPeriodConfidence.MEDIUM,MotionPeriodConfidence.HIGH))
    }
    @Test fun flatOrShortEvidenceNeverManufacturesAPeriod(){val analyzer=VesselMotionAnalyzer();var result=VesselMotion();repeat(100){index->result=analyzer.add(VesselMotionPoint(index*100L,.1,.1,0.0,0.0,.01))};assertNull(result.dominantRollPeriodSeconds)}
    @Test fun impactCandidatesUseThresholdAndRefractoryWindow(){
        val analyzer=VesselMotionAnalyzer()
        assertNull(analyzer.add(VesselMotionPoint(0,0.0,0.0,0.0,0.0,.59)).impactPeakG)
        assertEquals(.7,analyzer.add(VesselMotionPoint(100,0.0,0.0,0.0,0.0,.7)).impactPeakG!!,.001)
        assertNull(analyzer.add(VesselMotionPoint(1_000,0.0,0.0,0.0,0.0,.9)).impactPeakG)
        assertEquals(.8,analyzer.add(VesselMotionPoint(1_600,0.0,0.0,0.0,0.0,.8)).impactPeakG!!,.001)
    }
}
