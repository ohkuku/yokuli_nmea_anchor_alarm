package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.linz.HydroFeatureKind
import com.yokuli.anchorwatch.data.linz.GeoPoint
import com.yokuli.anchorwatch.data.linz.HydroFeature
import com.yokuli.anchorwatch.data.linz.HydroGeometry
import com.yokuli.anchorwatch.data.linz.LinzDepthPresentation
import com.yokuli.anchorwatch.data.linz.LinzDepthReference
import com.yokuli.anchorwatch.data.linz.LinzDepthReferenceRepository
import com.yokuli.anchorwatch.data.linz.LinzDepthStatus
import com.yokuli.anchorwatch.data.linz.LinzHydroFeatureParser
import com.yokuli.anchorwatch.data.linz.LinzHydroSelector
import com.yokuli.anchorwatch.data.linz.LinzQueryThrottle
import com.yokuli.anchorwatch.data.linz.LinzFinalResultCachePolicy
import com.yokuli.anchorwatch.data.linz.LinzWfsClient
import com.yokuli.anchorwatch.data.linz.LinzWfsResult
import com.yokuli.anchorwatch.data.database.LinzDepthCacheDao
import com.yokuli.anchorwatch.data.database.LinzDepthCacheEntity
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinzDepthReferenceTest {
    @Test fun parsesOfficialDepthFieldsAndGeoJsonGeometry() {
        val sounding=LinzHydroFeatureParser.parse(feature("layer-50858.1", "Point", "[174.7633,-36.8485]", "\"depth\":6.4"),HydroFeatureKind.SOUNDING).single()
        assertEquals(6.4,sounding.depth?:Double.NaN,.001)

        val contour=LinzHydroFeatureParser.parse(feature("layer-50672.2", "LineString", "[[174.762,-36.849],[174.764,-36.849]]", "\"valdco\":10"),HydroFeatureKind.DEPTH_CONTOUR).single()
        assertEquals(10.0,contour.depth?:Double.NaN,.001)

        val area=LinzHydroFeatureParser.parse(feature("layer-50671.3", "Polygon", "[[[174.762,-36.850],[174.765,-36.850],[174.765,-36.847],[174.762,-36.847],[174.762,-36.850]]]", "\"drval1\":5,\"drval2\":10"),HydroFeatureKind.DEPTH_AREA).single()
        assertEquals(5.0,area.minDepth?:Double.NaN,.001)
        assertEquals(10.0,area.maxDepth?:Double.NaN,.001)
    }

    @Test fun selectorFindsContainingAreaAndNearestFeaturesAndDeduplicatesLayers() {
        val soundings=LinzHydroFeatureParser.parse(collection("""
            {"type":"Feature","id":"layer-50858.same","properties":{"depth":6.4},"geometry":{"type":"Point","coordinates":[174.76331,-36.84850]}},
            {"type":"Feature","id":"layer-50858.same","properties":{"depth":99},"geometry":{"type":"Point","coordinates":[174.8,-36.8]}}
        """),HydroFeatureKind.SOUNDING)
        assertEquals(1,soundings.size)
        val areas=LinzHydroFeatureParser.parse(feature("layer-50671.3", "Polygon", "[[[174.762,-36.850],[174.765,-36.850],[174.765,-36.847],[174.762,-36.847],[174.762,-36.850]]]", "\"drval1\":5,\"drval2\":10"),HydroFeatureKind.DEPTH_AREA)
        val contours=LinzHydroFeatureParser.parse(feature("layer-50672.2", "LineString", "[[174.762,-36.8486],[174.765,-36.8486]]", "\"valdco\":10"),HydroFeatureKind.DEPTH_CONTOUR)
        val selected=LinzHydroSelector.select(-36.8485,174.7633,123L,soundings,areas,contours,listOf("50858","50671","50672"))
        assertEquals(LinzDepthStatus.AVAILABLE,selected.status)
        assertEquals(5.0,selected.depthAreaMinMeters?:Double.NaN,.001)
        assertEquals(6.4,selected.nearestSoundingDepthMeters?:Double.NaN,.001)
        assertTrue((selected.nearestSoundingDistanceMeters?:999.0)<5.0)
        assertEquals(10.0,selected.nearestContourDepthMeters?:Double.NaN,.001)
        assertNotNull(selected.nearestContourDistanceMeters)
    }

    @Test fun distantSoundingNeverTurnsAnAreaRangeIntoFakeCurrentDepth() {
        val value=LinzDepthReference(
            depthAreaMinMeters=5.0,
            depthAreaMaxMeters=10.0,
            nearestSoundingDepthMeters=6.0,
            nearestSoundingDistanceMeters=180.0,
            status=LinzDepthStatus.AVAILABLE,
        )
        val text=LinzDepthPresentation.text(value)
        assertEquals("5–10 m",text.primary)
        assertTrue(text.secondary?.contains("180 m away")==true)
    }

    @Test fun oneHertzSmallMovementIsThrottledButDistanceOrTimeRefreshes() {
        val throttle=LinzQueryThrottle()
        assertTrue(throttle.shouldQuery(-36.8485,174.7633,0L))
        throttle.record(-36.8485,174.7633,0L)
        repeat(59){second->
            val lessThanTenMeters=(second+1)*0.0000008
            assertTrue(!throttle.shouldQuery(-36.8485,174.7633+lessThanTenMeters,(second+1)*1_000L))
        }
        assertTrue(throttle.shouldQuery(-36.8485,174.7633,60_000L))
        assertTrue(throttle.shouldQuery(-36.8485,174.7637,10_000L))
    }

    @Test fun finalResultCacheIsOnlyReusableNearItsOriginalQueryPosition() {
        val cached=LinzDepthCacheEntity("cell",-36.8485,174.7633,100_000L,status=LinzDepthStatus.AVAILABLE.name)
        assertTrue(LinzFinalResultCachePolicy.canReuseFresh(cached,-36.8485,174.76331,100_100L,LinzDepthReferenceRepository.CACHE_TTL_MILLIS))
        assertTrue(!LinzFinalResultCachePolicy.canReuseFresh(cached,-36.8485,174.7640,100_100L,LinzDepthReferenceRepository.CACHE_TTL_MILLIS))
        assertTrue(LinzFinalResultCachePolicy.isNear(cached,-36.8485,174.76331))
    }

    @Test fun movingWithinSameHundredMeterCellDoesNotReusePositionSpecificResult()=runBlocking {
        val cache=FakeCache();val client=FakeClient();val repository=LinzDepthReferenceRepository(client,cache)
        repository.refresh(-36.8485,174.7633,nowWall=100_000L,nowElapsed=0L)
        repository.refresh(-36.8485,174.7637,nowWall=101_000L,nowElapsed=1_000L)
        assertEquals(2,client.queryCount)
        assertEquals(174.7637,repository.state.value.queriedLongitude,.000001)
    }

    @Test fun offlineUsesStaleSpatialCacheAndNoCacheStaysUnavailable()=runBlocking {
        val cache=FakeCache();val client=FakeClient();val repository=LinzDepthReferenceRepository(client,cache)
        repository.refresh(-36.8485,174.7633,nowWall=100_000L,nowElapsed=0L)
        assertEquals(LinzDepthStatus.AVAILABLE,repository.state.value.status)
        client.failure=IOException("offline")
        repository.refresh(-36.8485,174.7633,nowWall=100_000L+LinzDepthReferenceRepository.CACHE_TTL_MILLIS+1,nowElapsed=61_000L)
        assertEquals(LinzDepthStatus.OFFLINE,repository.state.value.status)
        assertTrue(repository.state.value.cached)
        assertEquals(6.4,repository.state.value.nearestSoundingDepthMeters?:Double.NaN,.001)

        val emptyRepository=LinzDepthReferenceRepository(client,FakeCache())
        emptyRepository.refresh(-36.8485,174.7633,nowWall=1_000L,nowElapsed=0L)
        assertEquals(LinzDepthStatus.OFFLINE,emptyRepository.state.value.status)
        assertTrue(!emptyRepository.state.value.cached)
    }

    private fun feature(id:String,type:String,coordinates:String,properties:String)=collection("""{"type":"Feature","id":"$id","properties":{$properties},"geometry":{"type":"$type","coordinates":$coordinates}}""")
    private fun collection(features:String)="""{"type":"FeatureCollection","features":[$features]}"""

    private class FakeClient:LinzWfsClient(){
        override val configured=true
        override val allLayerIds=listOf("50858","50671","50672")
        var failure:Throwable?=null
        var queryCount=0
        override suspend fun query(latitude:Double,longitude:Double):LinzWfsResult{
            queryCount++
            failure?.let{throw it}
            val sounding=HydroFeature("s1","50858",HydroFeatureKind.SOUNDING,HydroGeometry.Point(GeoPoint(longitude,latitude)),depth=6.4)
            return LinzWfsResult(listOf(sounding),emptyList(),emptyList(),3,200,emptyList())
        }
    }

    private class FakeCache:LinzDepthCacheDao{
        private val values=mutableMapOf<String,LinzDepthCacheEntity>()
        override suspend fun get(cellKey:String)=values[cellKey]
        override suspend fun upsert(value:LinzDepthCacheEntity){values[value.cellKey]=value}
        override suspend fun prune(oldestAllowed:Long){values.entries.removeAll{it.value.queriedAt<oldestAllowed}}
        override suspend fun clear(){values.clear()}
    }
}
