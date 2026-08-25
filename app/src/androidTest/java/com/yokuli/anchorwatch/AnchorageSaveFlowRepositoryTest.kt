package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AnchorageDatabaseCallback
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.domain.anchorage.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageSaveFlowRepositoryTest{
    private lateinit var db:AppDatabase;private lateinit var save:AnchorageSaveRepository
    @Before fun setup(){
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).addCallback(AnchorageDatabaseCallback).build()
        val spatial=AnchorageSpatialIndexRepository(db,db.anchorageSpatialDao());val search=AnchorageSearchRepository(db,db.anchorageSearchDao());save=AnchorageSaveRepository(db,AnchoragePlaceRepository(db,spatial,search),AnchorageSpotRepository(db,spatial,search),AnchorageVisitRepository(db),spatial,search)
    }
    @After fun close(){db.close()}

    @Test fun immutableDraftDoesNotWriteUntilExplicitSaveAndMapPointStaysPlanned()=runBlocking{
        val draft=AnchorageSaveDraftFactory.fromMap(-36.8,175.1)
        assertEquals(0L,db.anchoragePlaceDao().count())
        val result=save.save(AnchorageSaveRequest(draft,AnchorageSavePlaceInput(displayName="Planned Bay",planningStatus=AnchoragePlanningStatus.WANT_TO_VISIT),AnchorageSaveSpotInput(name="Chart reference")))
        assertNull(result.visitId);assertEquals("PLANNED",db.anchoragePlaceDao().get(result.placeId)?.verificationStatus);assertEquals("PLANNED_REFERENCE",db.anchorageSpotDao().get(result.spotId)?.spotType)
    }

    @Test fun sessionSaveAtomicallyCreatesPlaceSpotVisitAndSessionLinks()=runBlocking{
        val sessionId=db.anchorDao().insertSession(session())
        val draft=AnchorageSaveDraftFactory.fromSession(db.anchorDao().session(sessionId)!!)
        val result=save.save(AnchorageSaveRequest(draft,AnchorageSavePlaceInput(displayName="Visited Bay",placeType=AnchoragePlaceType.BAY),AnchorageSaveSpotInput(name="Inner mud"),"Calm night",AnchoragePersonalAssessmentInput(AnchorageWouldReturn.YES,AnchorageAssessmentRating.GOOD,AnchorageAssessmentRating.AVERAGE,AnchorageAssessmentRating.GOOD,notes="Would return")))
        assertNotNull(result.visitId);val linked=db.anchorDao().session(sessionId)!!;assertEquals(result.placeId,linked.anchoragePlaceId);assertEquals(result.spotId,linked.anchorageSpotId);assertEquals(result.visitId,linked.anchorageVisitId)
        assertEquals("Calm night",db.anchorageVisitDao().get(result.visitId!!)?.userNotes)
        val assessment=db.anchorageMetadataDao().assessment(result.placeId)!!;assertEquals("YES",assessment.wouldReturn);assertEquals("GOOD",assessment.holding);assertEquals("AVERAGE",assessment.comfort);assertEquals("Would return",assessment.notes)
    }

    @Test fun closeCoordinatesCanRemainDifferentPlacesAndExistingPlaceCanGainAnotherSpot()=runBlocking{
        val first=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromMap(-36.8,175.1),AnchorageSavePlaceInput(displayName="North Bay"),AnchorageSaveSpotInput()))
        val second=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromMap(-36.8002,175.1),AnchorageSavePlaceInput(displayName="South Cove"),AnchorageSaveSpotInput()))
        assertNotEquals(first.placeId,second.placeId)
        val another=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromMap(-36.801,175.1),AnchorageSavePlaceInput(existingPlaceId=first.placeId,displayName="North Bay"),AnchorageSaveSpotInput(name="Outer sand")))
        assertEquals(first.placeId,another.placeId);assertEquals(2,db.anchorageSpotDao().forPlaceNow(first.placeId).size)
    }

    @Test fun existingPlaceAndSpotOnlyGainAnImmutableVisit()=runBlocking{
        val firstSessionId=db.anchorDao().insertSession(session())
        val first=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromSession(db.anchorDao().session(firstSessionId)!!),AnchorageSavePlaceInput(displayName="Known Bay"),AnchorageSaveSpotInput(name="Inner mud")))
        val secondSessionId=db.anchorDao().insertSession(session().copy(startedAt=10_000,endedAt=15_000))
        val second=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromSession(db.anchorDao().session(secondSessionId)!!),AnchorageSavePlaceInput(existingPlaceId=first.placeId,displayName="Known Bay"),AnchorageSaveSpotInput(existingSpotId=first.spotId,name="Inner mud")))
        assertEquals(first.placeId,second.placeId);assertEquals(first.spotId,second.spotId)
        assertFalse(second.placeCreated);assertFalse(second.spotCreated);assertTrue(second.visitCreated)
        assertEquals(1L,db.anchoragePlaceDao().count());assertEquals(1,db.anchorageSpotDao().forPlaceNow(first.placeId).size);assertEquals(2,db.anchorageVisitDao().forPlaceNow(first.placeId).size)
    }

    @Test fun completedSaveCanUndoOnlyItsNewPlaceSpotAndVisit()=runBlocking{
        val sessionId=db.anchorDao().insertSession(session())
        val result=save.save(AnchorageSaveRequest(AnchorageSaveDraftFactory.fromSession(db.anchorDao().session(sessionId)!!),AnchorageSavePlaceInput(displayName="Undo Bay"),AnchorageSaveSpotInput()))
        assertTrue(result.placeCreated&&result.spotCreated&&result.visitCreated)
        assertTrue(save.undo(result,sessionId))
        assertNull(db.anchoragePlaceDao().get(result.placeId));assertNull(db.anchorageSpotDao().get(result.spotId));assertNull(result.visitId?.let{db.anchorageVisitDao().get(it)})
        assertNull(db.anchorDao().session(sessionId)?.anchorageVisitId)
    }

    private fun session()=AnchorSessionEntity(startedAt=1_000,endedAt=5_000,anchorLatitude=-36.8,anchorLongitude=175.1,rodeLengthMeters=40.0,waterDepthMeters=7.0,bowRollerHeightMeters=1.2,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=40.0,warningRadiusMeters=45.0,alarmRadiusMeters=50.0,active=false,maxDistanceMeters=28.0,alarmCount=1,minObservedDepthMeters=6.8,maxObservedDepthMeters=7.3)
}
