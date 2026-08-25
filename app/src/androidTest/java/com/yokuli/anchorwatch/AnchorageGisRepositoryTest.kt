package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.AnchorageDatabaseCallback
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCompassSector
import com.yokuli.anchorwatch.domain.anchorage.AnchorageProtectionMedium
import com.yokuli.anchorwatch.domain.anchorage.AnchorageProtectionRating
import com.yokuli.anchorwatch.domain.anchorage.AnchorageViewport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageGisRepositoryTest {
    private lateinit var database:AppDatabase
    private lateinit var spatial:AnchorageSpatialIndexRepository
    private lateinit var search:AnchorageSearchRepository
    private lateinit var places:AnchoragePlaceRepository
    private lateinit var spots:AnchorageSpotRepository
    private lateinit var visits:AnchorageVisitRepository

    @Before fun setUp(){
        database=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).addCallback(AnchorageDatabaseCallback).build()
        spatial=AnchorageSpatialIndexRepository(database,database.anchorageSpatialDao());search=AnchorageSearchRepository(database,database.anchorageSearchDao());places=AnchoragePlaceRepository(database,spatial,search);spots=AnchorageSpotRepository(database,spatial,search);visits=AnchorageVisitRepository(database)
    }
    @After fun tearDown(){database.close()}

    @Test fun spatialViewportNearbySearchAndRebuildUseThePersistedPlaceIdentity()=runBlocking{
        val placeId=places.save(place(name="Smokehouse Bay",lat=-36.188,lon=175.345,notes="quiet mud anchorage"))
        val spotId=spots.save(spot(placeId,-36.1881,175.3451,"Inner mud",55.0))
        assertEquals(placeId,spatial.viewport(AnchorageViewport(-36.3,175.2,-36.1,175.5)).single().id)
        assertEquals(spotId,spatial.nearbySpots(-36.188,175.345,1_000.0).single().first.id)
        assertEquals(placeId,search.search("Smokehouse").single().id)
        database.openHelper.writableDatabase.execSQL("DELETE FROM anchorage_spot_rtree")
        val health=spatial.verifyAndRepair();assertTrue(health.rebuilt);assertEquals(1L,health.indexedSpots)
    }

    @Test fun antiMeridianViewportQueriesBothWindowsWithoutDuplicatePlaces()=runBlocking{
        val east=places.save(place(name="East",lat=-36.0,lon=179.8))
        val west=places.save(place(name="West",lat=-36.0,lon=-179.8))
        val ids=spatial.viewport(AnchorageViewport(-37.0,179.0,-35.0,-179.0)).map{it.id}.toSet()
        assertEquals(setOf(east,west),ids)
    }

    @Test fun deletingSpotPreservesVisitSummaryWithNullSpotReference()=runBlocking{
        val placeId=places.save(place(name="Visit Bay",lat=-36.5,lon=175.0));val spotId=spots.save(spot(placeId,-36.5,175.0,"Main spot",40.0))
        val visitId=visits.save(AnchorageVisitEntity(placeId=placeId,spotId=spotId,anchorSessionId=null,visitKind="MANUAL",startedAt=1_000,endedAt=2_000,actualAnchorLatitude=-36.5,actualAnchorLongitude=175.0,coordinateSource="USER_ENTERED",coordinateUncertaintyMeters=5.0,waterDepthMeters=7.0,rodeLengthMeters=40.0,alarmRadiusMeters=50.0,maxExcursionMeters=25.0,alarmCount=0,minDepthMeters=6.8,maxDepthMeters=7.2,maxWindKnots=null,maxWindSource=null,typicalMotionScore=null,p95MotionScore=null,p95AbsoluteHeelDegrees=null,dominantRollPeriodSeconds=null,impactCount=null,summaryVersion="1",createdAt=2_000))
        val immutable=database.anchorageVisitDao().get(visitId)!!
        assertTrue(runCatching{visits.save(immutable.copy(userNotes="edited"))}.exceptionOrNull() is IllegalArgumentException)
        spots.save(requireNotNull(database.anchorageSpotDao().get(spotId)).copy(latitude=-36.51,longitude=175.01,updatedAt=3_000))
        assertEquals(-36.5,database.anchorageVisitDao().get(visitId)?.actualAnchorLatitude?:0.0,0.0);assertEquals(175.0,database.anchorageVisitDao().get(visitId)?.actualAnchorLongitude?:0.0,0.0)
        assertTrue(spots.delete(spotId));assertNull(database.anchorageVisitDao().get(visitId)?.spotId);assertEquals(7.0,database.anchorageVisitDao().get(visitId)?.waterDepthMeters?:-1.0,0.0)
    }

    @Test fun windAndSwellEightSectorProtectionRoundTripsAndVisitDoesNotOverwriteIt()=runBlocking{
        val placeId=places.save(place(name="Protected Bay",lat=-36.5,lon=175.0));val spotId=spots.save(spot(placeId,-36.5,175.0,"Main spot",40.0))
        val protection=AnchorageProtectionMedium.entries.flatMap{medium->AnchorageCompassSector.entries.mapIndexed{index,sector->AnchorageProtectionSectorEntity(placeId,medium.name,sector.name,if(index==0)AnchorageProtectionRating.UNKNOWN.name else AnchorageProtectionRating.entries[(index%3)+1].name,"USER",.8,updatedAt=1_000)}}
        database.anchorageMetadataDao().upsertProtection(protection)
        val before=database.anchorageMetadataDao().protection(placeId)
        assertEquals(16,before.size);assertEquals(8,before.count{it.medium=="WIND"});assertEquals(8,before.count{it.medium=="SWELL"});assertEquals(2,before.count{it.rating=="UNKNOWN"})
        visits.save(AnchorageVisitEntity(placeId=placeId,spotId=spotId,anchorSessionId=null,visitKind="MANUAL",startedAt=1_000,endedAt=2_000,actualAnchorLatitude=-36.5,actualAnchorLongitude=175.0,coordinateSource="USER_ENTERED",coordinateUncertaintyMeters=5.0,waterDepthMeters=7.0,rodeLengthMeters=40.0,alarmRadiusMeters=50.0,maxExcursionMeters=25.0,alarmCount=0,minDepthMeters=6.8,maxDepthMeters=7.2,maxWindKnots=18.0,maxWindSource="NMEA",typicalMotionScore=null,p95MotionScore=null,p95AbsoluteHeelDegrees=null,dominantRollPeriodSeconds=null,impactCount=null,summaryVersion="1",createdAt=2_000))
        assertEquals(before,database.anchorageMetadataDao().protection(placeId))
    }

    private fun place(name:String,lat:Double,lon:Double,notes:String="")=AnchoragePlaceEntity(displayName=name,placeType="BAY",geometryType="POINT",centerLatitude=lat,centerLongitude=lon,bboxMinLatitude=lat,bboxMaxLatitude=lat,bboxMinLongitude=lon,bboxMaxLongitude=lon,personalNotes=notes,verificationStatus="VISITED",createdAt=1,updatedAt=1)
    private fun spot(placeId:Long,lat:Double,lon:Double,name:String,radius:Double)=AnchorageSpotEntity(placeId=placeId,name=name,spotType="ANCHOR_SPOT",latitude=lat,longitude=lon,coordinateSource="CONFIRMED_ANCHOR",preferredAlarmRadiusMeters=radius,verificationStatus="VISITED",createdAt=1,updatedAt=1)
}
