package com.yokuli.anchorwatch.data.condition

import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import com.yokuli.anchorwatch.data.nmea.NmeaSourceInvalidation
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.condition.TrueWindDirectionSource
import com.yokuli.anchorwatch.domain.condition.WindSpeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class TimedWindValue(val value:Double,val receivedElapsedRealtime:Long)
data class LiveWindState(
    val trueSpeed:TimedWindValue?=null,
    val apparentSpeed:TimedWindValue?=null,
    val trueDirection:TimedWindValue?=null,
    val trueDirectionSource:TrueWindDirectionSource?=null,
    val apparentAngle:TimedWindValue?=null,
    val trueAngle:TimedWindValue?=null,
){
    fun speed(now:Long,allowApparentFallback:Boolean):Pair<TimedWindValue,WindSpeedSource>?=
        trueSpeed?.takeIf{now-it.receivedElapsedRealtime in 0..5_000}?.let{it to WindSpeedSource.TRUE}
            ?:apparentSpeed?.takeIf{allowApparentFallback&&now-it.receivedElapsedRealtime in 0..5_000}?.let{it to WindSpeedSource.APPARENT}
    fun direction(now:Long)=trueDirection?.takeIf{now-it.receivedElapsedRealtime in 0..5_000}?.let{it to trueDirectionSource}
}

/** Wind sentences are published independently of GPS fixes. */
@Singleton
class LiveWindRepository @Inject constructor(){
    private val _state=MutableStateFlow(LiveWindState());val state=_state.asStateFlow()
    private var physicalHeading:TimedWindValue?=null
    private var mwdDirection:TimedWindValue?=null
    @Synchronized fun accept(update:NmeaUpdate,elapsed:Long){
        var value=_state.value
        fun numeric(metric:com.yokuli.anchorwatch.data.nmea.NmeaMetric)=update.metricTimings.isEmpty()||update.isNumeric(metric)
        fun measured(metric:com.yokuli.anchorwatch.data.nmea.NmeaMetric)=update.measuredAt(metric)?:elapsed
        update.trueHeading?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_HEADING)}?.let{physicalHeading=TimedWindValue(normalize(it),measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_HEADING))}
        update.trueWindSpeedKnots?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_SPEED)}?.let{value=value.copy(trueSpeed=TimedWindValue(it,measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_SPEED)))}
        update.apparentWindSpeedKnots?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.APPARENT_WIND_SPEED)}?.let{value=value.copy(apparentSpeed=TimedWindValue(it,measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.APPARENT_WIND_SPEED)))}
        update.apparentWindAngle?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.APPARENT_WIND_ANGLE)}?.let{value=value.copy(apparentAngle=TimedWindValue(it,measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.APPARENT_WIND_ANGLE)))}
        update.trueWindAngle?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_ANGLE)}?.let{angle->value=value.copy(trueAngle=TimedWindValue(angle,measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_ANGLE)))}
        update.trueWindDirection?.takeIf{numeric(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_DIRECTION)}?.let{direction->mwdDirection=TimedWindValue(normalize(direction),measured(com.yokuli.anchorwatch.data.nmea.NmeaMetric.TRUE_WIND_DIRECTION))}
        // HDT and MWV-T are coherent in either arrival order. Apparent angle is
        // deliberately excluded; this is not a home-grown apparent→true solve.
        val heading=physicalHeading;val angle=value.trueAngle
        if(heading!=null&&angle!=null&&kotlin.math.abs(heading.receivedElapsedRealtime-angle.receivedElapsedRealtime)<=2_000){
            val at=maxOf(heading.receivedElapsedRealtime,angle.receivedElapsedRealtime)
            value=value.copy(trueDirection=TimedWindValue(normalize(heading.value+angle.value),at),trueDirectionSource=TrueWindDirectionSource.MWV_TRUE_PLUS_HDT)
        }
        // A fresh MWD is an absolute true direction and always wins over a
        // coherent derived pair, including when sentences arrive separately.
        mwdDirection?.takeIf{elapsed-it.receivedElapsedRealtime<=5_000}?.let{direction->value=value.copy(trueDirection=direction,trueDirectionSource=TrueWindDirectionSource.MWD)}
        _state.value=value
    }
    fun clear(){physicalHeading=null;mwdDirection=null;_state.value=LiveWindState()}
    @Synchronized fun invalidate(event:NmeaSourceInvalidation){
        var value=_state.value
        if(VesselMetricId.TRUE_WIND_SPEED in event.affectedMetrics)value=value.copy(trueSpeed=null)
        if(VesselMetricId.APPARENT_WIND_SPEED in event.affectedMetrics)value=value.copy(apparentSpeed=null)
        if(VesselMetricId.TRUE_WIND_DIRECTION in event.affectedMetrics){mwdDirection=null;value=value.copy(trueDirection=null,trueDirectionSource=null)}
        if(VesselMetricId.APPARENT_WIND_ANGLE in event.affectedMetrics)value=value.copy(apparentAngle=null)
        if(VesselMetricId.TRUE_WIND_ANGLE in event.affectedMetrics)value=value.copy(trueAngle=null,trueDirection=value.trueDirection.takeIf{value.trueDirectionSource!=TrueWindDirectionSource.MWV_TRUE_PLUS_HDT},trueDirectionSource=value.trueDirectionSource.takeIf{it!=TrueWindDirectionSource.MWV_TRUE_PLUS_HDT})
        _state.value=value
    }
    private fun normalize(value:Double)=(value%360.0+360.0)%360.0
}
