package com.yokuli.anchorwatch.map

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

object MapOverlayZ {
    const val OFFLINE_CHART=.1f
    const val LINZ_CHART=.2f
    const val SONAR=.8f
    const val TRAIL=1f
    const val ALARM_GEOMETRY=1.5f
    const val ANCHOR=2f
    const val BOAT=3f
}

object LinzHydroConfiguration {
    const val ATTRIBUTION = "Contains data sourced from the LINZ Data Service licensed for reuse under CC BY 4.0"

    fun isUsable(template: String): Boolean = template.startsWith("https://") &&
        listOf("{z}", "{x}", "{y}").all(template::contains)

    fun isOverlayVisible(configured:Boolean,enabled:Boolean)=configured&&enabled
    fun transparency(opacity:Double)=(1.0-opacity.coerceIn(.30,1.0)).toFloat()

    fun tileUrl(template: String, x: Int, y: Int, zoom: Int): String = template
        .replace("{s}", listOf("a", "b", "c", "d")[(x + y + zoom).mod(4)])
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
}

data class LinzTileDiagnostics(val requests:Long=0,val successes:Long=0,val failures:Long=0,val lastHttpCode:Int?=null,val message:String="Waiting for a chart tile request")
object LinzHydroDiagnostics{
    private val _state=MutableStateFlow(LinzTileDiagnostics());val state=_state.asStateFlow()
    @Synchronized fun requested(){_state.value=_state.value.copy(requests=_state.value.requests+1,message="Requesting LINZ chart tiles")}
    @Synchronized fun succeeded(code:Int){_state.value=_state.value.copy(successes=_state.value.successes+1,lastHttpCode=code,message="LINZ chart tile loaded")}
    @Synchronized fun failed(code:Int?,message:String){_state.value=_state.value.copy(failures=_state.value.failures+1,lastHttpCode=code,message=message)}
}

class LinzHydroTileProvider(private val template: String,private val diskCacheDirectory:File?=null) : TileProvider {
    private val cache=object:LinkedHashMap<String,Tile>(64,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Tile>?)=size>64}
    private val diskWrites=AtomicInteger(0)
    fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (!LinzHydroConfiguration.isUsable(template) || zoom !in 0..24 || x < 0 || y < 0) return null
        val extent=1L shl zoom
        if(x.toLong()>=extent||y.toLong()>=extent)return null
        return runCatching { URL(LinzHydroConfiguration.tileUrl(template, x, y, zoom)) }.getOrNull()
    }
    override fun getTile(x:Int,y:Int,zoom:Int):Tile{
        val url=getTileUrl(x,y,zoom)?:return TileProvider.NO_TILE;val key="$zoom/$x/$y";synchronized(cache){cache[key]}?.let{return it}
        LinzHydroDiagnostics.requested()
        val diskFile=diskCacheDirectory?.let{File(it,"$zoom/$x/$y.tile")};val diskBytes=diskFile?.takeIf{it.isFile&&it.length() in 1L..4_000_000L}?.let{runCatching{it.readBytes()}.getOrNull()}
        if(diskBytes!=null&&System.currentTimeMillis()-(diskFile?.lastModified()?:0L)<=REFRESH_AFTER_MILLIS){diskFile?.setLastModified(System.currentTimeMillis());return Tile(256,256,diskBytes).also{synchronized(cache){cache[key]=it};LinzHydroDiagnostics.succeeded(200)}}
        return runCatching{
            val connection=(url.openConnection() as HttpURLConnection).apply{connectTimeout=7_000;readTimeout=10_000;instanceFollowRedirects=true;setRequestProperty("User-Agent","Anchor-by-Yokuli/1.0")}
            try{val code=connection.responseCode;if(code !in 200..299){LinzHydroDiagnostics.failed(code,"LINZ tile HTTP $code");return@runCatching diskBytes?.let{Tile(256,256,it)}?:TileProvider.NO_TILE};val bytes=connection.inputStream.use{it.readBytes()};if(bytes.isEmpty()||bytes.size>4_000_000){LinzHydroDiagnostics.failed(code,"LINZ returned an invalid tile");diskBytes?.let{Tile(256,256,it)}?:TileProvider.NO_TILE}else Tile(256,256,bytes).also{tile->synchronized(cache){cache[key]=tile};writeDisk(diskFile,bytes);LinzHydroDiagnostics.succeeded(code)}}finally{connection.disconnect()}
        }.getOrElse{error->LinzHydroDiagnostics.failed(null,error.message?:error.javaClass.simpleName);diskBytes?.let{Tile(256,256,it)}?:TileProvider.NO_TILE}
    }

    @Synchronized private fun writeDisk(target:File?,bytes:ByteArray){
        target?:return
        runCatching{target.parentFile?.mkdirs();val temp=File(target.parentFile,"${target.name}.tmp");FileOutputStream(temp).use{it.write(bytes)};if(target.exists())target.delete();if(!temp.renameTo(target))temp.delete();if(diskWrites.incrementAndGet()%64==0)pruneDisk()}
    }
    private fun pruneDisk(){
        val directory=diskCacheDirectory?:return
        val files=directory.walkTopDown().filter{it.isFile&&!it.name.endsWith(".tmp")}.toList();var total=files.sumOf{it.length()}
        if(total<=MAX_DISK_BYTES)return
        files.sortedBy{it.lastModified()}.forEach{file->if(total>MAX_DISK_BYTES){val size=file.length();if(file.delete())total-=size}}
    }
    companion object{const val REFRESH_AFTER_MILLIS=7L*24L*60L*60L*1_000L;const val MAX_DISK_BYTES=100L*1024L*1024L}
}
