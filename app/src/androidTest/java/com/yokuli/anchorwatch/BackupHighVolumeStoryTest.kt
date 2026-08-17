package com.yokuli.anchorwatch

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.backup.YokuliBackupManager
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full production-code round trip at the release-gate volume. */
@RunWith(AndroidJUnit4::class)
class BackupHighVolumeStoryTest{
    @Test fun fiveHundredThousandSonarSamplesStreamThroughBackupAndRestore()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao())
        val file=File(context.cacheDir,"500k-${System.nanoTime()}.yokuli-backup")
        try{
            sonar.importSurveys(listOf(SonarSurveyEntity(id=700,name="500k streaming fixture",startedAt=1_700_000_000_000L,endedAt=1_700_500_000_000L,active=false,sampleCount=SAMPLE_COUNT)))
            var nextId=1L
            repeat(SAMPLE_COUNT/BATCH){batchIndex->
                val rows=List(BATCH){offset->
                    val id=nextId++
                    DepthSampleEntity(
                        id=id,surveyId=700,timestamp=1_700_000_000_000L+id,
                        latitude=-36.8485+(batchIndex%10)*.000001,
                        longitude=174.7633+(offset%10)*.000001,
                        baseGridX=0,baseGridY=0,sourceElapsedRealtime=id,
                        rawDepthMeters=8.0+(id%20)*.01,measuredDepthMeters=8.1+(id%20)*.01,
                        depthReference="BELOW_SURFACE",sentenceType="DPT",gpsSource="NMEA_SERVER",
                        positionProvider="NMEA",positionAgeMillis=0,disposition="REJECTED_TEST_FIXTURE",usable=false,
                    )
                }
                sonar.importSamples(rows)
            }
            assertEquals(SAMPLE_COUNT.toLong(),sonar.rawSampleCount())
            val exported=manager.export(Uri.fromFile(file));assertTrue(exported.exceptionOrNull()?.stackTraceToString(),exported.isSuccess)
            sonar.clearSamples();sonar.clearSurveys();assertEquals(0L,sonar.rawSampleCount())
            val restored=manager.restore(Uri.fromFile(file));assertTrue(restored.exceptionOrNull()?.stackTraceToString(),restored.isSuccess)
            assertEquals(SAMPLE_COUNT.toLong(),sonar.rawSampleCount())
            assertEquals(1L,sonar.allSamplesPage(0,1).single().id)
            assertEquals(SAMPLE_COUNT.toLong(),sonar.allSamplesPage(SAMPLE_COUNT-1L,1).single().id)
        }finally{preferences.save(original);database.close();file.delete()}
    }

    private companion object{const val SAMPLE_COUNT=500_000;const val BATCH=1_000}
}
