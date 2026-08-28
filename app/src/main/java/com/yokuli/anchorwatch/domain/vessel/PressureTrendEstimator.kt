package com.yokuli.anchorwatch.domain.vessel

data class PressureTrend(val changeHpa:Double,val spanMillis:Long,val coverage:Double)

/** Bounded, down-sampled pressure history used only for display/report observations. */
class PressureTrendEstimator(
    private val retentionMillis:Long=6*60*60_000L,
    private val bucketMillis:Long=60_000L,
){
    private data class Point(val elapsed:Long,val hpa:Double)
    private val points=ArrayDeque<Point>()

    @Synchronized fun add(elapsed:Long,hpa:Double){
        if(!hpa.isFinite()||hpa !in 800.0..1_200.0)return
        val last=points.lastOrNull()
        // A high-rate pressure sensor normally emits every few hundred
        // milliseconds. Comparing only the interval from the previous sample
        // therefore replaced the sole point forever. Bucket by absolute time
        // so crossing a UTC-minute boundary always creates new trend evidence.
        if(last!=null&&elapsed<last.elapsed)return
        if(last!=null&&Math.floorDiv(elapsed,bucketMillis)==Math.floorDiv(last.elapsed,bucketMillis)){
            points.removeLast()
        }
        points.addLast(Point(elapsed,hpa))
        val cutoff=elapsed-retentionMillis
        while(points.firstOrNull()?.elapsed?.let{it<cutoff}==true)points.removeFirst()
    }

    @Synchronized fun trend(nowElapsed:Long,windowMillis:Long):PressureTrend?{
        if(windowMillis<=0)return null
        val window=points.filter{it.elapsed>=nowElapsed-windowMillis&&it.elapsed<=nowElapsed}
        if(window.size<2)return null
        val span=window.last().elapsed-window.first().elapsed
        val expectedBuckets=(windowMillis.toDouble()/bucketMillis).coerceAtLeast(1.0)
        val coverage=(window.map{(it.elapsed-(nowElapsed-windowMillis))/bucketMillis}.distinct().size/expectedBuckets).coerceIn(0.0,1.0)
        if(span<windowMillis*.8||coverage<.70)return null
        val origin=window.first().elapsed.toDouble();val xs=window.map{(it.elapsed-origin)/1000.0};val xMean=xs.average();val yMean=window.map{it.hpa}.average()
        val denominator=xs.sumOf{(it-xMean)*(it-xMean)}
        if(denominator<=0)return null
        val slope=xs.indices.sumOf{index->(xs[index]-xMean)*(window[index].hpa-yMean)}/denominator
        return PressureTrend(slope*(windowMillis/1000.0),span,coverage)
    }
}
