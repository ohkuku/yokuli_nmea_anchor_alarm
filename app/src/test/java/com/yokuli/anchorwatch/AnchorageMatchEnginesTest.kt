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

    @Test fun anEightyMetreSpotIsAllowedWhenUncertaintyDoesNotOverlap(){
        val first=AnchorageSpotMatchCandidate(-36.8,175.1,3.0,40.0)
        val secondPoint=com.yokuli.anchorwatch.domain.anchor.AnchorGeometry.project(-36.8,175.1,90.0,80.0)
        val second=AnchorageSpotMatchCandidate(secondPoint.first,secondPoint.second,3.0,40.0)
        val result=AnchorageSpotMatchEngine.evaluate(first,second,true)
        assertEquals(AnchorageSpotMatch.DISTINCT,result.match)
        assertEquals(80.0,result.distanceMeters,1.0)
    }

    @Test fun tenThousandPlacesAggregateBeforeMapMarkersAreCreated(){
        val places=(1L..10_000L).map{index->
            val row=(index/100).toDouble()
            AnchorageMapPlace(index,-45.0+(row%90)*.9,-179.0+(index%100)*3.5,"P$index",false,AnchoragePlanningStatus.NONE,0,1)
        }
        val world=AnchorageVisualClusterer.aggregate(places,4f)
        assertEquals(10_000,world.sumOf{it.count})
        assertTrue(world.size<places.size/2)
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
