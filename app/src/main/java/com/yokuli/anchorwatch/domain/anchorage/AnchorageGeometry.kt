package com.yokuli.anchorwatch.domain.anchorage

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry as MarineGeometry
import kotlin.math.*

data class AnchorageGeoPoint(val latitude:Double,val longitude:Double){
    init { require(latitude.isFinite()&&longitude.isFinite()&&latitude in -90.0..90.0&&longitude in -180.0..180.0) }
}

data class AnchorageBoundingBox(val minLatitude:Double,val maxLatitude:Double,val minLongitude:Double,val maxLongitude:Double){
    init { require(listOf(minLatitude,maxLatitude,minLongitude,maxLongitude).all(Double::isFinite));require(minLatitude<=maxLatitude);require(minLatitude>=-90&&maxLatitude<=90);require(minLongitude>=-180&&maxLongitude<=180) }
    fun intersects(viewport:AnchorageViewport):Boolean=viewport.queryWindows().any{maxLatitude>=it.south&&minLatitude<=it.north&&maxLongitude>=it.west&&minLongitude<=it.east}
}

sealed interface AnchorageGeometry {
    data class Point(val point:AnchorageGeoPoint):AnchorageGeometry
    data class Circle(val center:AnchorageGeoPoint,val radiusMeters:Double):AnchorageGeometry{init{require(radiusMeters.isFinite()&&radiusMeters>=0)}}
    data class Polygon(val rings:List<List<AnchorageGeoPoint>>):AnchorageGeometry{init{require(rings.isNotEmpty());rings.forEach{require(it.size>=4&&it.first()==it.last())}}}
    data class MultiPolygon(val polygons:List<Polygon>):AnchorageGeometry{init{require(polygons.isNotEmpty())}}
}

object AnchorageGeometryOps {
    private const val EARTH_RADIUS=6_371_000.0

    fun bbox(geometry:AnchorageGeometry):AnchorageBoundingBox=when(geometry){
        is AnchorageGeometry.Point->AnchorageBoundingBox(geometry.point.latitude,geometry.point.latitude,geometry.point.longitude,geometry.point.longitude)
        is AnchorageGeometry.Circle->{
            val latDelta=Math.toDegrees(geometry.radiusMeters/EARTH_RADIUS)
            val lonDelta=latDelta/cos(Math.toRadians(geometry.center.latitude)).coerceAtLeast(0.01)
            AnchorageBoundingBox((geometry.center.latitude-latDelta).coerceAtLeast(-90.0),(geometry.center.latitude+latDelta).coerceAtMost(90.0),(geometry.center.longitude-lonDelta).coerceAtLeast(-180.0),(geometry.center.longitude+lonDelta).coerceAtMost(180.0))
        }
        is AnchorageGeometry.Polygon->pointsBbox(geometry.rings.flatten())
        is AnchorageGeometry.MultiPolygon->pointsBbox(geometry.polygons.flatMap{it.rings.flatten()})
    }

    fun centroid(geometry:AnchorageGeometry):AnchorageGeoPoint=when(geometry){
        is AnchorageGeometry.Point->geometry.point
        is AnchorageGeometry.Circle->geometry.center
        is AnchorageGeometry.Polygon->polygonCentroid(geometry.rings.first())
        is AnchorageGeometry.MultiPolygon->{val points=geometry.polygons.map{polygonCentroid(it.rings.first())};sphericalCentroid(points)}
    }

    fun contains(geometry:AnchorageGeometry,point:AnchorageGeoPoint):Boolean=when(geometry){
        is AnchorageGeometry.Point->geometry.point==point
        is AnchorageGeometry.Circle->distance(geometry.center,point)<=geometry.radiusMeters
        is AnchorageGeometry.Polygon->containsPolygon(geometry,point)
        is AnchorageGeometry.MultiPolygon->geometry.polygons.any{containsPolygon(it,point)}
    }

    fun distanceToBoundaryMeters(geometry:AnchorageGeometry,point:AnchorageGeoPoint):Double=when(geometry){
        is AnchorageGeometry.Point->distance(geometry.point,point)
        is AnchorageGeometry.Circle->abs(distance(geometry.center,point)-geometry.radiusMeters)
        is AnchorageGeometry.Polygon->geometry.rings.flattenSegments().minOfOrNull{(a,b)->segmentDistanceMeters(point,a,b)}?:Double.POSITIVE_INFINITY
        is AnchorageGeometry.MultiPolygon->geometry.polygons.minOf{distanceToBoundaryMeters(it,point)}
    }

    fun simplifyForMap(geometry:AnchorageGeometry,toleranceMeters:Double):AnchorageGeometry=when(geometry){
        is AnchorageGeometry.Polygon->AnchorageGeometry.Polygon(geometry.rings.map{simplifyRing(it,toleranceMeters)})
        is AnchorageGeometry.MultiPolygon->AnchorageGeometry.MultiPolygon(geometry.polygons.map{simplifyForMap(it,toleranceMeters) as AnchorageGeometry.Polygon})
        else->geometry
    }

