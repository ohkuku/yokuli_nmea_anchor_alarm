package com.yokuli.anchorwatch.data.vessel

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.WatchWorkspaceMode
import com.yokuli.anchorwatch.domain.vessel.InstrumentLayoutPolicy
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputDestination
import com.yokuli.anchorwatch.domain.vessel.NmeaDestinationTransport
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputPurpose
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

private val Context.vesselSettingsStore by preferencesDataStore("vessel_data_settings")
private val Context.outputSettingsStore by preferencesDataStore("nmea_output_settings")

data class VesselDataSettings(
    val positionPreference:VesselSourcePreference=VesselSourcePreference.AUTO,
    val headingPreference:VesselSourcePreference=VesselSourcePreference.AUTO,
    val watchWorkspace:WatchWorkspaceMode=WatchWorkspaceMode.ANCHOR,
    val draftMeters:Double?=null,
    val navLayout:List<InstrumentTileId> = InstrumentLayoutPolicy.defaults(TripInstrumentPreset.NAV),
    val sailingLayout:List<InstrumentTileId> = InstrumentLayoutPolicy.defaults(TripInstrumentPreset.SAILING),
    val motionLayout:List<InstrumentTileId> = InstrumentLayoutPolicy.defaults(TripInstrumentPreset.MOTION),
    val weatherLayout:List<InstrumentTileId> = InstrumentLayoutPolicy.defaults(TripInstrumentPreset.WEATHER),
    val customLayout:List<InstrumentTileId> = emptyList(),
    val customNmeaFieldIds:List<String> = emptyList(),
    /** null means automatic arbitration; otherwise talker + sentence, e.g. IIHDT. */
    val boatHeadingSourceId:String?=null,
    val pinnedPositionSourceId:String?=null,
    val allowPinnedFallback:Boolean=false,
)

fun VesselDataSettings.layout(preset:TripInstrumentPreset)=when(preset){TripInstrumentPreset.NAV->navLayout;TripInstrumentPreset.SAILING->sailingLayout;TripInstrumentPreset.MOTION->motionLayout;TripInstrumentPreset.WEATHER->weatherLayout;TripInstrumentPreset.CUSTOM->customLayout}
fun VesselDataSettings.withLayout(preset:TripInstrumentPreset,value:List<InstrumentTileId>)=when(preset){
    TripInstrumentPreset.NAV->copy(navLayout=InstrumentLayoutPolicy.normalized(preset,value))
    TripInstrumentPreset.SAILING->copy(sailingLayout=InstrumentLayoutPolicy.normalized(preset,value))
    TripInstrumentPreset.MOTION->copy(motionLayout=InstrumentLayoutPolicy.normalized(preset,value))
    TripInstrumentPreset.WEATHER->copy(weatherLayout=InstrumentLayoutPolicy.normalized(preset,value))
    TripInstrumentPreset.CUSTOM->copy(customLayout=InstrumentLayoutPolicy.normalized(preset,value))
}

