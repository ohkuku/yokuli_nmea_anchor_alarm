package com.yokuli.anchorwatch.data.condition

import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
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
        update.trueHeading?.let{physicalHeading=TimedWindValue(normalize(it),elapsed)}
        update.trueWindSpeedKnots?.let{value=value.copy(trueSpeed=TimedWindValue(it,elapsed))}
        update.apparentWindSpeedKnots?.let{value=value.copy(apparentSpeed=TimedWindValue(it,elapsed))}
        update.apparentWindAngle?.let{value=value.copy(apparentAngle=TimedWindValue(it,elapsed))}
        update.trueWindAngle?.let{angle->value=value.copy(trueAngle=TimedWindValue(angle,elapsed))}
        update.trueWindDirection?.let{direction->mwdDirection=TimedWindValue(normalize(direction),elapsed)}
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
    private fun normalize(value:Double)=(value%360.0+360.0)%360.0
}
