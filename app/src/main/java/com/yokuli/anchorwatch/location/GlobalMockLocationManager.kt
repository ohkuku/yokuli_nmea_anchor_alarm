package com.yokuli.anchorwatch.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.*
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.yokuli.anchorwatch.domain.model.NavigationFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

enum class MockGpsState { INACTIVE, STARTING, ACTIVE, NOT_CONFIGURED, FAILED, STALE }
data class MockGpsStatus(val state:MockGpsState=MockGpsState.INACTIVE,val message:String="Android GPS is using the normal system source.",val fusedActive:Boolean=false,val directGpsActive:Boolean=false,val publishedFixes:Long=0,val lastPublishedElapsed:Long?=null)
interface MockLocationSink{val status:kotlinx.coroutines.flow.StateFlow<MockGpsStatus>;suspend fun start(enhancedCompatibility:Boolean):MockGpsStatus;suspend fun publish(fix:NavigationFix):Result<Unit>;suspend fun stop(message:String="Android GPS restored to the normal system source.");suspend fun stale()}

@Singleton @SuppressLint("MissingPermission","WrongConstant")
class GlobalMockLocationManager @Inject constructor(@ApplicationContext private val context:Context,private val fused:FusedLocationProviderClient):MockLocationSink{
 private val locationManager=context.getSystemService(LocationManager::class.java);private val _status=MutableStateFlow(MockGpsStatus());override val status=_status.asStateFlow();private var directProviderAdded=false
 override suspend fun start(enhancedCompatibility:Boolean):MockGpsStatus{
  if(_status.value.state==MockGpsState.ACTIVE)return _status.value;_status.value=MockGpsStatus(MockGpsState.STARTING,"Checking Android mock-location access…")
  return try{fused.setMockMode(true).await();val direct=enhancedCompatibility&&enableDirectProvider();MockGpsStatus(MockGpsState.ACTIVE,if(direct)"NMEA is feeding Fused Location and GPS_PROVIDER." else "NMEA is feeding Fused Location. Direct GPS compatibility is unavailable.",true,direct).also{_status.value=it}}
  catch(_:SecurityException){resetSystemLocation();MockGpsStatus(MockGpsState.NOT_CONFIGURED,"GPS proxy was not enabled. Turn on Developer Options and select NMEA Anchor Watch as the location override app.").also{_status.value=it}}
  catch(e:Exception){resetSystemLocation();MockGpsStatus(MockGpsState.FAILED,"GPS proxy was not enabled: ${e.message?:e.javaClass.simpleName}. Android GPS remains on its normal source.").also{_status.value=it}}
 }
 private fun enableDirectProvider():Boolean=try{
  runCatching{locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)}
  if(Build.VERSION.SDK_INT>=31){val properties=ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).setHasAltitudeSupport(true).setHasBearingSupport(true).setHasSpeedSupport(true).build();locationManager.addTestProvider(LocationManager.GPS_PROVIDER,properties)}else{@Suppress("DEPRECATION") locationManager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,1,1)}
  directProviderAdded=true;locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);true
 }catch(_:Exception){cleanupProviders();false}
 override suspend fun publish(fix:NavigationFix):Result<Unit> = runCatching{
  check(_status.value.state==MockGpsState.ACTIVE);check(fix.valid)
  val location=Location(LocationManager.GPS_PROVIDER).apply{latitude=fix.latitude;longitude=fix.longitude;accuracy=(fix.hdop?.times(3.0)?.coerceIn(5.0,50.0)?:5.0).toFloat();time=fix.timestampUtcMillis?:System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos();fix.sogKnots?.let{speed=(it*0.514444).toFloat()};fix.cogTrueDegrees?.let{bearing=it.toFloat()};fix.altitudeMeters?.let{altitude=it}}
  fused.setMockLocation(location).await();if(directProviderAdded)locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location);val previous=_status.value;_status.value=previous.copy(publishedFixes=previous.publishedFixes+1,lastPublishedElapsed=SystemClock.elapsedRealtime())
 }.onFailure{error->if(error is SecurityException)_status.value=MockGpsStatus(MockGpsState.NOT_CONFIGURED,"Mock-location access was revoked. Android GPS restored.")else _status.value=_status.value.copy(state=MockGpsState.FAILED,message="Could not publish NMEA position: ${error.message}")}
 override suspend fun stale(){stop("NMEA GPS became stale — Android GPS restored.");_status.value=MockGpsStatus(MockGpsState.STALE,"NMEA GPS became stale — Android GPS restored.")}
 override suspend fun stop(message:String){resetSystemLocation();_status.value=MockGpsStatus(MockGpsState.INACTIVE,message)}
 private suspend fun resetSystemLocation(){runCatching{fused.setMockMode(false).await()};cleanupProviders()}
 private fun cleanupProviders(){if(directProviderAdded)runCatching{locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)};directProviderAdded=false}
}
