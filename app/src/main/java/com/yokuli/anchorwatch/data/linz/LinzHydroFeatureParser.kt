package com.yokuli.anchorwatch.data.linz

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import kotlin.math.hypot

enum class HydroFeatureKind{SOUNDING,DEPTH_AREA,DEPTH_CONTOUR}
data class GeoPoint(val longitude:Double,val latitude:Double)
sealed interface HydroGeometry{data class Point(val value:GeoPoint):HydroGeometry;data class Lines(val values:List<List<GeoPoint>>):HydroGeometry;data class Polygons(val values:List<List<List<GeoPoint>>>):HydroGeometry}
data class HydroFeature(val id:String,val layerId:String,val kind:HydroFeatureKind,val geometry:HydroGeometry,val depth:Double?=null,val minDepth:Double?=null,val maxDepth:Double?=null,val scaleMinimum:Double?=null)

object LinzHydroFeatureParser{
    fun parse(geoJson:String,kind:HydroFeatureKind):List<HydroFeature>{
        val root=MiniJson(geoJson).read() as? Map<*,*>?:return emptyList();val features=root["features"] as? List<*>?:return emptyList()
        return features.mapNotNull{raw->feature(raw as? Map<*,*>?:return@mapNotNull null,kind)}.distinctBy{it.id.ifBlank{"${it.layerId}:${it.geometry}:${it.depth}:${it.minDepth}:${it.maxDepth}"}}
    }

    private fun feature(raw:Map<*,*>,kind:HydroFeatureKind):HydroFeature?{
        val id=raw["id"]?.toString().orEmpty();val properties=(raw["properties"] as? Map<*,*>).orEmpty().entries.associate{it.key.toString().lowercase() to it.value};val layer=Regex("layer-(\\d+)").find(id)?.groupValues?.get(1)?:properties["layer_id"]?.toString()?:"unknown"
        val geometry=parseGeometry(raw["geometry"] as? Map<*,*>?:return null)?:return null
        val depth=when(kind){
            HydroFeatureKind.SOUNDING->properties.number("depth")
            HydroFeatureKind.DEPTH_CONTOUR->properties.number("valdco")
            HydroFeatureKind.DEPTH_AREA->null
        }
        return HydroFeature(id,layer,kind,geometry,depth,properties.number("drval1"),properties.number("drval2"),properties.number("scamin"))
    }

    private fun parseGeometry(raw:Map<*,*>):HydroGeometry?{
        val type=raw["type"]?.toString()?:return null;val coordinates=raw["coordinates"] as? List<*>?:return null
        return when(type){
            "Point"->point(coordinates)?.let{HydroGeometry.Point(it)}
            "LineString"->line(coordinates)?.let{HydroGeometry.Lines(listOf(it))}
            "MultiLineString"->HydroGeometry.Lines(coordinates.mapNotNull{line(it as? List<*>?:emptyList())})
            "Polygon"->polygon(coordinates)?.let{HydroGeometry.Polygons(listOf(it))}
            "MultiPolygon"->HydroGeometry.Polygons(coordinates.mapNotNull{polygon(it as? List<*>?:emptyList())})
            else->null
        }
    }
    private fun point(value:List<*>):GeoPoint?{val longitude=(value.getOrNull(0) as? Number)?.toDouble()?:return null;val latitude=(value.getOrNull(1) as? Number)?.toDouble()?:return null;return GeoPoint(longitude,latitude).takeIf{latitude in -90.0..90.0&&longitude in -180.0..180.0}}
    private fun line(value:List<*>)=value.mapNotNull{point(it as? List<*>?:emptyList())}.takeIf{it.size>=2}
    private fun polygon(value:List<*>)=value.mapNotNull{line(it as? List<*>?:emptyList())}.takeIf{it.isNotEmpty()}
    private fun Map<String,Any?>.number(name:String)=(get(name) as? Number)?.toDouble()
}

