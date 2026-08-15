package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.*
import kotlin.math.*
import kotlin.random.Random

class AnchorCenterEstimator(private val random:Random=Random.Default){
 data class Point(val latitude:Double,val longitude:Double)
 fun estimate(points:List<Point>,expectedRadius:Double?=null):AnchorEstimate?{
  if(points.size<20)return null
  val oLat=points.map{it.latitude}.average();val oLon=points.map{it.longitude}.average();val cosLat=cos(Math.toRadians(oLat));val xy=points.map{P((it.longitude-oLon)*111320*cosLat,(it.latitude-oLat)*110540)}
  val hull=hull(xy);if(hull.size<3)return null
  var best:Circle?=null;var bestIn=emptyList<P>();repeat(300){val s=hull.shuffled(random).take(3);val c=circumcircle(s[0],s[1],s[2])?:return@repeat;val tolerance=max(3.5,c.r*.12);val inside=hull.filter{abs(hypot(it.x-c.x,it.y-c.y)-c.r)<=tolerance};if(inside.size>bestIn.size){best=c;bestIn=inside}}
  if(bestIn.size<3)return null;val fit=leastSquares(bestIn)?:best!!;val residual=sqrt(hull.map{(hypot(it.x-fit.x,it.y-fit.y)-fit.r).pow(2)}.average());val angles=hull.map{(Math.toDegrees(atan2(it.y-fit.y,it.x-fit.x))+360)%360}.sorted();val maxGap=(angles.zipWithNext{a,b->b-a}+((angles.first()+360)-angles.last())).maxOrNull()?:360.0;val coverage=360-maxGap
  val priorPenalty=expectedRadius?.let{abs(fit.r-it)/max(it,1.0)}?:0.0
  val sectorCount=angles.map{(it/30.0).toInt().coerceIn(0,11)}.distinct().size
  val confidence=when{points.size>=120&&coverage>=200&&sectorCount>=8&&residual<=max(4.0,fit.r*.12)&&priorPenalty<.35->Confidence.HIGH;coverage>=90&&sectorCount>=4&&residual<=8->Confidence.MEDIUM;else->Confidence.LOW}
  return AnchorEstimate(oLat+fit.y/110540,oLon+fit.x/(111320*cosLat),fit.r,confidence,residual,coverage,points.size)
 }
 private data class P(val x:Double,val y:Double);private data class Circle(val x:Double,val y:Double,val r:Double)
 private fun hull(p:List<P>):List<P>{
  val s=p.distinct().sortedWith(compareBy<P>{it.x}.thenBy{it.y});if(s.size<=2)return s
  fun cross(a:P,b:P,c:P):Double{return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)}
  val lo=mutableListOf<P>();for(x in s){while(lo.size>=2&&cross(lo[lo.size-2],lo.last(),x)<=0)lo.removeAt(lo.lastIndex);lo+=x}
  val hi=mutableListOf<P>();for(x in s.asReversed()){while(hi.size>=2&&cross(hi[hi.size-2],hi.last(),x)<=0)hi.removeAt(hi.lastIndex);hi+=x}
  return lo.dropLast(1)+hi.dropLast(1)
 }
 private fun circumcircle(a:P,b:P,c:P):Circle?{val d=2*(a.x*(b.y-c.y)+b.x*(c.y-a.y)+c.x*(a.y-b.y));if(abs(d)<1e-8)return null;val aa=a.x*a.x+a.y*a.y;val bb=b.x*b.x+b.y*b.y;val cc=c.x*c.x+c.y*c.y;val x=(aa*(b.y-c.y)+bb*(c.y-a.y)+cc*(a.y-b.y))/d;val y=(aa*(c.x-b.x)+bb*(a.x-c.x)+cc*(b.x-a.x))/d;return Circle(x,y,hypot(a.x-x,a.y-y))}
 private fun leastSquares(p:List<P>):Circle?{var sx=0.0;var sy=0.0;var sxx=0.0;var syy=0.0;var sxy=0.0;var sxz=0.0;var syz=0.0;var sz=0.0;p.forEach{val z=it.x*it.x+it.y*it.y;sx+=it.x;sy+=it.y;sxx+=it.x*it.x;syy+=it.y*it.y;sxy+=it.x*it.y;sxz+=it.x*z;syz+=it.y*z;sz+=z};val n=p.size.toDouble();val a=arrayOf(doubleArrayOf(sxx,sxy,sx),doubleArrayOf(sxy,syy,sy),doubleArrayOf(sx,sy,n));val b=doubleArrayOf(-sxz,-syz,-sz);for(i in 0..2){val pivot=(i..2).maxBy{abs(a[it][i])};val tr=a[i];a[i]=a[pivot];a[pivot]=tr;val tv=b[i];b[i]=b[pivot];b[pivot]=tv;if(abs(a[i][i])<1e-9)return null;for(j in i+1..2){val q=a[j][i]/a[i][i];for(k in i..2)a[j][k]-=q*a[i][k];b[j]-=q*b[i]}};val v=DoubleArray(3);for(i in 2 downTo 0){v[i]=(b[i]-(i+1..2).sumOf{a[i][it]*v[it]})/a[i][i]};val x=-v[0]/2;val y=-v[1]/2;return Circle(x,y,sqrt(max(0.0,x*x+y*y-v[2])))}
}
