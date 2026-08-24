package com.yokuli.anchorwatch.domain.anchorage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AnchorageGeometryCodec {
    const val MAX_JSON_BYTES=2*1024*1024
    const val MAX_COORDINATES=20_000

    fun decode(value:String):AnchorageGeometry{
        require(value.toByteArray(Charsets.UTF_8).size<=MAX_JSON_BYTES){"Geometry is larger than 2 MB"}
        val root=JsonParser.parseString(value).asJsonObject
        return when(root.requireString("type")){
            "Point"->AnchorageGeometry.Point(root.getAsJsonArray("coordinates").point())
            "Polygon"->AnchorageGeometry.Polygon(root.getAsJsonArray("coordinates").rings())
            "MultiPolygon"->{
                var count=0
                val polygons=root.getAsJsonArray("coordinates").map{polygonElement->
                    val rings=polygonElement.asJsonArray.rings().also{count+=it.sumOf(List<AnchorageGeoPoint>::size)}
                    AnchorageGeometry.Polygon(rings)
                }
                require(count<=MAX_COORDINATES){"Geometry has too many coordinates"}
                AnchorageGeometry.MultiPolygon(polygons)
            }
            else->error("Unsupported GeoJSON geometry")
        }
    }

    fun encode(geometry:AnchorageGeometry):String=JsonObject().apply{
        when(geometry){
            is AnchorageGeometry.Point->{addProperty("type","Point");add("coordinates",geometry.point.array())}
            is AnchorageGeometry.Circle->{addProperty("type","Point");add("coordinates",geometry.center.array());addProperty("yokuliRadiusMeters",geometry.radiusMeters)}
            is AnchorageGeometry.Polygon->{addProperty("type","Polygon");add("coordinates",geometry.rings.array())}
            is AnchorageGeometry.MultiPolygon->{addProperty("type","MultiPolygon");add("coordinates",JsonArray().also{outer->geometry.polygons.forEach{outer.add(it.rings.array())}})}
        }
    }.toString()

    private fun JsonObject.requireString(key:String)=get(key)?.takeIf{it.isJsonPrimitive}?.asString?:error("Missing GeoJSON $key")
    private fun JsonArray.point():AnchorageGeoPoint{require(size()>=2);val lon=get(0).asDouble;val lat=get(1).asDouble;require(lon.isFinite()&&lat.isFinite());return AnchorageGeoPoint(lat,lon)}
    private fun JsonArray.rings():List<List<AnchorageGeoPoint>>{var count=0;val result=map{ringElement->ringElement.asJsonArray.map{it.asJsonArray.point()}.also{ring->count+=ring.size;require(ring.size>=4&&ring.first()==ring.last()){"Polygon ring must be closed"}}};require(result.isNotEmpty());require(count<=MAX_COORDINATES){"Geometry has too many coordinates"};return result}
    private fun AnchorageGeoPoint.array()=JsonArray().also{it.add(longitude);it.add(latitude)}
    private fun List<List<AnchorageGeoPoint>>.array()=JsonArray().also{outer->forEach{ring->outer.add(JsonArray().also{array->ring.forEach{array.add(it.array())}})}}
}
