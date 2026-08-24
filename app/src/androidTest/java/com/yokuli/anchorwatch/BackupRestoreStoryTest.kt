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
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.database.TrackPointEntity
import com.yokuli.anchorwatch.data.database.TripCustomMetricSampleEntity
import com.yokuli.anchorwatch.data.database.TripDashboardEntity
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.database.TripWaypointEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.vessel.OutputSettingsRepository
import com.yokuli.anchorwatch.data.vessel.VesselSettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibrationRepository
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
    @Test fun normalizedAnchorageGisAndPrivateMediaRoundTripInV5()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,database.anchorDao(),database.sonarDao(),preferences,SonarIncrementalGridUpdater(database.sonarDao()),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val archive=File(context.cacheDir,"gis-v5-${System.nanoTime()}.yokuli-backup");val mediaDir=File(context.filesDir,"anchorage_media").apply{mkdirs()};val originalPhoto=File(mediaDir,"backup-photo.jpg").apply{writeBytes("private-photo".encodeToByteArray())}
        try{
            val now=1_000L
            database.anchoragePlaceDao().importAll(listOf(com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity(id=201,displayName="Smokehouse Bay",placeType="BAY",geometryType="POINT",centerLatitude=-36.18,centerLongitude=175.34,bboxMinLatitude=-36.18,bboxMaxLatitude=-36.18,bboxMinLongitude=175.34,bboxMaxLongitude=175.34,verificationStatus="VISITED",favorite=true,createdAt=now,updatedAt=now)))
            database.anchorageSpotDao().importAll(listOf(com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity(id=202,placeId=201,name="Inner mud",spotType="ANCHOR_SPOT",latitude=-36.181,longitude=175.341,coordinateSource="CONFIRMED_ANCHOR",preferredAlarmRadiusMeters=55.0,verificationStatus="VISITED",createdAt=now,updatedAt=now)))
            database.anchorageVisitDao().importAll(listOf(com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity(id=203,placeId=201,spotId=202,anchorSessionId=null,visitKind="MANUAL",startedAt=now,endedAt=2_000,actualAnchorLatitude=-36.181,actualAnchorLongitude=175.341,coordinateSource="CONFIRMED_ANCHOR",coordinateUncertaintyMeters=3.0,waterDepthMeters=7.0,rodeLengthMeters=45.0,alarmRadiusMeters=55.0,maxExcursionMeters=30.0,alarmCount=0,minDepthMeters=6.8,maxDepthMeters=7.2,maxWindKnots=null,maxWindSource=null,typicalMotionScore=null,p95MotionScore=null,p95AbsoluteHeelDegrees=null,dominantRollPeriodSeconds=null,impactCount=null,summaryVersion="1",createdAt=now)))
            database.anchoragePhotoDao().importAll(listOf(com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity(id=204,placeId=201,relativeFileName=originalPhoto.name,thumbnailRelativeFileName=null,mimeType="image/jpeg",sha256="metadata-sha",width=10,height=10,createdAt=now)))
            val manifest=manager.export(Uri.fromFile(archive)).getOrThrow();assertEquals(5,manifest.formatVersion);assertEquals(1L,manifest.recordCounts.getValue(YokuliBackupArchive.GIS_PLACES));assertTrue(readZip(archive).containsKey(YokuliBackupArchive.GIS_MEDIA_PREFIX+originalPhoto.name))
            originalPhoto.delete();manager.restore(Uri.fromFile(archive)).getOrThrow()
            assertEquals("Smokehouse Bay",database.anchoragePlaceDao().get(201)?.displayName);assertEquals("Inner mud",database.anchorageSpotDao().get(202)?.name);assertEquals(7.0,database.anchorageVisitDao().get(203)?.waterDepthMeters?:0.0,.001);assertEquals("private-photo",originalPhoto.readText())
        }finally{preferences.save(original);database.close();archive.delete();originalPhoto.delete()}
    }

    @Test fun tripWaypointsCustomNmeaAndDashboardsRoundTripInV4()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val preferences=SettingsRepository(context);val original=preferences.settings.first();val tripDao=database.tripDao()
        val manager=YokuliBackupManager(context,database,database.anchorDao(),database.sonarDao(),preferences,SonarIncrementalGridUpdater(database.sonarDao()),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),tripDao,VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val file=File(context.cacheDir,"trip-v4-${System.nanoTime()}.yokuli-backup")
        try{
            tripDao.insertSession(TripSessionEntity(id=101,name="Windward passage",startedAt=1_000,endedAt=5_000,active=false,boatLengthMeters=10.6,draftMeters=1.8,positionPreference="AUTO",headingPreference="AUTO",phoneMotionEnabled=true,mountCalibrationVersion=3))
            tripDao.insertWaypoint(TripWaypointEntity(id=102,tripId=101,timestamp=2_000,latitude=-36.81,longitude=175.11,name="Reefing",note="二号帆",type="SAIL_CHANGE",positionSource="BOAT_NMEA",sogKnots=6.2,headingTrueDegrees=82.0,trueWindSpeedKnots=18.0,heelDegrees=-14.0,depthMeters=27.0))
            tripDao.insertCustomMetrics(listOf(TripCustomMetricSampleEntity(id=103,tripId=101,timestamp=2_100,fieldId="II:XYZ:3:RAW:",displayName="Foil angle",numericValue=4.2,unit="deg",sentenceType="XYZ",fieldAgeMillis=20)))
            tripDao.upsertDashboard(TripDashboardEntity("custom-race","CUSTOM","Race page","[{\"nmeaFieldId\":\"II:XYZ:3:RAW:\",\"size\":\"LARGE\"}]",2_200))
            val manifest=manager.export(Uri.fromFile(file)).getOrThrow()
            assertEquals(1L,manifest.recordCounts.getValue(YokuliBackupArchive.TRIP_CUSTOM_METRICS));assertEquals(1L,manifest.recordCounts.getValue(YokuliBackupArchive.TRIP_DASHBOARDS))
            tripDao.clearCustomMetrics();tripDao.clearDashboards();tripDao.clearWaypoints();tripDao.clearSessions()
            manager.restore(Uri.fromFile(file)).getOrThrow()
            assertEquals("二号帆",tripDao.waypoints(101).single().note);assertEquals(18.0,tripDao.waypoints(101).single().trueWindSpeedKnots!!,.001)
            assertEquals(4.2,tripDao.customMetrics(101).single().numericValue!!,.001)
            assertEquals("Race page",tripDao.allDashboardsNow().single().title)
        }finally{preferences.save(original);database.close();file.delete()}
    }

    @Test fun activeWritesDuringExportStillProduceARestoreValidSnapshot()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
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
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val file=File(context.cacheDir,"story-${System.nanoTime()}.yokuli-backup")
        try{
            preferences.save(original.copy(appLanguage=AppLanguage.SIMPLIFIED_CHINESE,boatLengthMeters=12.4,preferredAlarmRadiusMeters=66.0))
            anchor.importSessions(listOf(session(41,"ESTIMATED_USER_ACCEPTED")))
            anchor.importPoints(listOf(TrackPointEntity(id=51,sessionId=41,timestamp=2_000,latitude=-36.8484,longitude=174.7634,distanceFromAnchor=2.0,sog=.1,cog=12.0,heading=11.0,hdop=.8)))
            anchor.importEvents(listOf(AlarmEventEntity(id=61,sessionId=41,timestamp=2_500,type="ALARM_SNOOZED",detail="5m")))
            database.anchorageDao().insert(SavedAnchorageEntity(id=62,name="Quiet Bay",latitude=-36.8485,longitude=174.7633,createdAt=2_600,updatedAt=2_600,preferredAlarmRadiusMeters=55.0,rating=5,notes="Local-only note",sourceSessionId=41))
            sonar.importSurveys(listOf(SonarSurveyEntity(id=71,name="港湾 α 测深",startedAt=3_000,endedAt=4_000,active=false,tideMode="AUTO_PREDICTED",tideStationId="auckland",tideStationName="Auckland",tideStationDistanceMeters=840.0)))
            sonar.importSamples(listOf(DepthSampleEntity(id=81,surveyId=71,timestamp=3_500,latitude=-36.8483,longitude=174.7635,baseGridX=1,baseGridY=2,sourceElapsedRealtime=300,rawDepthMeters=8.2,measuredDepthMeters=8.4,normalizedDepthMeters=6.9,depthReference="BELOW_SURFACE",sentenceType="DPT",gpsSource="NMEA_SERVER",positionProvider="NMEA",positionAgeMillis=50,tideHeightMetersApplied=1.5,tideCorrectionMode="AUTO_PREDICTED",tideStationId="auckland",tideStationName="Auckland",tideStationDistanceMeters=840.0,tidePredictionYear=2026,tideCorrectionMethod="LINZ_COSINE_BETWEEN_EXTREMES",tideSource="LINZ_DAILY_PREDICTION",tideSourceUpdatedAt=3_400,tideCorrectionStatus="AVAILABLE")))
            assertTrue(manager.export(Uri.fromFile(file)).isSuccess)
            sonar.clearGridCells();sonar.clearSamples();sonar.clearSurveys();database.anchorageDao().clear();anchor.clearEvents();anchor.clearPoints();anchor.clearSessions()
            preferences.save(original)
            assertTrue(manager.restore(Uri.fromFile(file)).isSuccess)
            assertEquals(41,anchor.allSessionsNow().single().id);assertEquals("ESTIMATED_USER_ACCEPTED",anchor.allSessionsNow().single().centerSource)
            assertEquals(51,anchor.allPointsPage(0,10).single().id);assertEquals("ALARM_SNOOZED",anchor.allEventsPage(0,10).single().type)
            assertEquals("港湾 α 测深",sonar.allSurveysNow().single().name);assertEquals("NMEA_SERVER",sonar.allSamplesPage(0,10).single().gpsSource);assertEquals(1.5,sonar.allSamplesPage(0,10).single().tideHeightMetersApplied!!,.0001);assertEquals("LINZ_DAILY_PREDICTION",sonar.allSamplesPage(0,10).single().tideSource)
            assertEquals("Quiet Bay",database.anchorageDao().allNow().single().name);assertEquals(5,database.anchorageDao().allNow().single().rating)
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
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val corrupt=File(context.cacheDir,"corrupt-${System.nanoTime()}.yokuli-backup").apply{writeBytes(byteArrayOf(1,2,3,4,5))}
        try{anchor.importSessions(listOf(session(99,"CURRENT_POSITION")));assertTrue(manager.restore(Uri.fromFile(corrupt)).isFailure);assertEquals(99,anchor.allSessionsNow().single().id)}finally{preferences.save(original);database.close();corrupt.delete()}
    }

    @Test fun emptyAppRoundTripAndSecondExportHaveTheSameCanonicalPayload()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val first=File(context.cacheDir,"empty-a-${System.nanoTime()}.yokuli-backup");val second=File(context.cacheDir,"empty-b-${System.nanoTime()}.yokuli-backup")
        try{
            anchor.clearEvents();anchor.clearPoints();anchor.clearSessions();sonar.clearGridCells();sonar.clearSamples();sonar.clearSurveys()
            preferences.save(original.copy(boatLengthMeters=14.25))
            assertTrue(manager.export(Uri.fromFile(first)).isSuccess)
            preferences.save(original)
            assertTrue(manager.restore(Uri.fromFile(first)).isSuccess)
            assertTrue(manager.export(Uri.fromFile(second)).isSuccess)
            val comparable=listOf(YokuliBackupArchive.SETTINGS,YokuliBackupArchive.VESSEL_SETTINGS)+YokuliBackupArchive.dataFiles
            val a=readZip(first);val b=readZip(second)
            comparable.forEach{name->assertEquals("Canonical payload differs for $name",a.getValue(name).decodeToString(),b.getValue(name).decodeToString())}
            assertEquals(0L,anchor.sessionCount());assertEquals(0L,sonar.rawSampleCount());assertEquals(14.25,preferences.settings.first().boatLengthMeters,.001)
        }finally{preferences.save(original);database.close();first.delete();second.delete()}
    }

    @Test fun checksumMissingEntryUnsupportedVersionInvalidFkAndInvalidCoordinateAreRejectedBeforeReplace()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();val anchor=database.anchorDao();val sonar=database.sonarDao();val preferences=SettingsRepository(context);val original=preferences.settings.first()
        val manager=YokuliBackupManager(context,database,anchor,sonar,preferences,SonarIncrementalGridUpdater(sonar),database.linzDepthCacheDao(),database.tidePredictionCacheDao(),database.anchorageDao(),database.tripDao(),VesselSettingsRepository(context),OutputSettingsRepository(context),VesselMountCalibrationRepository(context))
        val base=File(context.cacheDir,"fault-base-${System.nanoTime()}.yokuli-backup");val variants=mutableListOf<File>()
        try{
            anchor.importSessions(listOf(session(41,"CURRENT_POSITION")))
            anchor.importPoints(listOf(TrackPointEntity(id=51,sessionId=41,timestamp=2_000,latitude=-36.8484,longitude=174.7634,distanceFromAnchor=2.0,sog=null,cog=null,heading=null,hdop=.8)))
            database.anchorageDao().insert(SavedAnchorageEntity(id=71,name="Validation Bay",latitude=-36.84,longitude=174.76,createdAt=1,updatedAt=1,rating=4))
            assertTrue(manager.export(Uri.fromFile(base)).isSuccess)
            suspend fun rejected(name:String,mutate:(MutableMap<String,ByteArray>)->Unit){
                val target=File(context.cacheDir,"$name-${System.nanoTime()}.yokuli-backup");variants+=target
                val entries=readZip(base);mutate(entries);writeZip(target,entries)
                assertTrue("$name should fail validation",manager.restore(Uri.fromFile(target)).isFailure)
                assertEquals("$name changed existing data",41,anchor.allSessionsNow().single().id)
            }
            rejected("wrong-checksum"){entries->entries[YokuliBackupArchive.POINTS]=entries.getValue(YokuliBackupArchive.POINTS)+" ".encodeToByteArray()}
            rejected("missing-entry"){entries->entries.remove(YokuliBackupArchive.SAMPLES)}
            rejected("unsupported-version"){entries->entries[YokuliBackupArchive.MANIFEST]=entries.getValue(YokuliBackupArchive.MANIFEST).decodeToString().replace("\"formatVersion\":${YokuliBackupArchive.VERSION}","\"formatVersion\":999").encodeToByteArray()}
            rejected("invalid-fk"){entries->entries[YokuliBackupArchive.POINTS]=entries.getValue(YokuliBackupArchive.POINTS).decodeToString().replace("\"sessionId\":41","\"sessionId\":999").encodeToByteArray();refreshChecksum(entries,YokuliBackupArchive.POINTS)}
            rejected("invalid-coordinate"){entries->entries[YokuliBackupArchive.ANCHORS]=entries.getValue(YokuliBackupArchive.ANCHORS).decodeToString().replace("\"anchorLatitude\":-36.8485","\"anchorLatitude\":999.0").encodeToByteArray();refreshChecksum(entries,YokuliBackupArchive.ANCHORS)}
            rejected("invalid-rating"){entries->entries[YokuliBackupArchive.ANCHORAGES]=entries.getValue(YokuliBackupArchive.ANCHORAGES).decodeToString().replace("\"rating\":4","\"rating\":99").encodeToByteArray();refreshChecksum(entries,YokuliBackupArchive.ANCHORAGES)}
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