data class NmeaDeviceOutputSettings(
    val phonePositionEnabled:Boolean=false,
    val phoneHeadingEnabled:Boolean=false,
    val phoneMotionEnabled:Boolean=false,
    val phonePressureEnabled:Boolean=false,
    val proprietaryStatusEnabled:Boolean=false,
    val transportMode:NmeaOutputTransportMode=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,
    val outputHost:String="",
    val outputPort:Int=10110,
    val phoneHeadingFormat:PhoneHeadingOutputFormat=PhoneHeadingOutputFormat.HDT_TRUE,
    /** False until the user explicitly chooses same-socket or dedicated TX. */
    val transportConfigured:Boolean=false,
    val positionPolicy:PublicationPolicy=if(phonePositionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    val headingPolicy:PublicationPolicy=if(phoneHeadingEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    val motionPolicy:PublicationPolicy=if(phoneMotionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    val pressurePolicy:PublicationPolicy=if(phonePressureEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
    val derivedWindPolicy:PublicationPolicy=PublicationPolicy.OFF,
    val destinations:List<NmeaOutputDestination> = emptyList(),
    /**
     * Master publication lease. Stream selections and the destination are only
     * a saved configuration; no socket may be opened until the user explicitly
     * starts output. This prevents a restored Service from silently resuming TX.
     */
    val publicationEnabled:Boolean=false,
    val purpose:NmeaOutputPurpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
    val autoStartOutput:Boolean=false,
)
enum class NmeaOutputTransportMode { SAME_AS_INPUT_CONNECTION, DEDICATED_TCP, UDP_UNICAST, UDP_BROADCAST }
enum class PhoneHeadingOutputFormat { HDT_TRUE, HDG_MAGNETIC, HDT_AND_HDG }
val NmeaDeviceOutputSettings.effectivePositionPolicy get()=positionPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phonePositionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectiveHeadingPolicy get()=headingPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phoneHeadingEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectiveMotionPolicy get()=motionPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phoneMotionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectivePressurePolicy get()=pressurePolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phonePressureEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.anyStreamSelected:Boolean get()=purpose==NmeaOutputPurpose.CANONICAL_CLIENT_FEED||effectivePositionPolicy!=PublicationPolicy.OFF||effectiveHeadingPolicy!=PublicationPolicy.OFF||effectiveMotionPolicy!=PublicationPolicy.OFF||effectivePressurePolicy!=PublicationPolicy.OFF||derivedWindPolicy!=PublicationPolicy.OFF||proprietaryStatusEnabled
val NmeaDeviceOutputSettings.anyEnabled:Boolean get()=publicationEnabled&&transportConfigured&&anyStreamSelected
val NmeaDeviceOutputSettings.phonePositionPublishing:Boolean get()=purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION&&publicationEnabled&&transportConfigured&&effectivePositionPolicy!=PublicationPolicy.OFF
object NmeaOutputLeasePolicy{
    fun shouldAutoStart(value:NmeaDeviceOutputSettings)=value.autoStartOutput&&value.transportConfigured&&value.anyStreamSelected
    fun afterRestore(value:NmeaDeviceOutputSettings)=value.copy(publicationEnabled=false,autoStartOutput=false)
}
fun NmeaDeviceOutputSettings.withPolicy(family:String,policy:PublicationPolicy)=when(family){
    "position"->copy(phonePositionEnabled=policy!=PublicationPolicy.OFF,positionPolicy=policy)
    "heading"->copy(phoneHeadingEnabled=policy!=PublicationPolicy.OFF,headingPolicy=policy)
    "motion"->copy(phoneMotionEnabled=policy!=PublicationPolicy.OFF,motionPolicy=policy)
    "pressure"->copy(phonePressureEnabled=policy!=PublicationPolicy.OFF,pressurePolicy=policy)
    "wind"->copy(derivedWindPolicy=policy)
    else->this
}

@Singleton
class VesselSettingsRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val position=stringPreferencesKey("position_preference");val heading=stringPreferencesKey("heading_preference");val workspace=stringPreferencesKey("watch_workspace");val draft=doublePreferencesKey("vessel_draft_m");val nav=stringPreferencesKey("instrument_layout_nav");val sailing=stringPreferencesKey("instrument_layout_sailing");val motion=stringPreferencesKey("instrument_layout_motion");val weather=stringPreferencesKey("instrument_layout_weather");val custom=stringPreferencesKey("instrument_layout_custom");val customFields=stringPreferencesKey("instrument_custom_nmea_fields");val boatHeadingSource=stringPreferencesKey("boat_heading_source_id");val pinnedPositionSource=stringPreferencesKey("pinned_position_source_id");val allowPinnedFallback=booleanPreferencesKey("allow_pinned_source_fallback")}
    private fun decode(value:String?,preset:TripInstrumentPreset):List<InstrumentTileId>{
        if(value==null)return InstrumentLayoutPolicy.defaults(preset)
        return InstrumentLayoutPolicy.normalized(preset,value.split(',').mapNotNull{runCatching{InstrumentTileId.valueOf(it)}.getOrNull()})
    }
    private fun encode(value:List<InstrumentTileId>?,preset:TripInstrumentPreset)=(value?.let{InstrumentLayoutPolicy.normalized(preset,it)}?:InstrumentLayoutPolicy.defaults(preset)).joinToString(","){it.name}
    val settings=context.vesselSettingsStore.data.map{p->VesselDataSettings(
        p[K.position]?.let{runCatching{VesselSourcePreference.valueOf(it)}.getOrNull()}?:VesselSourcePreference.AUTO,
        p[K.heading]?.let{runCatching{VesselSourcePreference.valueOf(it)}.getOrNull()}?:VesselSourcePreference.AUTO,
        p[K.workspace]?.let{runCatching{WatchWorkspaceMode.valueOf(it)}.getOrNull()}?:WatchWorkspaceMode.ANCHOR,
        p[K.draft]?.takeIf{it>0},
        decode(p[K.nav],TripInstrumentPreset.NAV),decode(p[K.sailing],TripInstrumentPreset.SAILING),decode(p[K.motion],TripInstrumentPreset.MOTION),decode(p[K.weather],TripInstrumentPreset.WEATHER),
        decode(p[K.custom],TripInstrumentPreset.CUSTOM),p[K.customFields]?.split('|')?.filter{it.isNotBlank()}?.distinct()?.take(24)?:emptyList(),p[K.boatHeadingSource]?.takeIf{it.isNotBlank()},p[K.pinnedPositionSource]?.takeIf{it.isNotBlank()},p[K.allowPinnedFallback]?:false,
    )}
    suspend fun save(value:VesselDataSettings)=context.vesselSettingsStore.edit{p->p[K.position]=value.positionPreference.name;p[K.heading]=value.headingPreference.name;p[K.workspace]=value.watchWorkspace.name;if(value.draftMeters==null)p.remove(K.draft)else p[K.draft]=value.draftMeters.coerceAtLeast(0.0);p[K.nav]=encode(value.navLayout,TripInstrumentPreset.NAV);p[K.sailing]=encode(value.sailingLayout,TripInstrumentPreset.SAILING);p[K.motion]=encode(value.motionLayout,TripInstrumentPreset.MOTION);p[K.weather]=encode(value.weatherLayout,TripInstrumentPreset.WEATHER);p[K.custom]=encode(value.customLayout,TripInstrumentPreset.CUSTOM);p[K.customFields]=value.customNmeaFieldIds.distinct().take(24).joinToString("|");if(value.boatHeadingSourceId.isNullOrBlank())p.remove(K.boatHeadingSource)else p[K.boatHeadingSource]=value.boatHeadingSourceId;if(value.pinnedPositionSourceId.isNullOrBlank())p.remove(K.pinnedPositionSource)else p[K.pinnedPositionSource]=value.pinnedPositionSourceId;p[K.allowPinnedFallback]=value.allowPinnedFallback}
}

@Singleton
class OutputSettingsRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val purpose=stringPreferencesKey("output_purpose");val autoStart=booleanPreferencesKey("output_auto_start");val phonePosition=booleanPreferencesKey("phone_position_enabled");val phoneHeading=booleanPreferencesKey("phone_heading_enabled");val phoneMotion=booleanPreferencesKey("phone_motion_enabled");val phonePressure=booleanPreferencesKey("phone_pressure_enabled");val proprietary=booleanPreferencesKey("phone_proprietary_enabled");val mode=stringPreferencesKey("transport_mode");val host=stringPreferencesKey("output_host");val port=intPreferencesKey("output_port");val headingFormat=stringPreferencesKey("phone_heading_format");val transportConfigured=booleanPreferencesKey("transport_configured");val publicationEnabled=booleanPreferencesKey("publication_enabled");val positionPolicy=stringPreferencesKey("publication_position_policy");val headingPolicy=stringPreferencesKey("publication_heading_policy");val motionPolicy=stringPreferencesKey("publication_motion_policy");val pressurePolicy=stringPreferencesKey("publication_pressure_policy");val windPolicy=stringPreferencesKey("publication_derived_wind_policy");val destinations=stringPreferencesKey("output_destinations_json")}
    private val gson=Gson();private val destinationType=object:TypeToken<List<NmeaOutputDestination>>(){}.type
    private fun policy(stored:String?,legacy:Boolean?,newDefault:PublicationPolicy)=stored?.let{runCatching{PublicationPolicy.valueOf(it)}.getOrNull()}?:legacy?.let{if(it)PublicationPolicy.ALWAYS else PublicationPolicy.OFF}?:newDefault
    private val outputRunning=MutableStateFlow(false)
    private val persistedSettings=context.outputSettingsStore.data.map{p->
        // A first-run setup explicitly skips NMEA Output. Existing saved
        // policies are preserved, but a fresh install never publishes merely
        // because a destination is configured later.
        val position=policy(p[K.positionPolicy],p[K.phonePosition],PublicationPolicy.OFF);val heading=policy(p[K.headingPolicy],p[K.phoneHeading],PublicationPolicy.OFF);val motion=policy(p[K.motionPolicy],p[K.phoneMotion],PublicationPolicy.OFF);val pressure=policy(p[K.pressurePolicy],p[K.phonePressure],PublicationPolicy.OFF);val wind=policy(p[K.windPolicy],null,PublicationPolicy.OFF)
        val mode=p[K.mode]?.let{runCatching{NmeaOutputTransportMode.valueOf(it)}.getOrNull()}?:NmeaOutputTransportMode.DEDICATED_TCP;val host=p[K.host].orEmpty();val port=p[K.port]?:10110;val configured=p[K.transportConfigured]?:p.contains(K.mode);val autoStart=p[K.autoStart]?:false
        val restoredDestinations=p[K.destinations]?.let{json->runCatching{gson.fromJson<List<NmeaOutputDestination>>(json,destinationType)}.getOrNull()}?.filter{it.id.isNotBlank()&&it.port in 1..65535}?.map{it.copy(enabled=false)}.orEmpty()
        val migratedDestination=NmeaOutputDestination(transport=mode.destinationTransport(),host=host,port=port,enabled=false)
        NmeaDeviceOutputSettings(purpose=p[K.purpose]?.let{runCatching{NmeaOutputPurpose.valueOf(it)}.getOrNull()}?:NmeaOutputPurpose.BOAT_BUS_INJECTION,phonePositionEnabled=position!=PublicationPolicy.OFF,phoneHeadingEnabled=heading!=PublicationPolicy.OFF,phoneMotionEnabled=motion!=PublicationPolicy.OFF,phonePressureEnabled=pressure!=PublicationPolicy.OFF,proprietaryStatusEnabled=p[K.proprietary]?:false,transportMode=mode,outputHost=host,outputPort=port,phoneHeadingFormat=p[K.headingFormat]?.let{runCatching{PhoneHeadingOutputFormat.valueOf(it)}.getOrNull()}?:PhoneHeadingOutputFormat.HDT_TRUE,transportConfigured=configured,positionPolicy=position,headingPolicy=heading,motionPolicy=motion,pressurePolicy=pressure,derivedWindPolicy=wind,destinations=if(restoredDestinations.isNotEmpty())restoredDestinations else if(configured)listOf(migratedDestination)else emptyList(),publicationEnabled=false,autoStartOutput=autoStart)
    }
    val settings=combine(persistedSettings,outputRunning){persisted,running->persisted.copy(publicationEnabled=running)}
    suspend fun activateAutoStart(){outputRunning.value=NmeaOutputLeasePolicy.shouldAutoStart(persistedSettings.first())}
    suspend fun save(value:NmeaDeviceOutputSettings){
        outputRunning.value=value.publicationEnabled
        context.outputSettingsStore.edit{preferences->
            preferences[K.purpose]=value.purpose.name;preferences[K.autoStart]=value.autoStartOutput;preferences.remove(K.publicationEnabled)
            preferences[K.phonePosition]=value.effectivePositionPolicy!=PublicationPolicy.OFF;preferences[K.phoneHeading]=value.effectiveHeadingPolicy!=PublicationPolicy.OFF;preferences[K.phoneMotion]=value.effectiveMotionPolicy!=PublicationPolicy.OFF;preferences[K.phonePressure]=value.effectivePressurePolicy!=PublicationPolicy.OFF
            preferences[K.positionPolicy]=value.effectivePositionPolicy.name;preferences[K.headingPolicy]=value.effectiveHeadingPolicy.name;preferences[K.motionPolicy]=value.effectiveMotionPolicy.name;preferences[K.pressurePolicy]=value.effectivePressurePolicy.name;preferences[K.windPolicy]=value.derivedWindPolicy.name
            preferences[K.proprietary]=value.proprietaryStatusEnabled;preferences[K.mode]=value.transportMode.name;preferences[K.host]=value.outputHost.trim();preferences[K.port]=value.outputPort.coerceIn(1,65535);preferences[K.headingFormat]=value.phoneHeadingFormat.name;preferences[K.transportConfigured]=value.transportConfigured
            val primary=(value.destinations.firstOrNull{destination->destination.id=="boat-gateway"}?:NmeaOutputDestination()).copy(transport=value.transportMode.destinationTransport(),host=value.outputHost.trim(),port=value.outputPort.coerceIn(1,65535),enabled=false)
            preferences[K.destinations]=gson.toJson(listOf(primary)+value.destinations.filterNot{destination->destination.id=="boat-gateway"}.map{it.copy(enabled=false)}.take(7))
        }
    }
    private fun NmeaOutputTransportMode.destinationTransport()=when(this){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaDestinationTransport.SAME_AS_INPUT_TCP_SOCKET;NmeaOutputTransportMode.DEDICATED_TCP->NmeaDestinationTransport.DEDICATED_TCP;NmeaOutputTransportMode.UDP_UNICAST->NmeaDestinationTransport.UDP_UNICAST;NmeaOutputTransportMode.UDP_BROADCAST->NmeaDestinationTransport.UDP_BROADCAST}
}
