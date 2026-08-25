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
    val includePressure:Boolean=true,
    val includeDerivedWind:Boolean=true,
)
enum class NmeaOutputTransportMode { SAME_AS_INPUT_CONNECTION, DEDICATED_TCP, TCP_SERVER, UDP_UNICAST, UDP_BROADCAST }
enum class PhoneHeadingOutputFormat { HDT_TRUE, HDG_MAGNETIC, HDT_AND_HDG }
val NmeaDeviceOutputSettings.effectivePositionPolicy get()=positionPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phonePositionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectiveHeadingPolicy get()=headingPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phoneHeadingEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectiveMotionPolicy get()=motionPolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phoneMotionEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.effectivePressurePolicy get()=pressurePolicy.takeIf{it!=PublicationPolicy.OFF}?:if(phonePressureEnabled)PublicationPolicy.ALWAYS else PublicationPolicy.OFF
val NmeaDeviceOutputSettings.anyStreamSelected:Boolean get()=effectivePositionPolicy!=PublicationPolicy.OFF||effectiveHeadingPolicy!=PublicationPolicy.OFF||effectiveMotionPolicy!=PublicationPolicy.OFF||effectivePressurePolicy!=PublicationPolicy.OFF||derivedWindPolicy!=PublicationPolicy.OFF||proprietaryStatusEnabled
val NmeaDeviceOutputSettings.anyEnabled:Boolean get()=publicationEnabled&&transportConfigured&&anyStreamSelected
object NmeaOutputLeasePolicy{
    /** Output owns a socket and can displace the only reader accepted by a
     * small marine gateway. It therefore always requires an explicit Start in
     * the current foreground session; a persisted legacy auto-start flag is
     * intentionally ignored. */
    fun shouldAutoStart(@Suppress("UNUSED_PARAMETER") value:NmeaDeviceOutputSettings)=false
    fun afterRestore(value:NmeaDeviceOutputSettings)=value.copy(publicationEnabled=false,autoStartOutput=false)
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
    private object K{val purpose=stringPreferencesKey("output_purpose");val autoStart=booleanPreferencesKey("output_auto_start");val phonePosition=booleanPreferencesKey("phone_position_enabled");val phoneHeading=booleanPreferencesKey("phone_heading_enabled");val phoneMotion=booleanPreferencesKey("phone_motion_enabled");val phonePressure=booleanPreferencesKey("phone_pressure_enabled");val proprietary=booleanPreferencesKey("phone_proprietary_enabled");val mode=stringPreferencesKey("transport_mode");val host=stringPreferencesKey("output_host");val port=intPreferencesKey("output_port");val headingFormat=stringPreferencesKey("phone_heading_format");val transportConfigured=booleanPreferencesKey("transport_configured");val publicationEnabled=booleanPreferencesKey("publication_enabled");val positionPolicy=stringPreferencesKey("publication_position_policy");val headingPolicy=stringPreferencesKey("publication_heading_policy");val motionPolicy=stringPreferencesKey("publication_motion_policy");val pressurePolicy=stringPreferencesKey("publication_pressure_policy");val windPolicy=stringPreferencesKey("publication_derived_wind_policy");val destinations=stringPreferencesKey("output_destinations_json");val includePressure=booleanPreferencesKey("canonical_feed_include_pressure");val includeDerivedWind=booleanPreferencesKey("canonical_feed_include_derived_wind")}
    private val gson=Gson();private val destinationType=object:TypeToken<List<NmeaOutputDestination>>(){}.type
    private val outputRunning=MutableStateFlow(false)
    private val persistedSettings=context.outputSettingsStore.data.map{p->
        // A first-run setup explicitly skips NMEA Output. Existing saved
        // policies are preserved, but a fresh install never publishes merely
        // because a destination is configured later.
        // Fresh installs use the already-owned full-duplex input socket. A
        // stored independent TCP/UDP/server choice remains untouched, but an
        // absent/corrupt legacy value must never silently create a second Boat
        // connection as the apparent default.
        val mode=NmeaOutputTransportDefaults.restore(p[K.mode]);val host=p[K.host].orEmpty();val port=p[K.port]?:10110;val configured=p[K.transportConfigured]?:p.contains(K.mode)
        val restoredDestinations=p[K.destinations]?.let{json->runCatching{gson.fromJson<List<NmeaOutputDestination>>(json,destinationType)}.getOrNull()}?.filter{it.id.isNotBlank()&&it.port in 1..65535}?.map{it.copy(enabled=false)}.orEmpty()
        val migratedDestination=NmeaOutputDestination(transport=mode.destinationTransport(),host=host,port=port,enabled=false)
        val includePressure=p[K.includePressure]?:true;val includeWind=p[K.includeDerivedWind]?:true
        // BACKUP and CANONICAL_CLIENT_FEED were legacy publisher-ownership
        // modes. Every transport now has strict Phone/App-owned semantics:
        // external Boat sources are input/diagnostics only and are never
        // republished merely because an independent endpoint was selected.
        NmeaDeviceOutputSettings(
            purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
            phonePositionEnabled=true,phoneHeadingEnabled=true,phoneMotionEnabled=true,phonePressureEnabled=includePressure,
            transportMode=mode,outputHost=host,outputPort=port,
            phoneHeadingFormat=p[K.headingFormat]?.let{runCatching{PhoneHeadingOutputFormat.valueOf(it)}.getOrNull()}?:PhoneHeadingOutputFormat.HDT_TRUE,
            transportConfigured=configured,
            positionPolicy=PublicationPolicy.ALWAYS,
            headingPolicy=PublicationPolicy.ALWAYS,
            motionPolicy=PublicationPolicy.ALWAYS,
            pressurePolicy=if(includePressure)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
            derivedWindPolicy=if(includeWind)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
            destinations=if(restoredDestinations.isNotEmpty())restoredDestinations else if(configured)listOf(migratedDestination)else emptyList(),
            publicationEnabled=false,autoStartOutput=false,includePressure=includePressure,includeDerivedWind=includeWind,
        )
    }
    val settings=combine(persistedSettings,outputRunning){persisted,running->persisted.copy(publicationEnabled=running)}
    suspend fun activateAutoStart(){outputRunning.value=NmeaOutputLeasePolicy.shouldAutoStart(persistedSettings.first())}
    private fun canonical(value:NmeaDeviceOutputSettings)=value.copy(
            purpose=NmeaOutputPurpose.BOAT_BUS_INJECTION,
            phonePositionEnabled=true,phoneHeadingEnabled=true,phoneMotionEnabled=true,phonePressureEnabled=value.includePressure,
            proprietaryStatusEnabled=false,
            positionPolicy=PublicationPolicy.ALWAYS,
            headingPolicy=PublicationPolicy.ALWAYS,
            motionPolicy=PublicationPolicy.ALWAYS,
            pressurePolicy=if(value.includePressure)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
            derivedWindPolicy=if(value.includeDerivedWind)PublicationPolicy.ALWAYS else PublicationPolicy.OFF,
            autoStartOutput=false,
        )

