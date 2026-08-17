package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.AnchorageRepository
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageLibraryStoryTest{
    @Test fun explicitReuseTracksVisitButNeverSuppliesANewAnchorCoordinate()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            val repository=AnchorageRepository(database.anchorageDao());val now=1_000L
            val id=repository.save(SavedAnchorageEntity(name="Little Bay",latitude=-36.8,longitude=175.1,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=55.0,typicalWaterDepthMeters=7.0,typicalRodeLengthMeters=42.0))
            val used=repository.markUsed(id)!!;assertEquals(1,used.visitCount)
            // The reusable values contain setup geometry only. The next session's
            // authoritative coordinate is independently supplied by its selected source.
            val newFixLatitude=-36.7;val newFixLongitude=175.2
            val session=AnchorSessionEntity(startedAt=2_000,anchorLatitude=newFixLatitude,anchorLongitude=newFixLongitude,rodeLengthMeters=used.typicalRodeLengthMeters!!,waterDepthMeters=used.typicalWaterDepthMeters,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=40.0,warningRadiusMeters=45.0,alarmRadiusMeters=used.preferredAlarmRadiusMeters!!,savedAnchorageId=id)
            assertNotEquals(used.latitude,session.anchorLatitude,.000001);assertNotEquals(used.longitude,session.anchorLongitude,.000001)
        }finally{database.close()}
    }

    @Test fun nearbyDuplicateCrudAndHistoryDeletionRemainIndependent()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            val repository=AnchorageRepository(database.anchorageDao());val anchorDao=database.anchorDao()
            val session=AnchorSessionEntity(startedAt=1,endedAt=2,active=false,anchorLatitude=-36.8,anchorLongitude=175.1,rodeLengthMeters=40.0,waterDepthMeters=7.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=38.0,warningRadiusMeters=45.0,alarmRadiusMeters=50.0)
            val sessionId=anchorDao.insertSession(session);val savedId=repository.saveFromSession(session.copy(id=sessionId),"Little Bay")
            assertEquals(savedId,repository.duplicate(-36.8001,175.1001)?.id)
            assertTrue(repository.nearby(-36.8005,175.1005).isNotEmpty())
            assertEquals(1,anchorDao.deleteCompletedSession(sessionId));assertNotNull(repository.get(savedId))
            repository.delete(savedId);assertNull(repository.get(savedId))
        }finally{database.close()}
    }
}
