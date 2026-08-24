package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnchorageMatchEnginesTest {
    @Test fun spotMatchUsesUncertaintyAndPlaceIdentityRatherThanFixed75m(){
        val candidate=AnchorageSpotMatchCandidate(-36.8,175.1,60.0,50.0)
        val near=AnchorageSpotMatchCandidate(-36.8007,175.1,30.0,50.0)
        assertEquals(AnchorageSpotMatch.LIKELY_SAME,AnchorageSpotMatchEngine.evaluate(candidate,near,true).match)
        assertEquals(AnchorageSpotMatch.DISTINCT,AnchorageSpotMatchEngine.evaluate(candidate,near,false).match)
    }

    @Test fun distinctSpotsCanBeCloseInsideTheSamePlace(){
        val a=AnchorageSpotMatchCandidate(-36.8,175.1,3.0,40.0)
        val b=AnchorageSpotMatchCandidate(-36.79945,175.1,3.0,40.0)
        assertEquals(AnchorageSpotMatch.DISTINCT,AnchorageSpotMatchEngine.evaluate(a,b,true).match)
    }

    @Test fun regionRankingPrefersContainingMarineFeatureButStillReturnsAllCandidates()=runBlocking{
        val broad=AnchorageRegionCandidate("LINZ","island","Aotea",null,AnchorageRegionFeatureType.ISLAND,null,-36.2,175.4,true,0.0,true)
        val bay=AnchorageRegionCandidate("LINZ","bay","Smokehouse Bay",null,AnchorageRegionFeatureType.BAY,null,-36.2,175.4,true,0.0,true)
        val failing=object:AnchorageRegionProvider{override val providerId="offline";override suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double)=Result.failure<List<AnchorageRegionCandidate>>(IllegalStateException("offline"))}
        val cached=object:AnchorageRegionProvider{override val providerId="cache";override suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double)=Result.success(listOf(broad,bay))}
        val result=AnchorageRegionResolver(listOf(failing,cached)).resolve(-36.2,175.4)
        assertEquals("bay",result.first().externalId);assertEquals(2,result.size)
    }
}
