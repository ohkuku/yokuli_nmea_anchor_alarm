package com.yokuli.anchorwatch.data.linz

import com.yokuli.anchorwatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

data class LinzWfsResult(val soundings:List<HydroFeature>,val areas:List<HydroFeature>,val contours:List<HydroFeature>,val requestCount:Int,val lastHttpCode:Int?,val errors:List<String>)

@Singleton
open class LinzWfsClient @Inject constructor(){
    val soundingLayerIds=ids(BuildConfig.LINZ_SOUNDING_LAYER_IDS);val areaLayerIds=ids(BuildConfig.LINZ_DEPTH_AREA_LAYER_IDS);val contourLayerIds=ids(BuildConfig.LINZ_DEPTH_CONTOUR_LAYER_IDS)
    open val allLayerIds=soundingLayerIds+areaLayerIds+contourLayerIds;open val configured get()=BuildConfig.LINZ_API_KEY.isNotBlank()&&allLayerIds.isNotEmpty()

    open suspend fun query(latitude:Double,longitude:Double):LinzWfsResult=coroutineScope{
        if(!configured)throw IllegalStateException("LINZ vector depth is not configured")
        val sounding=async{fetch(soundingLayerIds,latitude,longitude,250.0,HydroFeatureKind.SOUNDING)};val area=async{fetch(areaLayerIds,latitude,longitude,20.0,HydroFeatureKind.DEPTH_AREA)};val contour=async{fetch(contourLayerIds,latitude,longitude,100.0,HydroFeatureKind.DEPTH_CONTOUR)}
        val results=listOf(sounding.await(),area.await(),contour.await());if(results.all{it.error!=null})throw IOException("LINZ WFS unavailable")
        LinzWfsResult(results[0].features,results[1].features,results[2].features,3,results.mapNotNull{it.code}.lastOrNull(),results.mapNotNull{it.error})
    }

    private data class FetchResult(val features:List<HydroFeature>,val code:Int?,val error:String?)
    private suspend fun fetch(layers:List<String>,latitude:Double,longitude:Double,radiusMeters:Double,kind:HydroFeatureKind)=withContext(Dispatchers.IO){
        var connection:HttpURLConnection?=null
        try{
            val latDelta=radiusMeters/111_320.0;val lonDelta=radiusMeters/(111_320.0*cos(Math.toRadians(latitude)).coerceAtLeast(.15));val bbox=String.format(Locale.US,"bbox(shape,%.8f,%.8f,%.8f,%.8f)",latitude-latDelta,longitude-lonDelta,latitude+latDelta,longitude+lonDelta)
            val types=layers.joinToString(","){"layer-$it"};val filters=layers.joinToString(";"){bbox};val params=linkedMapOf("service" to "WFS","version" to "2.0.0","request" to "GetFeature","typeNames" to types,"count" to "400","srsName" to "EPSG:4326","outputFormat" to "json","cql_filter" to filters)
            val query=params.entries.joinToString("&"){(key,value)->"${encode(key)}=${encode(value)}"};val url=URL("https://data.linz.govt.nz/services;key=${BuildConfig.LINZ_API_KEY}/wfs?$query")
            connection=(url.openConnection() as HttpURLConnection).apply{connectTimeout=8_000;readTimeout=12_000;requestMethod="GET";setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Anchor-by-Yokuli/${BuildConfig.VERSION_NAME}")}
            val code=connection.responseCode;if(code !in 200..299){connection.errorStream?.close();return@withContext FetchResult(emptyList(),code,"HTTP $code")}
            val bytes=connection.inputStream.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);if(output.size()>4_000_000)throw IOException("LINZ response too large")};output.toByteArray()}
            FetchResult(LinzHydroFeatureParser.parse(bytes.toString(Charsets.UTF_8),kind),code,null)
        }catch(error:Throwable){if(error is CancellationException)throw error;FetchResult(emptyList(),null,error.javaClass.simpleName)}finally{connection?.disconnect()}
    }
    private fun ids(value:String)=value.split('|').map(String::trim).filter{value->value.isNotEmpty()&&value.all{it.isDigit()}}
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
}
