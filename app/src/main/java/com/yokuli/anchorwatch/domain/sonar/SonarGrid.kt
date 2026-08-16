package com.yokuli.anchorwatch.domain.sonar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

data class SonarGridSample(val latitude:Double,val longitude:Double,val depthMeters:Double,val horizontalAccuracyMeters:Double?=null,val qualityWeight:Double=1.0)
data class SonarCell(val xIndex:Long,val yIndex:Long,val depthMeters:Double,val uncertaintyMeters:Double,val sampleCount:Int)
data class SonarInspection(val depthMeters:Double,val uncertaintyMeters:Double,val sampleCount:Int,val measured:Boolean)

class SonarGrid private constructor(val cellSizeMeters:Double,val cells:Map<Pair<Long,Long>,SonarCell>){
    private val spatialBuckets by lazy(LazyThreadSafetyMode.PUBLICATION){
        cells.values.groupBy{cell->Math.floorDiv(cell.xIndex,BUCKET_CELL_SPAN) to Math.floorDiv(cell.yIndex,BUCKET_CELL_SPAN)}
    }

    fun cellsInBounds(minX:Double,maxX:Double,minY:Double,maxY:Double):List<SonarCell>{
        val minXi=floor(minX/cellSizeMeters).toLong();val maxXi=floor(maxX/cellSizeMeters).toLong()
        val minYi=floor(minY/cellSizeMeters).toLong();val maxYi=floor(maxY/cellSizeMeters).toLong()
        val result=ArrayList<SonarCell>()
        val minBucketX=Math.floorDiv(minXi,BUCKET_CELL_SPAN);val maxBucketX=Math.floorDiv(maxXi,BUCKET_CELL_SPAN)
        val minBucketY=Math.floorDiv(minYi,BUCKET_CELL_SPAN);val maxBucketY=Math.floorDiv(maxYi,BUCKET_CELL_SPAN)
        for(bucketX in minBucketX..maxBucketX)for(bucketY in minBucketY..maxBucketY){
            spatialBuckets[bucketX to bucketY]?.forEach{cell->if(cell.xIndex in minXi..maxXi&&cell.yIndex in minYi..maxYi)result+=cell}
        }
        return result
    }

    fun inspect(latitude:Double,longitude:Double,maxInterpolationMeters:Double=15.0):SonarInspection?{
        val (x,y)=project(latitude,longitude);return inspectProjected(x,y,maxInterpolationMeters)
    }

    fun inspectProjected(x:Double,y:Double,maxInterpolationMeters:Double=15.0):SonarInspection?{
        val key=floor(x/cellSizeMeters).toLong() to floor(y/cellSizeMeters).toLong()
        cells[key]?.let{return SonarInspection(it.depthMeters,it.uncertaintyMeters,it.sampleCount,true)}
        val range=ceil(maxInterpolationMeters/cellSizeMeters).toInt();val neighbours=mutableListOf<Pair<SonarCell,Double>>()
        for(dx in -range..range)for(dy in -range..range){val cell=cells[(key.first+dx) to (key.second+dy)]?:continue;val cx=(cell.xIndex+.5)*cellSizeMeters;val cy=(cell.yIndex+.5)*cellSizeMeters;val distance=kotlin.math.hypot(cx-x,cy-y);if(distance<=maxInterpolationMeters)neighbours+=cell to distance.coerceAtLeast(1.0)}
        if(neighbours.size<3)return null
        val weights=neighbours.map{1.0/it.second.pow(2)};val total=weights.sum();val depth=neighbours.indices.sumOf{neighbours[it].first.depthMeters*weights[it]}/total
        val spread=sqrt(neighbours.indices.sumOf{(neighbours[it].first.depthMeters-depth).pow(2)*weights[it]}/total)
        val uncertainty=max(spread,neighbours.indices.sumOf{neighbours[it].first.uncertaintyMeters*weights[it]}/total)
        return SonarInspection(depth,uncertainty,neighbours.sumOf{it.first.sampleCount},false)
    }

    companion object{
        const val EARTH_RADIUS=6_378_137.0
        private const val BUCKET_CELL_SPAN=64L
        fun build(samples:List<SonarGridSample>,cellSizeMeters:Double=5.0):SonarGrid{
            val grouped=samples.filter{it.latitude in -85.0..85.0&&it.longitude in -180.0..180.0&&it.depthMeters.isFinite()&&it.depthMeters>0}.groupBy{sample->val p=project(sample.latitude,sample.longitude);floor(p.first/cellSizeMeters).toLong() to floor(p.second/cellSizeMeters).toLong()}
            val cells=grouped.mapValues{(key,values)->
                val depths=values.map{it.depthMeters}.sorted();val median=median(depths);val deviations=depths.map{abs(it-median)}.sorted();val mad=median(deviations);val gate=max(.35,mad*3.5)
                val robust=values.filter{abs(it.depthMeters-median)<=gate}.ifEmpty{values};val weights=robust.map{it.qualityWeight.coerceIn(.05,1.0)/((it.horizontalAccuracyMeters?:5.0).coerceAtLeast(.5).pow(2)+.25)};val total=weights.sum();val depth=robust.indices.sumOf{robust[it].depthMeters*weights[it]}/total
                val spread=sqrt(robust.indices.sumOf{(robust[it].depthMeters-depth).pow(2)*weights[it]}/total);val positionContribution=(robust.mapNotNull{it.horizontalAccuracyMeters}.sorted().let{if(it.isEmpty())5.0 else it[it.size/2]})*.05
                SonarCell(key.first,key.second,depth,max(.10,spread+positionContribution),robust.size)
            }
            return SonarGrid(cellSizeMeters,cells)
        }
        fun project(latitude:Double,longitude:Double):Pair<Double,Double>{val lat=Math.toRadians(latitude.coerceIn(-85.05112878,85.05112878));return EARTH_RADIUS*Math.toRadians(longitude) to EARTH_RADIUS*ln(tan(PI/4+lat/2))}
        fun unproject(x:Double,y:Double):Pair<Double,Double>{val longitude=Math.toDegrees(x/EARTH_RADIUS);val latitude=Math.toDegrees(2*kotlin.math.atan(kotlin.math.exp(y/EARTH_RADIUS))-PI/2);return latitude to longitude}
        private fun median(values:List<Double>)=if(values.isEmpty())0.0 else if(values.size%2==1)values[values.size/2]else(values[values.size/2-1]+values[values.size/2])/2
    }
}
