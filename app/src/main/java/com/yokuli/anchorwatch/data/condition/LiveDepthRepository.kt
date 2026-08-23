package com.yokuli.anchorwatch.data.condition

import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthProvenance
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.sonar.DepthSentenceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LiveDepthState(
    val depthMeters:Double?=null,
    val rawDepthMeters:Double?=null,
    val nmeaOffsetMeters:Double?=null,
    val userOffsetMeters:Double=0.0,
    val reference:DepthReference?=null,
    val sentenceType:DepthSentenceType?=null,
    val receivedElapsedRealtime:Long?=null,
    val isDemo:Boolean=false,
){ fun isFresh(now:Long,maxAgeMillis:Long=3_000)=receivedElapsedRealtime?.let{now-it in 0..maxAgeMillis}==true }

/** Live safety depth exists whether or not sonar survey recording is enabled. */
@Singleton
class LiveDepthRepository @Inject constructor(settings:SettingsRepository){
    private val _state=MutableStateFlow(LiveDepthState());val state=_state.asStateFlow()
    private val lock=Any()
    @Volatile private var userOffset=0.0
    @Volatile private var settingsReady=false
    private var lastObservation:DepthObservation?=null
    private var lastObservationIsDemo=false
    init{CoroutineScope(SupervisorJob()+Dispatchers.Default).launch{settings.settings.collect{value->
        synchronized(lock){
            userOffset=value.sounderOffsetMeters
            settingsReady=true
            lastObservation?.let{publishLocked(it,lastObservationIsDemo)}
        }
    }}}
    fun accept(observation:DepthObservation,isDemo:Boolean=false){
        synchronized(lock){
            lastObservation=observation
            lastObservationIsDemo=isDemo
            // DataStore is asynchronous. Publishing with a made-up zero offset
            // creates a brief but real safety-depth error during cold start.
            if(settingsReady)publishLocked(observation,isDemo)
        }
    }
    fun clear(){synchronized(lock){lastObservation=null;lastObservationIsDemo=false;_state.value=LiveDepthState(userOffsetMeters=userOffset)}}
    private fun publishLocked(observation:DepthObservation,isDemo:Boolean){
        val provenance=DepthProvenance.from(observation,userOffset)
        _state.value=LiveDepthState(
            depthMeters=provenance.finalDepthMeters,
            rawDepthMeters=provenance.rawDepthMeters,
            nmeaOffsetMeters=provenance.nmeaOffsetMeters,
            userOffsetMeters=provenance.userOffsetMeters,
            reference=observation.reference,
            sentenceType=observation.sentenceType,
            receivedElapsedRealtime=observation.receivedElapsedRealtime,
            isDemo=isDemo,
        )
    }
}
