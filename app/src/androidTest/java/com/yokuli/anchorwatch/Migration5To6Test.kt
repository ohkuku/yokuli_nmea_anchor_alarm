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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @Test fun migrationPreservesV5SessionAndAddsSafetyMetadata()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v5-v6-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        createV5(context,name)
        val database=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(Migration5To6).build()
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
}
