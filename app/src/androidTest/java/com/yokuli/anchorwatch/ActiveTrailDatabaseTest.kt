package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveTrailDatabaseTest{
    @Test fun activeMapQueryReturnsOnlyNewestBoundedPointsInChronologicalOrder()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            val dao=database.anchorDao()
            dao.importSessions(listOf(AnchorSessionEntity(id=7,startedAt=1,anchorLatitude=-36.84,anchorLongitude=174.76,rodeLengthMeters=0.0,waterDepthMeters=null,bowRollerHeightMeters=0.0,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=0.0,warningRadiusMeters=40.0,alarmRadiusMeters=50.0)))
            dao.importPoints((1L..5_001L).map{id->TrackPointEntity(id=id,sessionId=7,timestamp=id,latitude=-36.84,longitude=174.76,distanceFromAnchor=0.0,sog=null,cog=null,heading=null,hdop=null)})
            val recent=dao.recentPoints(7,4_800).first()
            assertEquals(4_800,recent.size)
            assertEquals(202L,recent.first().id)
            assertEquals(5_001L,recent.last().id)
        }finally{database.close()}
    }
}
