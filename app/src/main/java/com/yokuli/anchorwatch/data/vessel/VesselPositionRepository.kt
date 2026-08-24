package com.yokuli.anchorwatch.data.vessel

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.vessel.*
import com.yokuli.anchorwatch.location.PositionIntegrityFilter
import com.yokuli.anchorwatch.location.PositionIntegrityResult
import com.yokuli.anchorwatch.location.SystemLocationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Presentation/trip gates. They are deliberately separate from Anchor safety. */
@Singleton
class VesselPositionRepository @Inject constructor(navigation:NavigationRepository,systemLocation:SystemLocationRepository){
    private val boatGate=PositionIntegrityFilter();private val phoneGate=PositionIntegrityFilter()
    private val _boat=MutableStateFlow(VesselObservation<VesselPosition>());val boat=_boat.asStateFlow()
    private val _phone=MutableStateFlow(VesselObservation<VesselPosition>());val phone=_phone.asStateFlow()
    private val _acceptedPhoneFix=MutableStateFlow<NavigationFix?>(null);val acceptedPhoneFix=_acceptedPhoneFix.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    init{
        scope.launch{navigation.fix.filterNotNull().collect{ingestBoat(it)}}
        scope.launch{navigation.sourceInvalidations.collect{event->if(VesselMetricId.POSITION in event.affectedMetrics)resetBoatGeneration()}}
        scope.launch{systemLocation.fix.filterNotNull().collect{ingestPhone(it)}}
        scope.launch{navigation.transportDiagnostics.map{it.connectionGeneration}.distinctUntilChanged().drop(1).collect{resetBoatGeneration()}}
    }
    @Synchronized fun ingestBoat(fix:NavigationFix){ingest(fix,VesselDataSource.BOAT_NMEA,boatGate,_boat)}
    @Synchronized fun ingestPhone(fix:NavigationFix){ingest(fix,VesselDataSource.PHONE_GNSS,phoneGate,_phone,_acceptedPhoneFix)}
    @Synchronized private fun resetBoatGeneration(){boatGate.reset();_boat.value=VesselObservation()}
    private fun ingest(fix:NavigationFix,source:VesselDataSource,gate:PositionIntegrityFilter,target:MutableStateFlow<VesselObservation<VesselPosition>>,acceptedTarget:MutableStateFlow<NavigationFix?>?=null){
        val result=gate.evaluate(fix);if(result !is PositionIntegrityResult.Accepted)return
        result.fixes.lastOrNull()?.let{accepted->acceptedTarget?.value=accepted.fix;target.value=VesselObservation(value=VesselPosition(accepted.fix.latitude,accepted.fix.longitude,accepted.fix.altitudeMeters,accepted.fix.horizontalAccuracyMeters,accepted.fix.satellites,accepted.fix.hdop),source=source,observedAtUtcMillis=accepted.fix.timestampUtcMillis,receivedElapsedRealtime=accepted.fix.receivedElapsedRealtime,quality=if(accepted.trust==FixTrust.TRUSTED)VesselDataQuality.GOOD else VesselDataQuality.DEGRADED,freshness=VesselDataFreshness.FRESH,provenance=accepted.reason)}
    }
}
