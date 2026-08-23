package com.yokuli.anchorwatch.data.backup

import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.vessel.VesselDataSettings
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration

/** V3 wrappers freeze the archive boundary independently of NDJSON envelopes. */
data class BackupTripSessionV3(val value:TripSessionEntity,val id:Long=value.id){companion object{fun from(value:TripSessionEntity)=BackupTripSessionV3(value)}}
data class BackupTripSampleV3(val value:TripSampleEntity,val id:Long=value.id){companion object{fun from(value:TripSampleEntity)=BackupTripSampleV3(value)}}
data class BackupTripEventV3(val value:TripEventEntity,val id:Long=value.id){companion object{fun from(value:TripEventEntity)=BackupTripEventV3(value)}}
data class BackupTripWaypointV3(val value:TripWaypointEntity,val id:Long=value.id){companion object{fun from(value:TripWaypointEntity)=BackupTripWaypointV3(value)}}
data class BackupAnchorTelemetryV3(val value:AnchorTelemetrySampleEntity,val id:Long=value.id){companion object{fun from(value:AnchorTelemetrySampleEntity)=BackupAnchorTelemetryV3(value)}}
data class BackupTripCustomMetricV4(val value:TripCustomMetricSampleEntity,val id:Long=value.id){companion object{fun from(value:TripCustomMetricSampleEntity)=BackupTripCustomMetricV4(value)}}
data class BackupTripDashboardV4(val value:TripDashboardEntity,val id:String=value.id){companion object{fun from(value:TripDashboardEntity)=BackupTripDashboardV4(value)}}
data class BackupVesselSettingsV3(
    val schemaVersion:Int=3,
    val value:VesselDataSettings=VesselDataSettings(),
    val output:NmeaDeviceOutputSettings=NmeaDeviceOutputSettings(),
    val mountCalibration:VesselMountCalibration?=null,
)