object LinzHydroSelector{
    fun select(latitude:Double,longitude:Double,queriedAt:Long,soundings:List<HydroFeature>,areas:List<HydroFeature>,contours:List<HydroFeature>,layerOrder:List<String>):LinzDepthReference{
        val rank=layerOrder.withIndex().associate{it.value to it.index};val boat=GeoPoint(longitude,latitude)
        val sounding=soundings.asSequence().filter{it.depth!=null}.mapNotNull{feature->(feature.geometry as? HydroGeometry.Point)?.let{feature to AnchorGeometry.distanceMeters(latitude,longitude,it.value.latitude,it.value.longitude)}}.minWithOrNull(compareBy<Pair<HydroFeature,Double>>{it.second}.thenBy{rank[it.first.layerId]?:Int.MAX_VALUE})
        val area=areas.filter{it.minDepth!=null&&it.maxDepth!=null&&contains(it.geometry,boat)}.minWithOrNull(compareBy<HydroFeature>{rank[it.layerId]?:Int.MAX_VALUE}.thenBy{(it.maxDepth?:0.0)-(it.minDepth?:0.0)})
        val contour=contours.asSequence().filter{it.depth!=null}.mapNotNull{feature->distance(feature.geometry,boat)?.let{feature to it}}.minWithOrNull(compareBy<Pair<HydroFeature,Double>>{it.second}.thenBy{rank[it.first.layerId]?:Int.MAX_VALUE})
        val layers=listOfNotNull(area?.layerId,sounding?.first?.layerId,contour?.first?.layerId).distinct();val available=area!=null||sounding!=null||contour!=null
        val soundingPoint=(sounding?.first?.geometry as? HydroGeometry.Point)?.value
        return LinzDepthReference(latitude,longitude,queriedAt,area?.minDepth,area?.maxDepth,sounding?.first?.depth,sounding?.second,soundingPoint?.latitude,soundingPoint?.longitude,contour?.first?.depth,contour?.second,layers,if(available)LinzDepthStatus.AVAILABLE else LinzDepthStatus.NO_DATA)
    }

    private fun contains(geometry:HydroGeometry,point:GeoPoint):Boolean=(geometry as? HydroGeometry.Polygons)?.values?.any{polygon->polygon.isNotEmpty()&&insideRing(point,polygon.first())&&polygon.drop(1).none{insideRing(point,it)}}==true
    private fun insideRing(point:GeoPoint,ring:List<GeoPoint>):Boolean{var inside=false;var previous=ring.lastOrNull()?:return false;for(current in ring){if((current.latitude>point.latitude)!=(previous.latitude>point.latitude)){val crossing=(previous.longitude-current.longitude)*(point.latitude-current.latitude)/(previous.latitude-current.latitude)+current.longitude;if(point.longitude<crossing)inside=!inside};previous=current};return inside}
    private fun distance(geometry:HydroGeometry,point:GeoPoint):Double?{val target=SonarGrid.project(point.latitude,point.longitude);val lines=when(geometry){is HydroGeometry.Lines->geometry.values;is HydroGeometry.Polygons->geometry.values.flatten();is HydroGeometry.Point->return AnchorGeometry.distanceMeters(point.latitude,point.longitude,geometry.value.latitude,geometry.value.longitude)};return lines.flatMap{line->line.zipWithNext()}.minOfOrNull{(a,b)->val pa=SonarGrid.project(a.latitude,a.longitude);val pb=SonarGrid.project(b.latitude,b.longitude);segmentDistance(target.first,target.second,pa.first,pa.second,pb.first,pb.second)}}
    private fun segmentDistance(px:Double,py:Double,ax:Double,ay:Double,bx:Double,by:Double):Double{val dx=bx-ax;val dy=by-ay;if(dx==0.0&&dy==0.0)return hypot(px-ax,py-ay);val t=(((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0);return hypot(px-(ax+t*dx),py-(ay+t*dy))}
}
