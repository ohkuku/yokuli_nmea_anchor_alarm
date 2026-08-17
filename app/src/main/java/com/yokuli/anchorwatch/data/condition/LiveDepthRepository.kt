package com.yokuli.anchorwatch.data.condition

import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.sonar.DepthObservation
import com.yokuli.anchorwatch.domain.sonar.DepthProvenance
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
    val sentenceType:DepthSentenceType?=null,
    val receivedElapsedRealtime:Long?=null,
    val isDemo:Boolean=false,
){ fun isFresh(now:Long,maxAgeMillis:Long=3_000)=receivedElapsedRealtime?.let{now-it in 0..maxAgeMillis}==true }

/** Live safety depth exists whether or not sonar survey recording is enabled. */
@Singleton
class LiveDepthRepository @Inject constructor(settings:SettingsRepository){
    private val _state=MutableStateFlow(LiveDepthState());val state=_state.asStateFlow()
    @Volatile private var userOffset=0.0
    init{CoroutineScope(SupervisorJob()+Dispatchers.Default).launch{settings.settings.collect{userOffset=it.sounderOffsetMeters}}}
    fun accept(observation:DepthObservation,isDemo:Boolean=false){
        val provenance=DepthProvenance.from(observation,userOffset)
        _state.value=LiveDepthState(provenance.finalDepthMeters,provenance.rawDepthMeters,provenance.nmeaOffsetMeters,provenance.userOffsetMeters,observation.sentenceType,observation.receivedElapsedRealtime,isDemo)
    }
    fun clear(){_state.value=LiveDepthState()}
}

