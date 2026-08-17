package com.yokuli.anchorwatch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.IncidentSeverity
import com.yokuli.anchorwatch.map.OfflineMapRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OperationalDataSafetyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun incidentBoundaryRemovesPositionsRawSentencesAndCredentials()=runBlocking{
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            val logger=IncidentLogger(database.incidentLogDao())
            logger.recordNow("gps","REJECTED",IncidentSeverity.WARNING,details=mapOf(
                "reason" to "jump","latitude" to -36.84,"rawSentence" to "GPGGA,secret","apiKey" to "secret","accuracy" to 12.0,
            ))
            val row=database.incidentLogDao().since(0).single()
            assertFalse(row.details.contains("36.84"));assertFalse(row.details.contains("GPGGA"));assertFalse(row.details.contains("secret"));assertTrue(row.details.contains("accuracy"))
        }finally{database.close()}
    }

    @Test fun rasterMbtilesImportsToPrivateStorageAndUsesTmsRows()=runBlocking{
        val source=File(context.cacheDir,"offline-test-${System.nanoTime()}.mbtiles")
        val sqlite=SQLiteDatabase.openOrCreateDatabase(source,null)
        try{
            sqlite.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            sqlite.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            sqlite.execSQL("INSERT INTO metadata VALUES ('name','Test chart'),('format','png'),('scheme','tms'),('attribution','Test only')")
            val image=byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3)
            sqlite.insertOrThrow("tiles",null,ContentValues().apply{put("zoom_level",0);put("tile_column",0);put("tile_row",0);put("tile_data",image)})
        }finally{sqlite.close()}
        val repository=OfflineMapRepository(context)
        try{
            val result=repository.import(Uri.fromFile(source)).getOrThrow()
            assertEquals("Test chart",result.info.name);assertTrue(result.info.installed);assertEquals(1,result.info.tileCount)
            val provider=repository.provider()!!
            try{assertNotEquals(TileProvider.NO_TILE,provider.getTile(0,0,0))}finally{provider.close()}
        }finally{repository.remove();source.delete()}
    }
}
