package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.AnchorageRepository
import com.yokuli.anchorwatch.data.anchorage.DuplicateAnchorageException
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageLibraryStoryTest{
    @Test fun duplicateAnchorageCannotBeCreatedButTheExistingDetailsRemainAvailable()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            val repository=AnchorageRepository(database.anchorageDao());val now=1_000L
            val id=repository.save(SavedAnchorageEntity(name="Little Bay",latitude=-36.8,longitude=175.1,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=55.0,typicalWaterDepthMeters=7.0,typicalRodeLengthMeters=42.0,notes="Sheltered in northerlies"))
            val failure=runCatching{repository.save(SavedAnchorageEntity(name="Duplicate",latitude=-36.8001,longitude=175.1001,createdAt=now+1,updatedAt=now+1))}.exceptionOrNull()
            assertTrue(failure is DuplicateAnchorageException)
            assertEquals(id,(failure as DuplicateAnchorageException).existing.id)
            assertEquals("Sheltered in northerlies",repository.get(id)?.notes)
            assertEquals(1,database.anchorageDao().allNow().size)

            // Editing the same record is allowed because it is not a duplicate of itself.
            repository.save(requireNotNull(repository.get(id)).copy(notes="Updated local note"))
            assertEquals("Updated local note",repository.get(id)?.notes)
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
            val recreatedId=repository.saveFromSession(session.copy(id=sessionId),"Little Bay recreated")
            assertEquals("Little Bay recreated",repository.get(recreatedId)?.name)
            assertEquals(1,database.anchorageDao().allNow().size)
        }finally{database.close()}
    }
}