    fun envelope(point:AnchorageGeoPoint,radiusMeters:Double):AnchorageBoundingBox=bbox(AnchorageGeometry.Circle(point,radiusMeters.coerceAtLeast(0.0)))
    fun distance(first:AnchorageGeoPoint,second:AnchorageGeoPoint)=MarineGeometry.distanceMeters(first.latitude,first.longitude,second.latitude,second.longitude)

    private fun pointsBbox(points:List<AnchorageGeoPoint>):AnchorageBoundingBox{require(points.isNotEmpty());return AnchorageBoundingBox(points.minOf{it.latitude},points.maxOf{it.latitude},points.minOf{it.longitude},points.maxOf{it.longitude})}
    private fun containsPolygon(polygon:AnchorageGeometry.Polygon,point:AnchorageGeoPoint):Boolean{
        fun ringContains(ring:List<AnchorageGeoPoint>):Boolean{var inside=false;var j=ring.lastIndex;for(i in ring.indices){val yi=ring[i].latitude;val yj=ring[j].latitude;val xi=unwrap(ring[i].longitude,point.longitude);val xj=unwrap(ring[j].longitude,point.longitude);val crosses=(yi>point.latitude)!=(yj>point.latitude)&&point.longitude<(xj-xi)*(point.latitude-yi)/(yj-yi)+xi;if(crosses)inside=!inside;j=i};return inside}
        return ringContains(polygon.rings.first())&&!polygon.rings.drop(1).any(::ringContains)
    }
    private fun unwrap(longitude:Double,reference:Double):Double{var value=longitude;while(value-reference>180)value-=360.0;while(value-reference< -180)value+=360.0;return value}
    private fun sphericalCentroid(points:List<AnchorageGeoPoint>):AnchorageGeoPoint{var x=0.0;var y=0.0;var z=0.0;points.forEach{val lat=Math.toRadians(it.latitude);val lon=Math.toRadians(it.longitude);x+=cos(lat)*cos(lon);y+=cos(lat)*sin(lon);z+=sin(lat)};return AnchorageGeoPoint(Math.toDegrees(atan2(z,sqrt(x*x+y*y))),((Math.toDegrees(atan2(y,x))+540)%360)-180)}
    private fun polygonCentroid(ring:List<AnchorageGeoPoint>):AnchorageGeoPoint{
        val reference=ring.first().longitude;var twiceArea=0.0;var cx=0.0;var cy=0.0
        for(i in 0 until ring.lastIndex){val x1=unwrap(ring[i].longitude,reference);val y1=ring[i].latitude;val x2=unwrap(ring[i+1].longitude,reference);val y2=ring[i+1].latitude;val cross=x1*y2-x2*y1;twiceArea+=cross;cx+=(x1+x2)*cross;cy+=(y1+y2)*cross}
        if(abs(twiceArea)<1e-12)return sphericalCentroid(ring.dropLast(1))
        return AnchorageGeoPoint(cy/(3*twiceArea),normalizeLongitude(cx/(3*twiceArea)))
    }
    private fun normalizeLongitude(value:Double)=((value+540)%360)-180
    private fun List<List<AnchorageGeoPoint>>.flattenSegments()=flatMap{ring->ring.zipWithNext()}
    private fun segmentDistanceMeters(p:AnchorageGeoPoint,a:AnchorageGeoPoint,b:AnchorageGeoPoint):Double{
        val scale=cos(Math.toRadians(p.latitude));fun xy(q:AnchorageGeoPoint)=Pair(Math.toRadians(unwrap(q.longitude,p.longitude)-p.longitude)*EARTH_RADIUS*scale,Math.toRadians(q.latitude-p.latitude)*EARTH_RADIUS)
        val (ax,ay)=xy(a);val (bx,by)=xy(b);val dx=bx-ax;val dy=by-ay;val t=if(dx*dx+dy*dy==0.0)0.0 else (-(ax*dx+ay*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0);return hypot(ax+t*dx,ay+t*dy)
    }
    private fun simplifyRing(ring:List<AnchorageGeoPoint>,tolerance:Double):List<AnchorageGeoPoint>{if(ring.size<=4||tolerance<=0)return ring;val open=ring.dropLast(1);val keep=BooleanArray(open.size);keep[0]=true;keep[open.lastIndex]=true;fun recurse(start:Int,end:Int){var max=0.0;var index=-1;for(i in start+1 until end){val value=segmentDistanceMeters(open[i],open[start],open[end]);if(value>max){max=value;index=i}};if(index>=0&&max>tolerance){keep[index]=true;recurse(start,index);recurse(index,end)}};recurse(0,open.lastIndex);val result=open.filterIndexed{i,_->keep[i]}.toMutableList();while(result.size<3)result+=open[result.size];result+=result.first();return result}
}
