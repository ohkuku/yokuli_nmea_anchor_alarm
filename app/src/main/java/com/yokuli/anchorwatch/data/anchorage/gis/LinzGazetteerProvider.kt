package com.yokuli.anchorwatch.data.anchorage.gis

import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.database.dao.AnchorageRegionDao
import com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GazetteerHttpResponse(val code:Int,val body:String)
open class LinzGazetteerTransport @Inject constructor(){
    open suspend fun get(url:URL,maxBytes:Int=4_000_000):GazetteerHttpResponse=withContext(Dispatchers.IO){
        val connection=(url.openConnection() as HttpURLConnection).apply{connectTimeout=8_000;readTimeout=12_000;requestMethod="GET";setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Anchor-Watch/${BuildConfig.VERSION_NAME}")}
        try{val code=connection.responseCode;val stream=if(code in 200..299)connection.inputStream else connection.errorStream;val bytes=stream?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8_192);while(true){val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);if(output.size()>maxBytes)throw IOException("LINZ Gazetteer response too large")};output.toByteArray()}?:ByteArray(0);GazetteerHttpResponse(code,bytes.toString(Charsets.UTF_8))}finally{connection.disconnect()}
    }
}

@Singleton class LinzGazetteerProvider @Inject constructor(private val dao:AnchorageRegionDao,private val transport:LinzGazetteerTransport):AnchorageRegionProvider{
    override val providerId="LINZ_GAZETTEER"
    override suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double):Result<List<AnchorageRegionCandidate>>{
        if(!configured||!isNewZealand(latitude,longitude))return Result.success(emptyList())
        return runCatching{
            val now=System.currentTimeMillis();val values=LAYERS.flatMap{layer->val response=transport.get(requestUrl(layer,latitude,longitude,radiusMeters.coerceIn(5_000.0,15_000.0)));if(response.code !in 200..299)throw IOException("LINZ Gazetteer HTTP ${response.code}");LinzGazetteerParser.parse(response.body,layer,latitude,longitude)}
            val ranked=AnchorageRegionCandidateRanker.rank(values)
            ranked.forEach{candidate->val existing=candidate.externalId?.let{dao.byExternalId(providerId,it)};val geometry=candidate.geometry;val box=geometry?.let(AnchorageGeometryOps::bbox)?:AnchorageGeometryOps.envelope(AnchorageGeoPoint(candidate.centerLatitude,candidate.centerLongitude),20.0);dao.upsert(AnchorageRegionEntity(id=existing?.id?:0,parentRegionId=existing?.parentRegionId,displayName=candidate.displayName,officialName=candidate.officialName,alternateNamesJson=existing?.alternateNamesJson?:"[]",provider=providerId,externalId=candidate.externalId,featureType=candidate.featureType.name,geometryType=geometry?.let{when(it){is AnchorageGeometry.Point->"POINT";is AnchorageGeometry.Circle->"CIRCLE";is AnchorageGeometry.Polygon->"POLYGON";is AnchorageGeometry.MultiPolygon->"MULTI_POLYGON"}}?:"POINT",geometryGeoJson=geometry?.let(AnchorageGeometryCodec::encode),centerLatitude=candidate.centerLatitude,centerLongitude=candidate.centerLongitude,bboxMinLatitude=box.minLatitude,bboxMaxLatitude=box.maxLatitude,bboxMinLongitude=box.minLongitude,bboxMaxLongitude=box.maxLongitude,official=true,userConfirmed=existing?.userConfirmed?:false,custom=false,sourceUpdatedAt=candidate.sourceUpdatedAt,lastResolvedAt=now,createdAt=existing?.createdAt?:now,updatedAt=now))}
            ranked
        }
    }

    private fun requestUrl(layer:String,latitude:Double,longitude:Double,radiusMeters:Double):URL{
        val (south,west,north,east)=bounds(latitude,longitude,radiusMeters);val root="https://data.linz.govt.nz/services;key=${BuildConfig.LINZ_API_KEY}/wfs/layer-$layer/"
        val values=linkedMapOf("service" to "WFS","version" to "2.0.0","request" to "GetFeature","typeNames" to "layer-$layer","count" to "300","srsName" to "EPSG:4326","outputFormat" to "json","bbox" to "$south,$west,$north,$east,EPSG:4326")
        return URL(root+"?"+values.entries.joinToString("&"){"${URLEncoder.encode(it.key,"UTF-8")}=${URLEncoder.encode(it.value,"UTF-8")}"})
    }
    companion object{val LAYERS=listOf("51681","52423","52424");val configured get()=BuildConfig.LINZ_API_KEY.isNotBlank();fun isNewZealand(latitude:Double,longitude:Double)=latitude in -48.5..-33.0&&longitude in 165.0..180.0}
}

