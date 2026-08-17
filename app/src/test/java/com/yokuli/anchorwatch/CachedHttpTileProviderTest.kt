package com.yokuli.anchorwatch

import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.map.nautical.OpenSeaMapConfiguration
import com.yokuli.anchorwatch.map.tiles.CachedHttpTileProvider
import com.yokuli.anchorwatch.map.tiles.HttpTileFetcher
import com.yokuli.anchorwatch.map.tiles.HttpTileResponse
import com.yokuli.anchorwatch.map.tiles.TileDiagnosticsSink
import java.io.File
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedHttpTileProviderTest {
    private class Diagnostics : TileDiagnosticsSink {
        var requests=0;var success=0;var failures=0;var memory=0;var disk=0;var stale=0;var code:Int?=null
        override fun requested(){requests++};override fun memoryHit(){memory++}
        override fun diskHit(stale:Boolean){disk++;if(stale)this.stale++}
        override fun succeeded(httpCode:Int){success++;code=httpCode}
        override fun failed(httpCode:Int?,message:String){failures++;code=httpCode}
    }

    @Test fun openSeaMapBuildsHttpsUrlsAndRejectsInvalidCoordinates() {
        assertEquals("https://tiles.openseamap.org/seamark/3/4/2.png", OpenSeaMapConfiguration.tileUrl(4,2,3).toString())
        assertNull(OpenSeaMapConfiguration.tileUrl(-1,0,3))
        assertNull(OpenSeaMapConfiguration.tileUrl(8,0,3))
        assertNull(OpenSeaMapConfiguration.tileUrl(0,8,3))
        assertNull(OpenSeaMapConfiguration.tileUrl(0,0,25))
    }

    @Test fun successfulResponseIsHeldInMemoryWithoutAnotherRequest() {
        val diagnostics=Diagnostics();var calls=0;val bytes=byteArrayOf(1,2,3)
        val provider=provider(diagnostics=diagnostics,fetcher=HttpTileFetcher{_,_,_->calls++;HttpTileResponse(200,bytes)})
        assertArrayEquals(bytes,provider.getTile(0,0,0).data)
        assertArrayEquals(bytes,provider.getTile(0,0,0).data)
        assertEquals(1,calls);assertEquals(1,diagnostics.success);assertEquals(1,diagnostics.memory)
    }

    @Test fun httpErrorsEmptyAndOversizeResponsesFailGracefully() {
        listOf(
            HttpTileResponse(404,byteArrayOf()),
            HttpTileResponse(500,byteArrayOf()),
            HttpTileResponse(200,byteArrayOf()),
            HttpTileResponse(200,ByteArray(CachedHttpTileProvider.MAX_TILE_BYTES+1)),
        ).forEach { response ->
            val diagnostics=Diagnostics();val tile=provider(diagnostics=diagnostics,fetcher=HttpTileFetcher{_,_,_->response}).getTile(0,0,0)
            assertTrue(tile===TileProvider.NO_TILE);assertEquals(1,diagnostics.failures)
        }
    }

    @Test fun timeoutUsesStaleDiskCacheInsteadOfBreakingTheMap() {
        val directory=Files.createTempDirectory("anchor-tile-stale").toFile();val bytes=byteArrayOf(8,9)
        provider(directory=directory,fetcher=HttpTileFetcher{_,_,_->HttpTileResponse(200,bytes)}).getTile(0,0,0)
        val diagnostics=Diagnostics()
        val stale=provider(directory=directory,diagnostics=diagnostics,clock={System.currentTimeMillis()+CachedHttpTileProvider.REFRESH_AFTER_MILLIS+1},fetcher=HttpTileFetcher{_,_,_->throw SocketTimeoutException("timeout")}).getTile(0,0,0)
        assertArrayEquals(bytes,stale.data);assertEquals(1,diagnostics.failures);assertEquals(1,diagnostics.stale)
    }

    @Test fun freshDiskCacheAvoidsNetworkAndDiskPruningIsBounded() {
        val directory=Files.createTempDirectory("anchor-tile-disk").toFile();val bytes=byteArrayOf(4,5,6)
        provider(directory=directory,fetcher=HttpTileFetcher{_,_,_->HttpTileResponse(200,bytes)}).getTile(0,0,0)
        val diagnostics=Diagnostics();var calls=0
        assertArrayEquals(bytes,provider(directory=directory,diagnostics=diagnostics,fetcher=HttpTileFetcher{_,_,_->calls++;HttpTileResponse(500,byteArrayOf())}).getTile(0,0,0).data)
        assertEquals(0,calls);assertEquals(1,diagnostics.disk)

        val old=File(directory,"old.tile").apply{writeBytes(ByteArray(6));setLastModified(1)}
        val current=File(directory,"current.tile").apply{writeBytes(ByteArray(6));setLastModified(2)}
        val bounded=provider(directory=directory,maxDiskBytes=8)
        bounded.pruneDiskNow()
        assertFalse(old.exists());assertTrue(current.exists()||directory.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=8)
    }

    private fun provider(
        directory:File=Files.createTempDirectory("anchor-tile").toFile(),
        diagnostics:Diagnostics=Diagnostics(),
        fetcher:HttpTileFetcher=HttpTileFetcher{_:URL,_,_->HttpTileResponse(200,byteArrayOf(1))},
        clock:()->Long=System::currentTimeMillis,
        maxDiskBytes:Long=CachedHttpTileProvider.MAX_DISK_BYTES,
    )=CachedHttpTileProvider(
        urlFor={x,y,z->URL("https://tiles.example/$z/$x/$y.png")},
        diskCacheDirectory=directory,
        userAgent="Anchor-Watch/test Android",
        diagnostics=diagnostics,
        fetcher=fetcher,
        clockMillis=clock,
        maxDiskBytes=maxDiskBytes,
    )
}
