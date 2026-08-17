package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.tide.TideExtreme
import com.yokuli.anchorwatch.domain.tide.TideInterpolationResult
import com.yokuli.anchorwatch.domain.tide.TideInterpolationQuality
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos

/** LINZ/NZ Nautical Almanac cosine method for heights between consecutive extrema. */
object TideHeightInterpolator{
    const val METHOD="LINZ_COSINE_BETWEEN_EXTREMES"
    fun heightAt(instant:Instant,previousExtreme:TideExtreme,nextExtreme:TideExtreme):TideInterpolationResult?{
        if(instant<previousExtreme.instantUtc||instant>nextExtreme.instantUtc)return null
        val duration=nextExtreme.instantUtc.toEpochMilli()-previousExtreme.instantUtc.toEpochMilli()
        if(duration<=0L)return null
        val intervalMinutes=duration/60_000L
        val fraction=(instant.toEpochMilli()-previousExtreme.instantUtc.toEpochMilli()).toDouble()/duration
        val angle=PI*(fraction+1.0)
        val height=previousExtreme.heightMetersAboveChartDatum+(nextExtreme.heightMetersAboveChartDatum-previousExtreme.heightMetersAboveChartDatum)*((cos(angle)+1.0)/2.0)
        return TideInterpolationResult(height,METHOD,previousExtreme,nextExtreme,intervalMinutes,if(intervalMinutes in 300L..420L)TideInterpolationQuality.RECOMMENDED_5_TO_7_HOURS else TideInterpolationQuality.OUTSIDE_RECOMMENDED_INTERVAL)
    }
}
