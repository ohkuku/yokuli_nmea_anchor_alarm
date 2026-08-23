package com.yokuli.anchorwatch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.map.MbTilesTileProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MbTilesProviderInstrumentedTest{
    @Test fun xyzAndTmsSchemesResolveRowsAndOutOfRangeRequestsNeverQuery(){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val file=File(context.cacheDir,"mbtiles-provider-${System.nanoTime()}.mbtiles")
        val bytes=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47)
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            db.insertOrThrow("tiles",null,ContentValues().apply{put("zoom_level",1);put("tile_column",0);put("tile_row",0);put("tile_data",bytes)})
        }
        try{
            MbTilesTileProvider(file,"xyz",1,1).use{provider->assertArrayEquals(bytes,provider.getTile(0,0,1).data);assertSame(TileProvider.NO_TILE,provider.getTile(0,1,1));assertSame(TileProvider.NO_TILE,provider.getTile(-1,0,1));assertSame(TileProvider.NO_TILE,provider.getTile(0,0,2))}
            MbTilesTileProvider(file,"tms",1,1).use{provider->assertArrayEquals(bytes,provider.getTile(0,1,1).data);assertSame(TileProvider.NO_TILE,provider.getTile(0,0,1))}
        }finally{file.delete()}
    }
}
