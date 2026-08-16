package com.yokuli.anchorwatch

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.GridCoordinate
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.sonar.SonarGridScope
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SonarIncrementalGridTest {
    @Test fun newSoundingOnlyRecomputesItsCellAndDeleteCascadesRawData()=runBlocking {
        val context:Context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val dao=database.sonarDao();val updater=SonarIncrementalGridUpdater(dao)
            val queryPlan=database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM depth_samples WHERE surveyId=7 AND baseGridX=100 AND baseGridY=200").use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))}}.joinToString()
            assertTrue(queryPlan.contains("index_depth_samples_surveyId_baseGridX_baseGridY"))
            val surveyId=dao.insertSurvey(SonarSurveyEntity(name="Incremental",startedAt=1L))
            dao.insertSample(sample(surveyId,1L,100L,200L,8.0,normalized=7.5))
            val first=updater.updateCells(surveyId,setOf(GridCoordinate(100L,200L)))
            assertEquals(1L,first.rawSamples);assertEquals(2L,first.gridCells)
            assertEquals(8.0,dao.gridCellsNow(SonarGridScope.SURVEY,surveyId).single().depthMeters,.001)
            assertEquals(7.5,dao.gridCellsNow(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID).single().depthMeters,.001)

            dao.insertSample(sample(surveyId,2L,101L,200L,9.0,normalized=null))
            val second=updater.updateCells(surveyId,setOf(GridCoordinate(101L,200L)))
            assertEquals("101,200",second.lastUpdatedCell)
            assertEquals(2,dao.gridCellsNow(SonarGridScope.SURVEY,surveyId).size)
            assertEquals(1,dao.gridCellsNow(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID).size)

            assertTrue(!updater.deleteSurvey(surveyId))
            assertEquals(2,dao.gridCellsNow(SonarGridScope.SURVEY,surveyId).size)
            dao.finish(surveyId,3L)
            assertTrue(updater.deleteSurvey(surveyId))
            assertEquals(0L,dao.rawSampleCount())
            assertTrue(dao.gridCellsNow(SonarGridScope.SURVEY,surveyId).isEmpty())
            assertTrue(dao.gridCellsNow(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID).isEmpty())
        } finally { database.close() }
    }

    private fun sample(surveyId:Long,id:Long,x:Long,y:Long,depth:Double,normalized:Double?)=DepthSampleEntity(
        id=id,
        surveyId=surveyId,
        timestamp=id,
        latitude=-36.8485,
        longitude=174.7633,
        baseGridX=x,
        baseGridY=y,
        sourceElapsedRealtime=id*1_000,
        rawDepthMeters=depth,
        measuredDepthMeters=depth,
        normalizedDepthMeters=normalized,
        depthReference=DepthReference.BELOW_SURFACE.name,
        sentenceType=DepthSentenceType.DPT.name,
        gpsSource="NMEA",
        positionProvider=PositionProvider.NMEA.name,
        fixTrust=FixTrust.TRUSTED.name,
        positionAgeMillis=0L,
    )
}