    /** Configuration persistence never mutates the live boat-network lease. */
    suspend fun saveConfiguration(value:NmeaDeviceOutputSettings)=persist(canonical(value))
    fun requestStart(){outputRunning.value=true}
    fun requestStop(){outputRunning.value=false}

    /** Compatibility entry point for restore/tests. Product UI uses the
     * explicit configuration and lease methods above. */
    suspend fun save(value:NmeaDeviceOutputSettings){
        val canonical=canonical(value)
        outputRunning.value=canonical.publicationEnabled
        persist(canonical)
    }

    private suspend fun persist(canonical:NmeaDeviceOutputSettings){
        context.outputSettingsStore.edit{preferences->
            preferences[K.purpose]=canonical.purpose.name;preferences[K.autoStart]=false;preferences.remove(K.publicationEnabled)
            preferences[K.phonePosition]=canonical.phonePositionEnabled;preferences[K.phoneHeading]=canonical.phoneHeadingEnabled;preferences[K.phoneMotion]=canonical.phoneMotionEnabled;preferences[K.phonePressure]=canonical.phonePressureEnabled
            preferences[K.positionPolicy]=canonical.positionPolicy.name;preferences[K.headingPolicy]=canonical.headingPolicy.name;preferences[K.motionPolicy]=canonical.motionPolicy.name;preferences[K.pressurePolicy]=canonical.pressurePolicy.name;preferences[K.windPolicy]=canonical.derivedWindPolicy.name
            preferences[K.proprietary]=false;preferences[K.mode]=canonical.transportMode.name;preferences[K.host]=canonical.outputHost.trim();preferences[K.port]=canonical.outputPort.coerceIn(1,65535);preferences[K.headingFormat]=canonical.phoneHeadingFormat.name;preferences[K.transportConfigured]=canonical.transportConfigured;preferences[K.includePressure]=canonical.includePressure;preferences[K.includeDerivedWind]=canonical.includeDerivedWind
            val primary=(canonical.destinations.firstOrNull{destination->destination.id=="boat-gateway"}?:NmeaOutputDestination()).copy(transport=canonical.transportMode.destinationTransport(),host=canonical.outputHost.trim(),port=canonical.outputPort.coerceIn(1,65535),enabled=false)
            preferences[K.destinations]=gson.toJson(listOf(primary)+canonical.destinations.filterNot{destination->destination.id=="boat-gateway"}.map{it.copy(enabled=false)}.take(7))
        }
    }
    private fun NmeaOutputTransportMode.destinationTransport()=when(this){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->NmeaDestinationTransport.SAME_AS_INPUT_TCP_SOCKET;NmeaOutputTransportMode.DEDICATED_TCP->NmeaDestinationTransport.DEDICATED_TCP;NmeaOutputTransportMode.TCP_SERVER->NmeaDestinationTransport.TCP_SERVER;NmeaOutputTransportMode.UDP_UNICAST->NmeaDestinationTransport.UDP_UNICAST;NmeaOutputTransportMode.UDP_BROADCAST->NmeaDestinationTransport.UDP_BROADCAST}
}

object NmeaOutputTransportDefaults{
    val AUTHORITATIVE_PHONE_TO_BOAT=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION
    fun restore(stored:String?)=stored?.let{runCatching{NmeaOutputTransportMode.valueOf(it)}.getOrNull()}?:AUTHORITATIVE_PHONE_TO_BOAT
}
