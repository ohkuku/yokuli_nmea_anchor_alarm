package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.WindAnchorEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindAnchorEvidenceTest {
    private fun sample(second:Int,heading:Double?=null,twd:Double=192.0,twa:Double?=12.0,awa:Double?=12.5,tws:Double?=12.0,aws:Double?=12.3,sequence:Long?=null)=WindAnchorEvidence.Sample(second*1_000L,25.0/110_540.0,0.0,.1,null,heading,twd,twa,awa,tws,aws,null,sequence)

    @Test fun circularWindowsTreatThreeHundredFiftyNineAndOneDegreeAsNeighbours(){
        val samples=(0..100).map{second->sample(second,heading=if(second%2==0)359.0 else 1.0,twa=null,awa=null,tws=null,aws=null)}
        val summary=WindAnchorEvidence.summarize(samples)
        assertTrue(summary.hasPhysicalEvidence)
        val heading=summary.observations.last().headingToAnchorDegrees
        assertTrue(heading<3.0||heading>357.0)
    }

    @Test fun awaTwaAndWindSpeedMustRepeatAcrossSeveralStableWindows(){
        assertTrue(WindAnchorEvidence.summarize((0..35).map(::sample)).observations.isEmpty())
        val summary=WindAnchorEvidence.summarize((0..100).map(::sample))
        assertTrue(summary.hasRepeatedWindEvidence)
        assertEquals(WindAnchorEvidence.Source.APPARENT_TRUE_MATCH,summary.observations.last().source)
    }

    @Test fun oneCachedWindSentenceRepeatedAcrossGpsFixesCountsOnlyOnce(){
        val summary=WindAnchorEvidence.summarize((0..100).map{sample(it,sequence=7L)})
        assertTrue(summary.observations.isEmpty())
    }

    @Test fun windHeadingMustAlsoPointFromTheBoatTowardTheCandidateCentre(){
        val summary=WindAnchorEvidence.summarize((0..100).map(::sample))
        val match=WindAnchorEvidence.centreMatch(summary,0.0,0.0)
        assertTrue(match.consistent)
        val wrong=WindAnchorEvidence.centreMatch(summary,50.0/110_540.0,0.0)
        assertTrue(!wrong.consistent)
    }
}
