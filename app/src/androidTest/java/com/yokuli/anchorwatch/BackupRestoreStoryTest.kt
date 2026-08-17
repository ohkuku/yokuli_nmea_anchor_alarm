package com.yokuli.anchorwatch

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.backup.YokuliBackupManager
import com.yokuli.anchorwatch.data.backup.YokuliBackupArchive
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.domain.model.AppLanguage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreStoryTest{
    @Test fun activeWritesDuringExportStillProduceARestoreValidSnapshot()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao())
        val file=File(context.cacheDir,"active-export-${System.nanoTime()}.yokuli-backup")
        try{
            anchor.importSessions(listOf(session(41,"CURRENT_POSITION")))
            anchor.importPoints((1L..5_000L).map{id->TrackPointEntity(id=id,sessionId=41,timestamp=id,latitude=-36.8485,longitude=174.7633,distanceFromAnchor=0.0,sog=null,cog=null,heading=null,hdop=null)})
            val exporting=async(Dispatchers.IO){manager.export(Uri.fromFile(file))}
            withTimeout(5_000){while(!manager.state.value.running&&!exporting.isCompleted)yield()}
            repeat(1_000){offset->anchor.insertPoint(TrackPointEntity(sessionId=41,timestamp=10_000L+offset,latitude=-36.8485,longitude=174.7633,distanceFromAnchor=0.0,sog=null,cog=null,heading=null,hdop=null))}
            val manifest=exporting.await().getOrThrow()
            anchor.clearEvents();anchor.clearPoints();anchor.clearSessions()
            assertTrue(manager.restore(Uri.fromFile(file)).isSuccess)
            assertEquals(manifest.recordCounts.getValue(YokuliBackupArchive.POINTS),anchor.pointCount())
        }finally{preferences.save(original);database.close();file.delete()}
    }

    @Test fun settingsAnchorTrackAlarmAndSonarRoundTripAsOneUserStory()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao())
        val file=File(context.cacheDir,"story-${System.nanoTime()}.yokuli-backup")
        try{
            preferences.save(original.copy(appLanguage=AppLanguage.SIMPLIFIED_CHINESE,boatLengthMeters=12.4,preferredAlarmRadiusMeters=66.0))
            anchor.importSessions(listOf(session(41,"ESTIMATED_USER_ACCEPTED")))
            anchor.importPoints(listOf(TrackPointEntity(id=51,sessionId=41,timestamp=2_000,latitude=-36.8484,longitude=174.7634,distanceFromAnchor=2.0,sog=.1,cog=12.0,heading=11.0,hdop=.8)))
            anchor.importEvents(listOf(AlarmEventEntity(id=61,sessionId=41,timestamp=2_500,type="ALARM_SNOOZED",detail="5m")))
            sonar.importSurveys(listOf(SonarSurveyEntity(id=71,name="港湾 α 测深",startedAt=3_000,endedAt=4_000,active=false,tideMode="AUTO_PREDICTED",tideStationId="auckland",tideStationName="Auckland",tideStationDistanceMeters=840.0)))
            sonar.importSamples(listOf(DepthSampleEntity(id=81,surveyId=71,timestamp=3_500,latitude=-36.8483,longitude=174.7635,baseGridX=1,baseGridY=2,sourceElapsedRealtime=300,rawDepthMeters=8.2,measuredDepthMeters=8.4,normalizedDepthMeters=6.9,depthReference="BELOW_SURFACE",sentenceType="DPT",gpsSource="NMEA_SERVER",positionProvider="NMEA",positionAgeMillis=50,tideHeightMetersApplied=1.5,tideCorrectionMode="AUTO_PREDICTED",tideStationId="auckland",tideStationName="Auckland",tideStationDistanceMeters=840.0,tidePredictionYear=2026,tideCorrectionMethod="LINZ_COSINE_BETWEEN_EXTREMES",tideSource="LINZ_DAILY_PREDICTION",tideSourceUpdatedAt=3_400,tideCorrectionStatus="AVAILABLE")))
            assertTrue(manager.export(Uri.fromFile(file)).isSuccess)
            sonar.clearGridCells();sonar.clearSamples();sonar.clearSurveys();anchor.clearEvents();anchor.clearPoints();anchor.clearSessions()
            preferences.save(original)
            assertTrue(manager.restore(Uri.fromFile(file)).isSuccess)
            assertEquals(41,anchor.allSessionsNow().single().id);assertEquals("ESTIMATED_USER_ACCEPTED",anchor.allSessionsNow().single().centerSource)
            assertEquals(51,anchor.allPointsPage(0,10).single().id);assertEquals("ALARM_SNOOZED",anchor.allEventsPage(0,10).single().type)
            assertEquals("港湾 α 测深",sonar.allSurveysNow().single().name);assertEquals("NMEA_SERVER",sonar.allSamplesPage(0,10).single().gpsSource);assertEquals(1.5,sonar.allSamplesPage(0,10).single().tideHeightMetersApplied!!,.0001);assertEquals("LINZ_DAILY_PREDICTION",sonar.allSamplesPage(0,10).single().tideSource)
            val restored=preferences.settings.first();assertEquals(AppLanguage.SIMPLIFIED_CHINESE,restored.appLanguage);assertEquals(12.4,restored.boatLengthMeters,.001)
            // Explicit imported primary keys must advance SQLite's generated ids;
            // otherwise the first post-restore watch or sounding could collide.
            assertTrue(anchor.insertSession(session(0,"CURRENT_POSITION").copy(startedAt=9_000))>41)
            val nextSurvey=sonar.insertSurvey(SonarSurveyEntity(name="post-restore",startedAt=9_000,active=false))
            assertTrue(nextSurvey>71)
            val nextSample=sonar.insertSample(DepthSampleEntity(surveyId=nextSurvey,timestamp=9_100,latitude=-36.84,longitude=174.76,baseGridX=3,baseGridY=4,sourceElapsedRealtime=9_100,rawDepthMeters=5.0,measuredDepthMeters=5.0,depthReference="BELOW_SURFACE",sentenceType="DPT",gpsSource="NMEA_SERVER",positionProvider="NMEA",positionAgeMillis=0))
            assertTrue(nextSample>81)
        }finally{preferences.save(original);database.close();file.delete()}
    }

    @Test fun corruptArchiveIsRejectedBeforeExistingLocalHistoryChanges()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao())
        val corrupt=File(context.cacheDir,"corrupt-${System.nanoTime()}.yokuli-backup").apply{writeBytes(byteArrayOf(1,2,3,4,5))}
        try{anchor.importSessions(listOf(session(99,"CURRENT_POSITION")));assertTrue(manager.restore(Uri.fromFile(corrupt)).isFailure);assertEquals(99,anchor.allSessionsNow().single().id)}finally{preferences.save(original);database.close();corrupt.delete()}
    }

    @Test fun emptyAppRoundTripAndSecondExportHaveTheSameCanonicalPayload()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao())
        val first=File(context.cacheDir,"empty-a-${System.nanoTime()}.yokuli-backup");val second=File(context.cacheDir,"empty-b-${System.nanoTime()}.yokuli-backup")
        try{
            anchor.clearEvents();anchor.clearPoints();anchor.clearSessions();sonar.clearGridCells();sonar.clearSamples();sonar.clearSurveys()
            preferences.save(original.copy(boatLengthMeters=14.25))
            assertTrue(manager.export(Uri.fromFile(first)).isSuccess)
            preferences.save(original)
            assertTrue(manager.restore(Uri.fromFile(first)).isSuccess)
            assertTrue(manager.export(Uri.fromFile(second)).isSuccess)
            val comparable=listOf(YokuliBackupArchive.SETTINGS)+YokuliBackupArchive.dataFiles
            val a=readZip(first);val b=readZip(second)
            comparable.forEach{name->assertEquals("Canonical payload differs for $name",a.getValue(name).decodeToString(),b.getValue(name).decodeToString())}
            assertEquals(0L,anchor.sessionCount());assertEquals(0L,sonar.rawSampleCount());assertEquals(14.25,preferences.settings.first().boatLengthMeters,.001)
        }finally{preferences.save(original);database.close();first.delete();second.delete()}
    }

    @Test fun checksumMissingEntryUnsupportedVersionInvalidFkAndInvalidCoordinateAreRejectedBeforeReplace()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao())
        val base=File(context.cacheDir,"fault-base-${System.nanoTime()}.yokuli-backup");val variants=mutableListOf<File>()
        try{
            anchor.importSessions(listOf(session(41,"CURRENT_POSITION")))
            anchor.importPoints(listOf(TrackPointEntity(id=51,sessionId=41,timestamp=2_000,latitude=-36.8484,longitude=174.7634,distanceFromAnchor=2.0,sog=null,cog=null,heading=null,hdop=.8)))
            assertTrue(manager.export(Uri.fromFile(base)).isSuccess)
            suspend fun rejected(name:String,mutate:(MutableMap<String,ByteArray>)->Unit){
                val target=File(context.cacheDir,"$name-${System.nanoTime()}.yokuli-backup");variants+=target
                val entries=readZip(base);mutate(entries);writeZip(target,entries)
                assertTrue("$name should fail validation",manager.restore(Uri.fromFile(target)).isFailure)
                assertEquals("$name changed existing data",41,anchor.allSessionsNow().single().id)
            }
            rejected("wrong-checksum"){entries->entries[YokuliBackupArchive.POINTS]=entries.getValue(YokuliBackupArchive.POINTS)+" ".encodeToByteArray()}
            rejected("missing-entry"){entries->entries.remove(YokuliBackupArchive.SAMPLES)}
            rejected("unsupported-version"){entries->entries[YokuliBackupArchive.MANIFEST]=entries.getValue(YokuliBackupArchive.MANIFEST).decodeToString().replace("\"formatVersion\":1","\"formatVersion\":999").encodeToByteArray()}
            rejected("invalid-fk"){entries->entries[YokuliBackupArchive.POINTS]=entries.getValue(YokuliBackupArchive.POINTS).decodeToString().replace("\"sessionId\":41","\"sessionId\":999").encodeToByteArray();refreshChecksum(entries,YokuliBackupArchive.POINTS)}
            rejected("invalid-coordinate"){entries->entries[YokuliBackupArchive.ANCHORS]=entries.getValue(YokuliBackupArchive.ANCHORS).decodeToString().replace("\"anchorLatitude\":-36.8485","\"anchorLatitude\":999.0").encodeToByteArray();refreshChecksum(entries,YokuliBackupArchive.ANCHORS)}
        }finally{preferences.save(original);database.close();base.delete();variants.forEach(File::delete)}
    }

    private fun session(id:Long,centerSource:String)=AnchorSessionEntity(id=id,startedAt=1_000,endedAt=5_000,anchorLatitude=-36.8485,anchorLongitude=174.7633,rodeLengthMeters=40.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=35.0,warningRadiusMeters=45.0,alarmRadiusMeters=55.0,active=false,centerSource=centerSource)

    private fun readZip(file:File):MutableMap<String,ByteArray>{
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(FileInputStream(file)).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes();zip.closeEntry()}}
        return entries
    }
    private fun writeZip(file:File,entries:Map<String,ByteArray>){ZipOutputStream(FileOutputStream(file)).use{zip->entries.forEach{(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}}
    private fun refreshChecksum(entries:MutableMap<String,ByteArray>,name:String){
        val type=object:TypeToken<MutableMap<String,String>>(){}.type
        val checksums:MutableMap<String,String> = Gson().fromJson(entries.getValue(YokuliBackupArchive.CHECKSUMS).decodeToString(),type)
        checksums[name]=MessageDigest.getInstance("SHA-256").digest(entries.getValue(name)).joinToString(""){"%02x".format(it)}
        entries[YokuliBackupArchive.CHECKSUMS]=Gson().toJson(checksums).encodeToByteArray()
    }
}
