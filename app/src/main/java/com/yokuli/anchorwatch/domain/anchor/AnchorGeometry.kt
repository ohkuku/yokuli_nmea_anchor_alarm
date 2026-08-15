package com.yokuli.anchorwatch.domain.anchor

import kotlin.math.*
object AnchorGeometry {
 private const val R=6371008.8
 fun distanceMeters(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double { val p1=Math.toRadians(aLat);val p2=Math.toRadians(bLat);val dp=p2-p1;val dl=Math.toRadians(bLon-aLon);val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 2*R*asin(sqrt(h.coerceIn(0.0,1.0))) }
 fun bearingDegrees(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double { val p1=Math.toRadians(aLat);val p2=Math.toRadians(bLat);val dl=Math.toRadians(bLon-aLon);return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360)%360 }
 fun project(lat:Double,lon:Double,bearing:Double,distance:Double):Pair<Double,Double>{val p=Math.toRadians(lat);val l=Math.toRadians(lon);val b=Math.toRadians(bearing);val d=distance/R;val p2=asin(sin(p)*cos(d)+cos(p)*sin(d)*cos(b));val l2=l+atan2(sin(b)*sin(d)*cos(p),cos(d)-sin(p)*sin(p2));return Math.toDegrees(p2) to ((Math.toDegrees(l2)+540)%360-180)}
 fun scope(rode:Double,depth:Double,bow:Double)=if(depth+bow>0)rode/(depth+bow) else null
 fun expectedRadius(rode:Double,depth:Double?,bow:Double=0.0,offset:Double=0.0)=if(depth==null)rode+offset else sqrt(max(rode*rode-(depth+bow).pow(2),0.0))+offset
}
