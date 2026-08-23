package com.yokuli.anchorwatch.domain.report

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic bounded-memory statistics for long 1–2 Hz sessions.
 * Mean/RMS/min/max are exact; quantiles use a fixed-size reservoir and are
 * explicitly presentation analytics rather than safety inputs.
 */
internal class StreamingStatistics(
    private val reservoirCapacity:Int=8_192,
    seed:Int=0x59A11,
){
    private val reservoir=DoubleArray(reservoirCapacity.coerceAtLeast(1))
    private val random=Random(seed)
    var count:Long=0;private set
    private var sum=0.0
    private var sumSquares=0.0
    var minimum:Double?=null;private set
    var maximum:Double?=null;private set

    fun add(value:Double){
        if(!value.isFinite())return
        count++
        sum+=value;sumSquares+=value*value
        minimum=minimum?.let{minOf(it,value)}?:value
        maximum=maximum?.let{maxOf(it,value)}?:value
        if(count<=reservoir.size)reservoir[count.toInt()-1]=value
        else{
            val selected=random.nextLong(count)
            if(selected<reservoir.size)reservoir[selected.toInt()]=value
        }
    }

    fun mean():Double?=sum.takeIf{count>0}?.div(count)
    fun rms():Double?=sumSquares.takeIf{count>0}?.div(count)?.let(::sqrt)
    fun quantile(probability:Double):Double?{
        val size=minOf(count,reservoir.size.toLong()).toInt()
        if(size==0)return null
        val sorted=reservoir.copyOf(size).apply{sort()}
        val position=probability.coerceIn(0.0,1.0)*(size-1)
        val lower=floor(position).toInt();val upper=ceil(position).toInt()
        if(lower==upper)return sorted[lower]
        return sorted[lower]+(sorted[upper]-sorted[lower])*(position-lower)
    }
}

internal class StreamingCircularMean {
    private var sine=0.0;private var cosine=0.0
    var count:Long=0;private set
    fun add(degrees:Double){if(!degrees.isFinite())return;val radians=Math.toRadians(degrees);sine+=sin(radians);cosine+=cos(radians);count++}
    fun degrees():Double?=if(count==0L)null else(Math.toDegrees(kotlin.math.atan2(sine,cosine))+360.0)%360.0
}
