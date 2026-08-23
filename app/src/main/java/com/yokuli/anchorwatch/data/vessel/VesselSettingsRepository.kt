package com.yokuli.anchorwatch.data.vessel

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.WatchWorkspaceMode
import com.yokuli.anchorwatch.domain.vessel.InstrumentLayoutPolicy
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

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
)
val NmeaDeviceOutputSettings.anyEnabled:Boolean get()=phonePositionEnabled||phoneHeadingEnabled||phoneMotionEnabled||phonePressureEnabled||proprietaryStatusEnabled

@Singleton
class VesselSettingsRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val position=stringPreferencesKey("position_preference");val heading=stringPreferencesKey("heading_preference");val workspace=stringPreferencesKey("watch_workspace");val draft=doublePreferencesKey("vessel_draft_m");val nav=stringPreferencesKey("instrument_layout_nav");val sailing=stringPreferencesKey("instrument_layout_sailing");val motion=stringPreferencesKey("instrument_layout_motion");val weather=stringPreferencesKey("instrument_layout_weather");val custom=stringPreferencesKey("instrument_layout_custom");val customFields=stringPreferencesKey("instrument_custom_nmea_fields")}
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
        decode(p[K.custom],TripInstrumentPreset.CUSTOM),p[K.customFields]?.split('|')?.filter{it.isNotBlank()}?.distinct()?.take(24)?:emptyList(),
    )}
    suspend fun save(value:VesselDataSettings)=context.vesselSettingsStore.edit{p->p[K.position]=value.positionPreference.name;p[K.heading]=value.headingPreference.name;p[K.workspace]=value.watchWorkspace.name;if(value.draftMeters==null)p.remove(K.draft)else p[K.draft]=value.draftMeters.coerceAtLeast(0.0);p[K.nav]=encode(value.navLayout,TripInstrumentPreset.NAV);p[K.sailing]=encode(value.sailingLayout,TripInstrumentPreset.SAILING);p[K.motion]=encode(value.motionLayout,TripInstrumentPreset.MOTION);p[K.weather]=encode(value.weatherLayout,TripInstrumentPreset.WEATHER);p[K.custom]=encode(value.customLayout,TripInstrumentPreset.CUSTOM);p[K.customFields]=value.customNmeaFieldIds.distinct().take(24).joinToString("|")}
}

@Singleton
class OutputSettingsRepository @Inject constructor(@ApplicationContext private val context:Context){
    private object K{val phonePosition=booleanPreferencesKey("phone_position_enabled");val phoneHeading=booleanPreferencesKey("phone_heading_enabled");val phoneMotion=booleanPreferencesKey("phone_motion_enabled");val phonePressure=booleanPreferencesKey("phone_pressure_enabled");val proprietary=booleanPreferencesKey("phone_proprietary_enabled")}
    val settings=context.outputSettingsStore.data.map{p->NmeaDeviceOutputSettings(p[K.phonePosition]?:false,p[K.phoneHeading]?:false,p[K.phoneMotion]?:false,p[K.phonePressure]?:false,p[K.proprietary]?:false)}
    suspend fun save(value:NmeaDeviceOutputSettings)=context.outputSettingsStore.edit{it[K.phonePosition]=value.phonePositionEnabled;it[K.phoneHeading]=value.phoneHeadingEnabled;it[K.phoneMotion]=value.phoneMotionEnabled;it[K.phonePressure]=value.phonePressureEnabled;it[K.proprietary]=value.proprietaryStatusEnabled}
}