object LinzGazetteerParser {
    fun parse(json:String,layerId:String,latitude:Double,longitude:Double):List<AnchorageRegionCandidate>{
        val root=com.google.gson.JsonParser.parseString(json).asJsonObject;val features=root.getAsJsonArray("features")?:return emptyList();val point=AnchorageGeoPoint(latitude,longitude)
        return features.mapNotNull{element->runCatching{parseFeature(element.asJsonObject,layerId,point)}.getOrNull()}
    }
    private fun parseFeature(feature:com.google.gson.JsonObject,layerId:String,point:AnchorageGeoPoint):AnchorageRegionCandidate?{
        val properties=feature.getAsJsonObject("properties")?:com.google.gson.JsonObject()
        val propertyId=properties.get("id")?.takeIf{it.isJsonPrimitive}?.asString
        val id=feature.get("id")?.takeIf{it.isJsonPrimitive}?.asString?:propertyId?.let{"layer-$layerId:$it"}?:return null
        val displayName=name(properties)?:return null
        val geometry=feature.getAsJsonObject("geometry")?.let{runCatching{AnchorageGeometryCodec.decode(it.toString())}.getOrNull()}?:return null
        val center=AnchorageGeometryOps.centroid(geometry)
        val updated=properties.entrySet().firstOrNull{it.key.lowercase() in setOf("modified","updated_at","source_date") }?.value?.takeIf{it.isJsonPrimitive}?.asString?.let{runCatching{java.time.Instant.parse(it).toEpochMilli()}.getOrNull()}
        return AnchorageRegionCandidate(provider="LINZ_GAZETTEER",externalId=id,displayName=displayName,officialName=displayName,featureType=featureType(properties,displayName),geometry=geometry,centerLatitude=center.latitude,centerLongitude=center.longitude,containsPoint=AnchorageGeometryOps.contains(geometry,point),distanceMeters=AnchorageGeometryOps.distance(point,center),official=true,sourceUpdatedAt=updated)
    }
    private fun name(properties:com.google.gson.JsonObject):String?=listOf("name","name_","official_name","name_text","placename","full_name").firstNotNullOfOrNull{key->properties.entrySet().firstOrNull{it.key.equals(key,true)}?.value?.takeIf{it.isJsonPrimitive}?.asString?.trim()?.takeIf(String::isNotBlank)}
    private fun featureType(properties:com.google.gson.JsonObject,name:String):AnchorageRegionFeatureType{val raw=(listOf("feature_type","feat_type","featdesc","type","description").firstNotNullOfOrNull{key->properties.entrySet().firstOrNull{it.key.equals(key,true)}?.value?.asString}+" "+name).lowercase();return when{ "cove" in raw->AnchorageRegionFeatureType.COVE;"bay" in raw->AnchorageRegionFeatureType.BAY;"inlet" in raw->AnchorageRegionFeatureType.INLET;"harbour" in raw||"harbor" in raw->AnchorageRegionFeatureType.HARBOUR;"gulf" in raw->AnchorageRegionFeatureType.GULF;"island" in raw->AnchorageRegionFeatureType.ISLAND;"sound" in raw->AnchorageRegionFeatureType.SOUND;"passage" in raw->AnchorageRegionFeatureType.PASSAGE;"coast" in raw->AnchorageRegionFeatureType.COAST;else->AnchorageRegionFeatureType.UNKNOWN}}
}
