package com.yokuli.anchorwatch

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.Migration5To6
import com.yokuli.anchorwatch.data.database.Migration6To7
import com.yokuli.anchorwatch.data.database.Migration7To8
import com.yokuli.anchorwatch.data.database.Migration8To9
import com.yokuli.anchorwatch.data.database.Migration9To10
import com.yokuli.anchorwatch.data.database.Migration10To11
import com.yokuli.anchorwatch.data.database.Migration11To12
import com.yokuli.anchorwatch.data.database.Migration12To13
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @Test fun migration8And9FixturesBothReachCurrentSchemaWithoutLosingSurvey()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(startVersion in listOf(8,9)){
            val name="migration-v$startVersion-v11-${System.nanoTime()}.db";context.deleteDatabase(name);createV5(context,name);upgradeFromV5(context,name,startVersion)
            val remaining=when(startVersion){8->arrayOf(Migration8To9,Migration9To10,Migration10To11,Migration11To12,Migration12To13);else->arrayOf(Migration9To10,Migration10To11,Migration11To12,Migration12To13)}
            val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(*remaining).build()
            try{
                database.openHelper.writableDatabase
                assertEquals("V$startVersion survey",database.sonarDao().survey(91L)?.name)
                assertTrue(database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='incident_log'").use{it.moveToFirst()})
                assertTrue(database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='saved_anchorages'").use{it.moveToFirst()})
                val conditionColumns=database.openHelper.writableDatabase.query("PRAGMA table_info(anchor_sessions)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
                assertTrue(conditionColumns.containsAll(setOf("depthGuardEnabled","windGuardEnabled","windShiftEnabled","windBaselineDirectionDegrees","minObservedDepthMeters","savedAnchorageId")))
                val anchorageColumns=database.openHelper.writableDatabase.query("PRAGMA table_info(saved_anchorages)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
                assertTrue(anchorageColumns.containsAll(setOf("coordinateSource","coordinateUncertaintyMeters")))
            }finally{database.close();context.deleteDatabase(name)}
        }
    }
    @Test fun migration10To11PreservesOperationalDataAndCreatesBoundedIncidentTable()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="migration-v10-v11-${System.nanoTime()}.db";context.deleteDatabase(name);createV5(context,name);upgradeToV10(context,name)
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(Migration10To11,Migration11To12,Migration12To13).build()
        try{
            database.openHelper.writableDatabase
            assertEquals(1,database.anchorDao().sessions().first().size);assertEquals(0L,database.incidentLogDao().count())
            val indices=database.openHelper.writableDatabase.query("PRAGMA index_list(incident_log)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
            assertTrue(indices.containsAll(setOf("index_incident_log_timestamp","index_incident_log_category","index_incident_log_severity")))
        }finally{database.close();context.deleteDatabase(name)}
    }

    @Test fun migration7To8PreservesV7SurveyAndCreatesOnlyDerivedCaches()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="migration-v7-v8-${System.nanoTime()}.db";context.deleteDatabase(name);createV5(context,name);upgradeToV7(context,name)
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(Migration7To8,Migration8To9,Migration9To10,Migration10To11,Migration11To12,Migration12To13).build()
        try{
            database.openHelper.writableDatabase
            val survey=database.sonarDao().survey(91L)!!
            assertEquals("V7 survey",survey.name);assertEquals(0.0,survey.sounderOffsetMeters,0.0)
            val tables=database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(0))}}
            assertTrue(tables.containsAll(setOf("sonar_grid_cells","linz_depth_cache","tide_prediction_cache","incident_log")))
            val sampleColumns=database.openHelper.writableDatabase.query("PRAGMA table_info(depth_samples)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
            assertTrue(sampleColumns.containsAll(setOf("tideHeightMetersApplied","tideCorrectionMode","tideStationId","tideStationDistanceMeters","tidePredictionYear","tideCorrectionStatus","tideSource","tideSourceUpdatedAt")))
            assertEquals(0L,database.sonarDao().gridCellCount())
        }finally{database.close();context.deleteDatabase(name)}
    }

    @Test fun migrationPreservesV5SessionAndAddsSafetyMetadata()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v5-v6-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        createV5(context,name)
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(Migration5To6,Migration6To7,Migration7To8,Migration8To9,Migration9To10,Migration10To11,Migration11To12,Migration12To13).build()
        try {
            database.openHelper.writableDatabase
            val session=database.anchorDao().sessions().first().single()
            assertEquals(1234L,session.startedAt)
            assertEquals(-36.8485,session.anchorLatitude,0.000001)
            assertEquals("UNKNOWN",session.positionSource)
            assertEquals(0.0,session.maxDistanceMeters,0.0)
            assertEquals(0,session.candidateSwingReversalCount)
            val columns=database.openHelper.writableDatabase.query("PRAGMA table_info(track_points)").use { cursor ->
                buildSet { while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(columns.containsAll(setOf("positionProvider","fixTrust","headingSource","headingEpoch")))
            val tables=database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(0))}}
            assertTrue(tables.containsAll(setOf("sonar_surveys","depth_samples")))
            val sonarColumns=database.openHelper.writableDatabase.query("PRAGMA table_info(depth_samples)").use{cursor->buildMap{while(cursor.moveToNext())put(cursor.getString(cursor.getColumnIndexOrThrow("name")),cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))}}
            assertTrue(sonarColumns.keys.containsAll(setOf("baseGridX","baseGridY","sourceElapsedRealtime","rawDepthMeters","measuredDepthMeters","normalizedDepthMeters","gpsSource","positionProvider","positionCorrectionApplied")))
            assertEquals(0,sonarColumns.getValue("normalizedDepthMeters"))
            val sonarIndices=database.openHelper.writableDatabase.query("PRAGMA index_list(depth_samples)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
            assertTrue(sonarIndices.containsAll(setOf("index_depth_samples_baseGridX_baseGridY","index_depth_samples_surveyId_baseGridX_baseGridY","index_depth_samples_surveyId_sourceElapsedRealtime")))
            val surveyColumns=database.openHelper.writableDatabase.query("PRAGMA table_info(sonar_surveys)").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
            assertTrue(surveyColumns.contains("sounderOffsetMeters"))
            val v8Tables=database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(0))}}
            assertTrue(v8Tables.containsAll(setOf("sonar_grid_cells","linz_depth_cache","tide_prediction_cache","incident_log")))
            val gridPrimaryKey=database.openHelper.writableDatabase.query("PRAGMA table_info(sonar_grid_cells)").use{cursor->buildMap{while(cursor.moveToNext())put(cursor.getString(cursor.getColumnIndexOrThrow("name")),cursor.getInt(cursor.getColumnIndexOrThrow("pk")))}}
            assertEquals(1,gridPrimaryKey.getValue("scopeType"));assertEquals(2,gridPrimaryKey.getValue("scopeId"));assertEquals(3,gridPrimaryKey.getValue("gridX"));assertEquals(4,gridPrimaryKey.getValue("gridY"))
        } finally {
            database.close();context.deleteDatabase(name)
        }
    }

    private fun createV5(context:Context,name:String){
        val configuration=SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(5){
            override fun onCreate(db:SupportSQLiteDatabase){
                db.execSQL("""CREATE TABLE IF NOT EXISTS `anchor_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `anchorLatitude` REAL NOT NULL, `anchorLongitude` REAL NOT NULL, `rodeLengthMeters` REAL NOT NULL, `waterDepthMeters` REAL, `bowRollerHeightMeters` REAL NOT NULL, `gpsAntennaOffsetMeters` REAL NOT NULL, `expectedSwingRadiusMeters` REAL NOT NULL, `warningRadiusMeters` REAL NOT NULL, `alarmRadiusMeters` REAL NOT NULL, `active` INTEGER NOT NULL, `paused` INTEGER NOT NULL DEFAULT 0, `placementMode` TEXT NOT NULL DEFAULT 'CENTER_DROP', `centerStatus` TEXT NOT NULL DEFAULT 'RESOLVED', `centerResolvedAt` INTEGER, `centerConfidence` TEXT NOT NULL DEFAULT 'HIGH', `centerSampleCount` INTEGER NOT NULL DEFAULT 0, `boatLengthMeters` REAL, `rangeMode` TEXT NOT NULL DEFAULT 'BASIC', `safetyPreset` TEXT NOT NULL DEFAULT 'BALANCED', `alarmSnoozedUntil` INTEGER, `learningReferenceLatitude` REAL, `learningReferenceLongitude` REAL, `provisionalAnchorLatitude` REAL, `provisionalAnchorLongitude` REAL, `provisionalRadiusMeters` REAL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `distanceFromAnchor` REAL NOT NULL, `sog` REAL, `cog` REAL, `heading` REAL, `hdop` REAL, `windDirectionTrue` REAL, `windSpeedKnots` REAL, `apparentWindAngle` REAL, `trueWindAngle` REAL, `trueWindSpeedKnots` REAL, `apparentWindSpeedKnots` REAL, `headingMeasured` INTEGER NOT NULL DEFAULT 0, `headingSampleSequence` INTEGER, `windSampleSequence` INTEGER, FOREIGN KEY(`sessionId`) REFERENCES `anchor_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_sessionId` ON `track_points` (`sessionId`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `alarm_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_events_sessionId` ON `alarm_events` (`sessionId`)")
                db.execSQL("INSERT INTO anchor_sessions (startedAt,anchorLatitude,anchorLongitude,rodeLengthMeters,waterDepthMeters,bowRollerHeightMeters,gpsAntennaOffsetMeters,expectedSwingRadiusMeters,warningRadiusMeters,alarmRadiusMeters,active) VALUES (1234,-36.8485,174.7633,40,8,1.5,0,38,40,50,1)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also{it.writableDatabase;it.close()}
    }

    private fun upgradeToV7(context:Context,name:String){
        val configuration=SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(7){
            override fun onCreate(db:SupportSQLiteDatabase)=error("Expected the v5 fixture")
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){
                assertEquals(5,oldVersion);assertEquals(7,newVersion);Migration5To6.migrate(db);Migration6To7.migrate(db)
                db.execSQL("INSERT INTO sonar_surveys (id,name,startedAt,active,tideMode,manualTideOffsetMeters,transducerDraftMeters,keelOffsetMeters,gpsToTransducerMeters,configuredDepthReference,sampleCount) VALUES (91,'V7 survey',1234,0,'OFF',0,0,0,0,'UNKNOWN',0)")
            }
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also{it.writableDatabase;it.close()}
    }

    private fun upgradeToV10(context:Context,name:String){
        val configuration=SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(10){
            override fun onCreate(db:SupportSQLiteDatabase)=error("Expected the v5 fixture")
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){
                assertEquals(5,oldVersion);assertEquals(10,newVersion)
                Migration5To6.migrate(db);Migration6To7.migrate(db);Migration7To8.migrate(db);Migration8To9.migrate(db);Migration9To10.migrate(db)
            }
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also{it.writableDatabase;it.close()}
    }

    private fun upgradeFromV5(context:Context,name:String,targetVersion:Int){
        val configuration=SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(targetVersion){
            override fun onCreate(db:SupportSQLiteDatabase)=error("Expected the v5 fixture")
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){
                assertEquals(5,oldVersion);Migration5To6.migrate(db);Migration6To7.migrate(db);Migration7To8.migrate(db)
                if(targetVersion>=9)Migration8To9.migrate(db)
                db.execSQL("INSERT INTO sonar_surveys (id,name,startedAt,active,tideMode,manualTideOffsetMeters,transducerDraftMeters,keelOffsetMeters,gpsToTransducerMeters,configuredDepthReference,sounderOffsetMeters,sampleCount${if(targetVersion>=9)",tideStationId,tideStationName" else ""}) VALUES (91,'V$targetVersion survey',1234,0,'OFF',0,0,0,0,'UNKNOWN',0,0${if(targetVersion>=9)",'auckland','Auckland'" else ""})")
            }
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also{it.writableDatabase;it.close()}
    }
}
