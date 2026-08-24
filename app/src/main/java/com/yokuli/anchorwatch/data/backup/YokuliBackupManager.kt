package com.yokuli.anchorwatch.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.android.gms.location.LocationServices
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.DATABASE_SCHEMA_VERSION
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.LinzDepthCacheDao
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.TidePredictionCacheDao
import com.yokuli.anchorwatch.data.database.AnchorageDao
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.vessel.VesselSettingsRepository
import com.yokuli.anchorwatch.data.vessel.OutputSettingsRepository
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageSearchRepository
import com.yokuli.anchorwatch.data.anchorage.AnchorageSpatialIndexRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.NetworkAddressProvider
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.trip.DashboardTileBinding
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibrationRepository
import com.yokuli.anchorwatch.map.OfflineMapRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupOperationState(
    val running:Boolean=false,
    val progress:String="",
    val result:String?=null,
    val error:String?=null,
    val lastBackupAt:Long?=null,
)

data class BackupManifestV1(
    val format:String=YokuliBackupArchive.FORMAT,
    val formatVersion:Int=YokuliBackupArchive.VERSION,
    val createdAtUtc:String,
    val appVersionName:String,
    val appVersionCode:Int,
    val roomSchemaVersion:Int=DATABASE_SCHEMA_VERSION,
    val recordCounts:Map<String,Long>,
    val files:List<String>,
    val device:Map<String,String> = mapOf("platform" to "Android"),
)

/**
 * Stable v1 envelope. The payload is deliberately adapted at the boundary so
 * a future Room entity change does not silently change the backup container.
 */
data class BackupRecordV1(val schemaVersion:Int=1,val payload:JsonObject)
data class BackupSettingsV1(
    val schemaVersion:Int=1,
    val payload:JsonObject,
    val customAlarmSoundDisplayName:String?=null,
    val originalCustomAlarmSoundUri:String?=null,
)
data class BackupValidation(
    val manifest:BackupManifestV1,
    val settings:BackupSettingsV1,
    val appSettings:AppSettings,
    val files:Map<String,File>,
)

private data class BackupSnapshot(
    val sessions:List<com.yokuli.anchorwatch.data.database.AnchorSessionEntity>,
    val pointThroughId:Long,
    val eventThroughId:Long,
    val surveys:List<com.yokuli.anchorwatch.data.database.SonarSurveyEntity>,
    val sampleThroughId:Long,
    val anchorages:List<com.yokuli.anchorwatch.data.database.SavedAnchorageEntity>,
    val trips:List<com.yokuli.anchorwatch.data.database.TripSessionEntity>,
    val tripSampleThroughId:Long,
    val tripEventThroughId:Long,
    val tripWaypointThroughId:Long,
    val tripCustomMetricThroughId:Long,
    val tripDashboards:List<com.yokuli.anchorwatch.data.database.TripDashboardEntity>,
    val anchorTelemetryThroughId:Long,
    val anchorageRegions:List<com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity>,
    val anchoragePlaces:List<com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity>,
    val anchoragePlaceRegions:List<com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceRegionCrossRef>,
    val anchorageSpots:List<com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity>,
    val anchorageVisits:List<com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity>,
    val anchorageCollections:List<com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity>,
    val anchorageCollectionPlaces:List<com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionPlaceCrossRef>,
    val anchorageProtection:List<com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity>,
    val anchorageFacilities:List<com.yokuli.anchorwatch.data.database.entity.AnchorageFacilityEntity>,
    val anchorageRatings:List<com.yokuli.anchorwatch.data.database.entity.AnchoragePersonalRatingEntity>,
    val anchoragePhotos:List<com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity>,
)

private data class WrittenBackupEntry(val checksum:String,val recordCount:Long)

object BackupRestorePolicy {
    fun blockingReason(anchorActive:Boolean,sonarActive:Boolean,proxyActive:Boolean,sharingActive:Boolean,tripActive:Boolean=false,phoneOutputActive:Boolean=false,nmeaConnected:Boolean=false):String?=when{
        anchorActive->"End the active anchor session before restoring a backup."
        tripActive->"End the active Trip Watch session before restoring a backup."
        sonarActive->"Stop the active sonar survey before restoring a backup."
        proxyActive->"Disable the global GPS proxy before restoring a backup."
        sharingActive->"Disable NMEA Sharing before restoring a backup."
        phoneOutputActive->"Turn off all phone-to-boat NMEA outputs before restoring a backup."
        nmeaConnected->"Disconnect the live NMEA endpoint before restoring a backup."
        else->null
    }
}

/** External files are intentionally not embedded in the backup archive. Never
 * restore a preference that claims an absent local MBTiles file is active. */
object BackupExternalSettingsPolicy{
    fun reconcileOfflineMap(settings:AppSettings,installed:Boolean):AppSettings=
        if(installed)settings else settings.copy(offlineMapEnabled=false,offlineMapName=null,offlineMapAttribution=null)
}

/** ZIP/NDJSON archive primitives shared by production and corruption tests. */
object YokuliBackupArchive {
    const val FORMAT="YOKULI_BACKUP"
    const val VERSION=5
    const val LEGACY_VERSION=1
    const val VERSION_2=2
    const val VERSION_3=3
    const val VERSION_4=4
    const val EXTENSION=".yokuli-backup"
    const val MANIFEST="manifest.json"
    const val SETTINGS="settings.json"
    const val CHECKSUMS="checksums.json"
    const val ANCHORS="data/anchor_sessions.ndjson"
    const val POINTS="data/track_points.ndjson"
    const val EVENTS="data/alarm_events.ndjson"
    const val SURVEYS="data/sonar_surveys.ndjson"
    const val SAMPLES="data/depth_samples.ndjson"
    const val ANCHORAGES="data/saved_anchorages.ndjson"
    const val TRIPS="data/trip_sessions.ndjson"
    const val TRIP_SAMPLES="data/trip_samples.ndjson"
    const val TRIP_EVENTS="data/trip_events.ndjson"
    const val TRIP_WAYPOINTS="data/trip_waypoints.ndjson"
    const val ANCHOR_TELEMETRY="data/anchor_telemetry.ndjson"
    const val TRIP_CUSTOM_METRICS="data/trip_custom_metrics.ndjson"
    const val TRIP_DASHBOARDS="data/trip_dashboards.ndjson"
    const val VESSEL_SETTINGS="vessel_settings.json"
    const val GIS_REGIONS="data/anchorage_regions.ndjson"
    const val GIS_PLACES="data/anchorage_places.ndjson"
    const val GIS_PLACE_REGIONS="data/anchorage_place_regions.ndjson"
    const val GIS_SPOTS="data/anchorage_spots.ndjson"
    const val GIS_VISITS="data/anchorage_visits.ndjson"
    const val GIS_COLLECTIONS="data/anchorage_collections.ndjson"
    const val GIS_COLLECTION_PLACES="data/anchorage_collection_places.ndjson"
    const val GIS_PROTECTION="data/anchorage_protection_sectors.ndjson"
    const val GIS_FACILITIES="data/anchorage_facilities.ndjson"
    const val GIS_RATINGS="data/anchorage_personal_ratings.ndjson"
    const val GIS_PHOTOS="data/anchorage_photos.ndjson"
    const val GIS_MEDIA_PREFIX="media/anchorage/"
    val requiredV1=setOf(MANIFEST,SETTINGS,CHECKSUMS,ANCHORS,POINTS,EVENTS,SURVEYS,SAMPLES)
    val requiredV2=setOf(MANIFEST,SETTINGS,CHECKSUMS,ANCHORS,POINTS,EVENTS,SURVEYS,SAMPLES,ANCHORAGES)
    val requiredV3=requiredV2+setOf(TRIPS,TRIP_SAMPLES,TRIP_EVENTS,TRIP_WAYPOINTS,ANCHOR_TELEMETRY,VESSEL_SETTINGS)
    val requiredV4=requiredV3+setOf(TRIP_CUSTOM_METRICS,TRIP_DASHBOARDS)
    val gisFiles=listOf(GIS_REGIONS,GIS_PLACES,GIS_PLACE_REGIONS,GIS_SPOTS,GIS_VISITS,GIS_COLLECTIONS,GIS_COLLECTION_PLACES,GIS_PROTECTION,GIS_FACILITIES,GIS_RATINGS,GIS_PHOTOS)
    val required=requiredV4+gisFiles
    val allowed=required
    val dataFiles=listOf(ANCHORS,POINTS,EVENTS,SURVEYS,SAMPLES,ANCHORAGES,TRIPS,TRIP_SAMPLES,TRIP_EVENTS,TRIP_WAYPOINTS,ANCHOR_TELEMETRY,TRIP_CUSTOM_METRICS,TRIP_DASHBOARDS)+gisFiles
    fun requiredFor(version:Int)=when(version){LEGACY_VERSION->requiredV1;VERSION_2->requiredV2;VERSION_3->requiredV3;VERSION_4->requiredV4;else->required}
    const val MAX_ARCHIVE_BYTES=2_000_000_000L
    const val MAX_ENTRY_BYTES=1_500_000_000L
    const val PAGE=1_000

    fun sha256(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use{input->val buffer=ByteArray(64*1024);while(true){val read=input.read(buffer);if(read<0)break;digest.update(buffer,0,read)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }

    fun validateCoordinate(latitude:Double,longitude:Double){
        require(latitude.isFinite()&&latitude in -90.0..90.0){"Invalid latitude in backup"}
        require(longitude.isFinite()&&longitude in -180.0..180.0){"Invalid longitude in backup"}
    }
}

@Singleton
class YokuliBackupManager @Inject constructor(
    @ApplicationContext private val context:Context,
    private val database:AppDatabase,
    private val anchorDao:AnchorDao,
    private val sonarDao:SonarDao,
    private val settingsRepository:SettingsRepository,
    private val gridUpdater:SonarIncrementalGridUpdater,
    private val linzCache:LinzDepthCacheDao,
    private val tideCache:TidePredictionCacheDao,
    private val anchorageDao:AnchorageDao,
    private val tripDao:TripDao,
    private val vesselSettingsRepository:VesselSettingsRepository,
    private val outputSettingsRepository:OutputSettingsRepository,
    private val mountCalibrationRepository:VesselMountCalibrationRepository,
    private val anchorageSpatial:AnchorageSpatialIndexRepository=AnchorageSpatialIndexRepository(database,database.anchorageSpatialDao()),
    private val anchorageSearch:AnchorageSearchRepository=AnchorageSearchRepository(database,database.anchorageSearchDao()),
    private val navigation:NavigationRepository=NavigationRepository(LiveDepthRepository(settingsRepository),LiveWindRepository(),com.yokuli.anchorwatch.data.nmea.output.NmeaOutboundLoopGuard(),com.yokuli.anchorwatch.data.vessel.VesselSourceRegistry()),
    private val mockGps:GlobalMockLocationManager=GlobalMockLocationManager(context,LocationServices.getFusedLocationProviderClient(context)),
    private val sharingServer:NmeaSharingServer=NmeaSharingServer(NetworkAddressProvider()),
){
    private val gson=Gson()
    private val _state=MutableStateFlow(BackupOperationState())
    val state=_state.asStateFlow()

    suspend fun export(uri:Uri):Result<BackupManifestV1> = withContext(Dispatchers.IO){runCatching{
        _state.value=BackupOperationState(running=true,progress="Preparing a consistent data snapshot…")
        val settings=settingsRepository.settings.first()
        // Keep the Room transaction short. Parent rows and monotonically increasing
        // child-id ceilings form the logical snapshot; the potentially large child
        // tables are then streamed outside the transaction without including later writes.
        val snapshot=database.withTransaction{BackupSnapshot(
            sessions=anchorDao.allSessionsNow(),
            pointThroughId=anchorDao.maxPointId(),
            eventThroughId=anchorDao.maxEventId(),
            surveys=sonarDao.allSurveysNow(),
            sampleThroughId=sonarDao.maxSampleId(),
            anchorages=anchorageDao.allNow(),
            trips=tripDao.allSessionsNow(),
            tripSampleThroughId=tripDao.maxSampleId(),
            tripEventThroughId=tripDao.maxEventId(),
            tripWaypointThroughId=tripDao.maxWaypointId(),
            tripCustomMetricThroughId=tripDao.maxCustomMetricId(),
            tripDashboards=tripDao.allDashboardsNow(),
            anchorTelemetryThroughId=tripDao.maxAnchorTelemetryId(),
            anchorageRegions=database.anchorageRegionDao().allNow(),
            anchoragePlaces=database.anchoragePlaceDao().allNow(),
            anchoragePlaceRegions=database.anchorageMetadataDao().allPlaceRegions(),
            anchorageSpots=database.anchorageSpotDao().allNow(),
            anchorageVisits=database.anchorageVisitDao().allNow(),
            anchorageCollections=database.anchorageCollectionDao().allNow(),
            anchorageCollectionPlaces=database.anchorageCollectionDao().membershipsNow(),
            anchorageProtection=database.anchorageMetadataDao().allProtection(),
            anchorageFacilities=database.anchorageMetadataDao().allFacilities(),
            anchorageRatings=database.anchorageMetadataDao().allRatings(),
            anchoragePhotos=database.anchoragePhotoDao().allNow(),
        )}
        var completedManifest:BackupManifestV1?=null
        val raw=context.contentResolver.openOutputStream(uri,"w")?:error("Android could not open the selected backup file")
        raw.use{output->ZipOutputStream(BufferedOutputStream(output)).use{zip->
            val checksums=linkedMapOf<String,String>()
            val settingsV1=BackupSettingsV1(payload=gson.toJsonTree(settings).asJsonObject,customAlarmSoundDisplayName=displayName(settings.customAlarmSoundUri),originalCustomAlarmSoundUri=settings.customAlarmSoundUri)
            checksums[YokuliBackupArchive.SETTINGS]=writeTextEntry(zip,YokuliBackupArchive.SETTINGS,gson.toJson(settingsV1))
            checksums[YokuliBackupArchive.VESSEL_SETTINGS]=writeTextEntry(zip,YokuliBackupArchive.VESSEL_SETTINGS,gson.toJson(BackupVesselSettingsV3(value=vesselSettingsRepository.settings.first(),output=outputSettingsRepository.settings.first(),mountCalibration=mountCalibrationRepository.calibration.first())))
            val written=linkedMapOf<String,WrittenBackupEntry>()
            _state.value=_state.value.copy(progress="Exporting anchor sessions…")
            written[YokuliBackupArchive.ANCHORS]=writeRecords(zip,YokuliBackupArchive.ANCHORS,snapshot.sessions.asSequence().map(BackupAnchorSessionV2::from))
            written[YokuliBackupArchive.POINTS]=writePaged(zip,YokuliBackupArchive.POINTS){after->anchorDao.allPointsPageThrough(after,snapshot.pointThroughId,YokuliBackupArchive.PAGE).map(BackupTrackPointV1::from)}
            written[YokuliBackupArchive.EVENTS]=writePaged(zip,YokuliBackupArchive.EVENTS){after->anchorDao.allEventsPageThrough(after,snapshot.eventThroughId,YokuliBackupArchive.PAGE).map(BackupAlarmEventV1::from)}
            written[YokuliBackupArchive.SURVEYS]=writeRecords(zip,YokuliBackupArchive.SURVEYS,snapshot.surveys.asSequence().map(BackupSonarSurveyV1::from))
            _state.value=_state.value.copy(progress="Streaming sonar soundings…")
            written[YokuliBackupArchive.SAMPLES]=writePaged(zip,YokuliBackupArchive.SAMPLES){after->sonarDao.allSamplesPageThrough(after,snapshot.sampleThroughId,YokuliBackupArchive.PAGE).map(BackupDepthSampleV1::from)}
            written[YokuliBackupArchive.ANCHORAGES]=writeRecords(zip,YokuliBackupArchive.ANCHORAGES,snapshot.anchorages.asSequence().map(BackupSavedAnchorageV2::from))
            _state.value=_state.value.copy(progress="Streaming Trip Watch history…")
            written[YokuliBackupArchive.TRIPS]=writeRecords(zip,YokuliBackupArchive.TRIPS,snapshot.trips.asSequence().map(BackupTripSessionV3::from))
            written[YokuliBackupArchive.TRIP_SAMPLES]=writePaged(zip,YokuliBackupArchive.TRIP_SAMPLES){after->tripDao.allSamplesPageThrough(after,snapshot.tripSampleThroughId,YokuliBackupArchive.PAGE).map(BackupTripSampleV3::from)}
            written[YokuliBackupArchive.TRIP_EVENTS]=writePaged(zip,YokuliBackupArchive.TRIP_EVENTS){after->tripDao.allEventsPageThrough(after,snapshot.tripEventThroughId,YokuliBackupArchive.PAGE).map(BackupTripEventV3::from)}
            written[YokuliBackupArchive.TRIP_WAYPOINTS]=writePaged(zip,YokuliBackupArchive.TRIP_WAYPOINTS){after->tripDao.allWaypointsPageThrough(after,snapshot.tripWaypointThroughId,YokuliBackupArchive.PAGE).map(BackupTripWaypointV3::from)}
            written[YokuliBackupArchive.ANCHOR_TELEMETRY]=writePaged(zip,YokuliBackupArchive.ANCHOR_TELEMETRY){after->tripDao.allAnchorTelemetryPageThrough(after,snapshot.anchorTelemetryThroughId,YokuliBackupArchive.PAGE).map(BackupAnchorTelemetryV3::from)}
            written[YokuliBackupArchive.TRIP_CUSTOM_METRICS]=writePaged(zip,YokuliBackupArchive.TRIP_CUSTOM_METRICS){after->tripDao.allCustomMetricsPageThrough(after,snapshot.tripCustomMetricThroughId,YokuliBackupArchive.PAGE).map(BackupTripCustomMetricV4::from)}
            written[YokuliBackupArchive.TRIP_DASHBOARDS]=writeRecords(zip,YokuliBackupArchive.TRIP_DASHBOARDS,snapshot.tripDashboards.asSequence().map(BackupTripDashboardV4::from))
            _state.value=_state.value.copy(progress="Exporting anchorage GIS library…")
            written[YokuliBackupArchive.GIS_REGIONS]=writeRecords(zip,YokuliBackupArchive.GIS_REGIONS,snapshot.anchorageRegions.asSequence())
            written[YokuliBackupArchive.GIS_PLACES]=writeRecords(zip,YokuliBackupArchive.GIS_PLACES,snapshot.anchoragePlaces.asSequence())
            written[YokuliBackupArchive.GIS_PLACE_REGIONS]=writeRecords(zip,YokuliBackupArchive.GIS_PLACE_REGIONS,snapshot.anchoragePlaceRegions.asSequence())
            written[YokuliBackupArchive.GIS_SPOTS]=writeRecords(zip,YokuliBackupArchive.GIS_SPOTS,snapshot.anchorageSpots.asSequence())
            written[YokuliBackupArchive.GIS_VISITS]=writeRecords(zip,YokuliBackupArchive.GIS_VISITS,snapshot.anchorageVisits.asSequence())
            written[YokuliBackupArchive.GIS_COLLECTIONS]=writeRecords(zip,YokuliBackupArchive.GIS_COLLECTIONS,snapshot.anchorageCollections.asSequence())
            written[YokuliBackupArchive.GIS_COLLECTION_PLACES]=writeRecords(zip,YokuliBackupArchive.GIS_COLLECTION_PLACES,snapshot.anchorageCollectionPlaces.asSequence())
            written[YokuliBackupArchive.GIS_PROTECTION]=writeRecords(zip,YokuliBackupArchive.GIS_PROTECTION,snapshot.anchorageProtection.asSequence())
            written[YokuliBackupArchive.GIS_FACILITIES]=writeRecords(zip,YokuliBackupArchive.GIS_FACILITIES,snapshot.anchorageFacilities.asSequence())
            written[YokuliBackupArchive.GIS_RATINGS]=writeRecords(zip,YokuliBackupArchive.GIS_RATINGS,snapshot.anchorageRatings.asSequence())
            val backupPhotos=snapshot.anchoragePhotos.filter{photo->File(context.filesDir,"anchorage_media/${File(photo.relativeFileName).name}").let{it.isFile&&it.length()<=25L*1024*1024}}
            written[YokuliBackupArchive.GIS_PHOTOS]=writeRecords(zip,YokuliBackupArchive.GIS_PHOTOS,backupPhotos.asSequence())
            written.forEach{(name,entry)->checksums[name]=entry.checksum}
            val mediaEntries=linkedSetOf<String>()
            backupPhotos.flatMap{listOfNotNull(it.relativeFileName,it.thumbnailRelativeFileName)}.distinct().forEach{name->
                val safeName=File(name).name
                val source=File(context.filesDir,"anchorage_media/$safeName")
                if(source.isFile&&source.length()<=25L*1024*1024){
                    val entryName=YokuliBackupArchive.GIS_MEDIA_PREFIX+safeName
                    checksums[entryName]=writeBinaryEntry(zip,entryName,source)
                    mediaEntries+=entryName
                }
            }
            val manifest=BackupManifestV1(
                createdAtUtc=Instant.now().toString(),appVersionName=BuildConfig.VERSION_NAME,
                appVersionCode=BuildConfig.VERSION_CODE,recordCounts=written.mapValues{it.value.recordCount},
                files=listOf(YokuliBackupArchive.SETTINGS,YokuliBackupArchive.VESSEL_SETTINGS)+YokuliBackupArchive.dataFiles+mediaEntries,
            )
            completedManifest=manifest
            checksums[YokuliBackupArchive.MANIFEST]=writeTextEntry(zip,YokuliBackupArchive.MANIFEST,gson.toJson(manifest))
            writeTextEntry(zip,YokuliBackupArchive.CHECKSUMS,gson.toJson(checksums))
        }}
        val completed=System.currentTimeMillis();_state.value=BackupOperationState(result="Backup exported successfully.",lastBackupAt=completed)
        checkNotNull(completedManifest)
    }.onFailure{error->_state.value=BackupOperationState(error=error.message?:"Backup export failed",lastBackupAt=_state.value.lastBackupAt)}}

    suspend fun restore(uri:Uri):Result<BackupManifestV1> = withContext(Dispatchers.IO){runCatching{
        val current=settingsRepository.settings.first()
        val outputActive=outputSettingsRepository.settings.first().anyEnabled
        BackupRestorePolicy.blockingReason(
            anchorActive=anchorDao.active()!=null,
            sonarActive=sonarDao.active()!=null,
            proxyActive=GpsSourceSafety.requiresStopAction(current.mockEnabled,mockGps.status.value.state),
            sharingActive=current.nmeaSharingEnabled||sharingServer.status.value.state!=SharingServerState.STOPPED,
            tripActive=tripDao.active()!=null,
            phoneOutputActive=outputActive,
            nmeaConnected=navigation.connectionState.value!=NmeaConnectionState.DISCONNECTED,
        )?.let(::error)
        _state.value=BackupOperationState(running=true,progress="Validating archive without changing local data…",lastBackupAt=_state.value.lastBackupAt)
        val staging=File(context.cacheDir,"restore-staging-${System.nanoTime()}").apply{mkdirs()}
        try{
            val validation=stageAndValidate(uri,staging)
            _state.value=_state.value.copy(progress="Replacing local Anchor Watch data…")
            database.withTransaction{
                sonarDao.clearGridCells();linzCache.clear();tideCache.clear()
                sonarDao.clearSamples();sonarDao.clearSurveys()
                tripDao.clearSamples();tripDao.clearEvents();tripDao.clearWaypoints();tripDao.clearCustomMetrics();tripDao.clearDashboards();tripDao.clearAnchorTelemetry();tripDao.clearSessions()
                database.anchoragePhotoDao().clear();database.anchorageVisitDao().clear();database.anchorageCollectionDao().clearMemberships();database.anchorageCollectionDao().clear();database.anchorageMetadataDao().clearPlaceRegionsAll();database.anchorageMetadataDao().clearProtection();database.anchorageMetadataDao().clearFacilities();database.anchorageMetadataDao().clearRatings();database.anchorageMetadataDao().clearSummaries();database.anchorageSpotDao().clear();database.anchoragePlaceDao().clear();database.anchorageRegionDao().clear()
                anchorDao.clearEvents();anchorDao.clearPoints();anchorDao.clearSessions();anchorageDao.clear()
                if(validation.manifest.formatVersion==YokuliBackupArchive.LEGACY_VERSION)importFile(validation.files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importSessions(rows.map(BackupAnchorSessionV1::toEntity).map{if(it.active)it.copy(paused=true,alarmSnoozedUntil=null)else it})}
                else importFile(validation.files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV2::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importSessions(rows.map(BackupAnchorSessionV2::toEntity).map{if(it.active)it.copy(paused=true,alarmSnoozedUntil=null,depthAlarmSnoozedUntil=null,windAlarmSnoozedUntil=null,windShiftAlarmSnoozedUntil=null)else it})}
                importFile(validation.files.getValue(YokuliBackupArchive.POINTS),BackupTrackPointV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importPoints(rows.map(BackupTrackPointV1::toEntity))}
                importFile(validation.files.getValue(YokuliBackupArchive.EVENTS),BackupAlarmEventV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importEvents(rows.map(BackupAlarmEventV1::toEntity))}
                importFile(validation.files.getValue(YokuliBackupArchive.SURVEYS),BackupSonarSurveyV1::class.java,YokuliBackupArchive.PAGE){rows->sonarDao.importSurveys(rows.map(BackupSonarSurveyV1::toEntity).map{if(it.active)it.copy(active=false,endedAt=it.endedAt?:System.currentTimeMillis())else it})}
                importFile(validation.files.getValue(YokuliBackupArchive.SAMPLES),BackupDepthSampleV1::class.java,YokuliBackupArchive.PAGE){rows->sonarDao.importSamples(rows.map(BackupDepthSampleV1::toEntity))}
                validation.files[YokuliBackupArchive.ANCHORAGES]?.let{file->importFile(file,BackupSavedAnchorageV2::class.java,YokuliBackupArchive.PAGE){rows->anchorageDao.importAll(rows.map(BackupSavedAnchorageV2::toEntity))}}
                validation.files[YokuliBackupArchive.TRIPS]?.let{file->importFile(file,BackupTripSessionV3::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importSessions(rows.map{row->row.value.let{if(it.active)it.copy(paused=true,pausedAt=System.currentTimeMillis(),restoredAfterProcessDeath=false)else it}})}}
                validation.files[YokuliBackupArchive.TRIP_SAMPLES]?.let{file->importFile(file,BackupTripSampleV3::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importSamples(rows.map{it.value})}}
                validation.files[YokuliBackupArchive.TRIP_EVENTS]?.let{file->importFile(file,BackupTripEventV3::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importEvents(rows.map{it.value})}}
                validation.files[YokuliBackupArchive.TRIP_WAYPOINTS]?.let{file->importFile(file,BackupTripWaypointV3::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importWaypoints(rows.map{it.value})}}
                validation.files[YokuliBackupArchive.ANCHOR_TELEMETRY]?.let{file->importFile(file,BackupAnchorTelemetryV3::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importAnchorTelemetry(rows.map{it.value})}}
                validation.files[YokuliBackupArchive.TRIP_CUSTOM_METRICS]?.let{file->importFile(file,BackupTripCustomMetricV4::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importCustomMetrics(rows.map{it.value})}}
                validation.files[YokuliBackupArchive.TRIP_DASHBOARDS]?.let{file->importFile(file,BackupTripDashboardV4::class.java,YokuliBackupArchive.PAGE){rows->tripDao.importDashboards(rows.map{it.value})}}
                if(validation.manifest.formatVersion>=YokuliBackupArchive.VERSION){
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_REGIONS),com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageRegionDao()::importAll)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_PLACES),com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity::class.java,YokuliBackupArchive.PAGE,database.anchoragePlaceDao()::importAll)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_PLACE_REGIONS),com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceRegionCrossRef::class.java,YokuliBackupArchive.PAGE,database.anchorageMetadataDao()::importPlaceRegions)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_SPOTS),com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageSpotDao()::importAll)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_VISITS),com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageVisitDao()::importAll)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_COLLECTIONS),com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageCollectionDao()::importAll)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_COLLECTION_PLACES),com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionPlaceCrossRef::class.java,YokuliBackupArchive.PAGE,database.anchorageCollectionDao()::importMemberships)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_PROTECTION),com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageMetadataDao()::importProtection)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_FACILITIES),com.yokuli.anchorwatch.data.database.entity.AnchorageFacilityEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageMetadataDao()::importFacilities)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_RATINGS),com.yokuli.anchorwatch.data.database.entity.AnchoragePersonalRatingEntity::class.java,YokuliBackupArchive.PAGE,database.anchorageMetadataDao()::importRatings)
                    importFile(validation.files.getValue(YokuliBackupArchive.GIS_PHOTOS),com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity::class.java,YokuliBackupArchive.PAGE,database.anchoragePhotoDao()::importAll)
                }else importLegacyAnchoragesIntoGis()
            }
            if(validation.manifest.formatVersion>=YokuliBackupArchive.VERSION)restoreAnchorageMedia(validation.files)
            val offlineInstalled=File(context.filesDir,"offline_maps/${OfflineMapRepository.FILE_NAME}").let{it.isFile&&it.length()>0L}
            val imported=BackupExternalSettingsPolicy.reconcileOfflineMap(validation.appSettings,offlineInstalled).copy(
                onboardingCompleted=true,mockEnabled=false,nmeaSharingEnabled=false,customAlarmSoundUri=null,
                alarmSound=if(validation.appSettings.alarmSound==AlarmSound.CUSTOM)AlarmSound.SYSTEM_ALARM else validation.appSettings.alarmSound,
            )
            val settingsError=runCatching{settingsRepository.save(imported)}.exceptionOrNull()
            var restoredOutput=NmeaDeviceOutputSettings()
            val vesselSettingsError=validation.files[YokuliBackupArchive.VESSEL_SETTINGS]?.let{file->runCatching{val value=gson.fromJson(file.readText(),BackupVesselSettingsV3::class.java);require(value.schemaVersion in 1..3);vesselSettingsRepository.save(value.value);restoredOutput=value.output;value.mountCalibration?.let{mountCalibrationRepository.restore(it)}}.exceptionOrNull()}
            // Destination/address choices are configuration and survive restore;
            // publication is an operational decision and is always forced OFF.
            val safeOutput=restoredOutput.copy(phonePositionEnabled=false,phoneHeadingEnabled=false,phoneMotionEnabled=false,phonePressureEnabled=false,proprietaryStatusEnabled=false,positionPolicy=com.yokuli.anchorwatch.domain.vessel.PublicationPolicy.OFF,headingPolicy=com.yokuli.anchorwatch.domain.vessel.PublicationPolicy.OFF,motionPolicy=com.yokuli.anchorwatch.domain.vessel.PublicationPolicy.OFF,pressurePolicy=com.yokuli.anchorwatch.domain.vessel.PublicationPolicy.OFF,derivedWindPolicy=com.yokuli.anchorwatch.domain.vessel.PublicationPolicy.OFF,destinations=restoredOutput.destinations.map{it.copy(enabled=false)},publicationEnabled=false,autoStartOutput=false)
            val outputSettingsError=runCatching{outputSettingsRepository.save(safeOutput)}.exceptionOrNull()
            val rebuildError=runCatching{gridUpdater.rebuildMissing()}.exceptionOrNull()
            runCatching{anchorageSpatial.verifyAndRepair();anchorageSearch.rebuildAll()}
            val message=buildString{
                append("Backup data restored. Active watches were restored paused; sonar recording was closed.")
                if(settingsError!=null)append(" Some preferences could not be restored and remain unchanged.")
                if(vesselSettingsError!=null)append(" Vessel display preferences could not be restored.")
                if(outputSettingsError!=null)append(" Phone NMEA output could not be forced off; verify Data Output before use.")
                if(rebuildError!=null)append(" The derived sonar map will be rebuilt the next time Anchor Watch starts.")
                append(" Restart Anchor Watch before use.")
            }
            _state.value=BackupOperationState(result=message,lastBackupAt=_state.value.lastBackupAt)
            validation.manifest
        }finally{staging.deleteRecursively()}
    }.onFailure{error->_state.value=BackupOperationState(error=error.message?:"Backup restore failed",lastBackupAt=_state.value.lastBackupAt)}}

    fun clearResult(){_state.value=_state.value.copy(result=null,error=null)}

    private suspend fun stageAndValidate(uri:Uri,staging:File):BackupValidation{
        val found=linkedMapOf<String,File>();var archiveBytes=0L
        val source=context.contentResolver.openInputStream(uri)?:error("Android could not read the selected backup")
        ZipInputStream(BufferedInputStream(source)).use{zip->
            while(true){val entry=zip.nextEntry?:break
                require(!entry.isDirectory){"Unexpected directory in backup"}
                require(entry.name in YokuliBackupArchive.allowed||entry.name.startsWith(YokuliBackupArchive.GIS_MEDIA_PREFIX)){"Unexpected backup entry: ${entry.name}"}
                require(found.put(entry.name,File(staging,entry.name.replace('/','_')))==null){"Duplicate backup entry: ${entry.name}"}
                val target=found.getValue(entry.name);FileOutputStream(target).use{out->
                    val buffer=ByteArray(64*1024);var entryBytes=0L
                    while(true){val read=zip.read(buffer);if(read<0)break;entryBytes+=read;archiveBytes+=read;require(entryBytes<=YokuliBackupArchive.MAX_ENTRY_BYTES&&archiveBytes<=YokuliBackupArchive.MAX_ARCHIVE_BYTES){"Backup exceeds safety size limits"};out.write(buffer,0,read)}
                };zip.closeEntry()
            }
        }
        val manifest=gson.fromJson(found.getValue(YokuliBackupArchive.MANIFEST).readText(),BackupManifestV1::class.java)
        require(manifest.format==YokuliBackupArchive.FORMAT){"This is not an Anchor Watch backup"};require(manifest.formatVersion in setOf(YokuliBackupArchive.LEGACY_VERSION,YokuliBackupArchive.VERSION_2,YokuliBackupArchive.VERSION_3,YokuliBackupArchive.VERSION_4,YokuliBackupArchive.VERSION)){"Unsupported Anchor Watch backup version ${manifest.formatVersion}"}
        val required=YokuliBackupArchive.requiredFor(manifest.formatVersion)
        val expected=if(manifest.formatVersion>=YokuliBackupArchive.VERSION)required+manifest.files.filter{it.startsWith(YokuliBackupArchive.GIS_MEDIA_PREFIX)} else required
        require(found.keys.containsAll(expected)){"Backup is missing required files: ${expected-found.keys}"};require(found.keys==expected){"Unexpected files for backup V${manifest.formatVersion}: ${found.keys-expected}"}
        @Suppress("UNCHECKED_CAST") val checksums=gson.fromJson(found.getValue(YokuliBackupArchive.CHECKSUMS).readText(),Map::class.java) as Map<String,String>
        (expected-YokuliBackupArchive.CHECKSUMS).forEach{name->require(checksums[name]==YokuliBackupArchive.sha256(found.getValue(name))){"Checksum mismatch for $name"}}
        val settings=gson.fromJson(found.getValue(YokuliBackupArchive.SETTINGS).readText(),BackupSettingsV1::class.java);require(settings.schemaVersion==1){"Unsupported settings schema"}
        val decodedSettings=requireNotNull(gson.fromJson(settings.payload,AppSettings::class.java)){"Backup settings payload is invalid"}
        if(manifest.formatVersion>=3){
            val vessel=gson.fromJson(found.getValue(YokuliBackupArchive.VESSEL_SETTINGS).readText(),BackupVesselSettingsV3::class.java)
            require(vessel.schemaVersion in 1..3){"Unsupported vessel settings schema"}
            vessel.mountCalibration?.let{calibration->
                val q=calibration.neutralQuaternion
                val normSquared=q.w*q.w+q.x*q.x+q.y*q.y+q.z*q.z
                require(calibration.version>=1&&calibration.calibratedAt>=0L&&listOf(q.w,q.x,q.y,q.z,normSquared).all(Double::isFinite)&&normSquared>1e-12){"Invalid vessel mount calibration"}
            }
        }
        val appSettings=if(manifest.formatVersion==YokuliBackupArchive.LEGACY_VERSION)decodedSettings.copy(defaultDepthGuardEnabled=false,defaultShallowDepthMeters=2.5,defaultDeepDepthEnabled=false,defaultDeepDepthMeters=15.0,defaultWindGuardEnabled=false,defaultWindWarningKnots=25.0,defaultWindAlarmKnots=35.0,defaultWindShiftEnabled=false,defaultWindShiftDegrees=70.0,allowApparentWindFallback=true)else decodedSettings
        validateRecords(found,manifest)
        return BackupValidation(manifest,settings,appSettings,found)
    }

    private fun validateRecords(files:Map<String,File>,manifest:BackupManifestV1){
        val sessions=mutableSetOf<Long>();var count=0L;var activeAnchorCount=0
        if(manifest.formatVersion==YokuliBackupArchive.LEGACY_VERSION)forEach(files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV1::class.java){row->YokuliBackupArchive.validateCoordinate(row.anchorLatitude,row.anchorLongitude);row.learningReferenceLatitude?.let{YokuliBackupArchive.validateCoordinate(it,requireNotNull(row.learningReferenceLongitude))};require(sessions.add(row.id)){"Duplicate anchor session id"};if(row.active)activeAnchorCount++;count++}
        else forEach(files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV2::class.java){row->val base=row.base;YokuliBackupArchive.validateCoordinate(base.anchorLatitude,base.anchorLongitude);require(sessions.add(base.id)){"Duplicate anchor session id"};if(base.active)activeAnchorCount++;count++}
        require(activeAnchorCount<=1){"Backup contains more than one active Anchor Watch session"}
        requireCount(manifest,YokuliBackupArchive.ANCHORS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.POINTS),BackupTrackPointV1::class.java){row->require(row.sessionId in sessions){"Track point references a missing anchor session"};YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);count++};requireCount(manifest,YokuliBackupArchive.POINTS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.EVENTS),BackupAlarmEventV1::class.java){row->require(row.sessionId in sessions){"Alarm event references a missing anchor session"};count++};requireCount(manifest,YokuliBackupArchive.EVENTS,count)
        val surveys=mutableSetOf<Long>();count=0;forEach(files.getValue(YokuliBackupArchive.SURVEYS),BackupSonarSurveyV1::class.java){row->require(surveys.add(row.id)){"Duplicate sonar survey id"};count++};requireCount(manifest,YokuliBackupArchive.SURVEYS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.SAMPLES),BackupDepthSampleV1::class.java){row->require(row.surveyId in surveys){"Depth sample references a missing sonar survey"};YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);require(row.rawDepthMeters.isFinite()&&row.rawDepthMeters>0){"Invalid raw sonar depth"};count++};requireCount(manifest,YokuliBackupArchive.SAMPLES,count)
        if(manifest.formatVersion>=2){val anchorageIds=mutableSetOf<Long>();count=0;forEach(files.getValue(YokuliBackupArchive.ANCHORAGES),BackupSavedAnchorageV2::class.java){row->require(anchorageIds.add(row.id)){"Duplicate saved anchorage id"};YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);require(row.name.length<=200&&row.notes.length<=20_000&&(row.customSeabedText?.length?:0)<=200){"Saved anchorage text is too long"};require(row.rating==null||row.rating in 1..5){"Invalid saved anchorage rating"};require(listOfNotNull(row.preferredAlarmRadiusMeters,row.typicalWaterDepthMeters,row.typicalRodeLengthMeters,row.coordinateUncertaintyMeters).all{it.isFinite()&&it>=0}){"Invalid saved anchorage measurement"};require(row.coordinateSource in com.yokuli.anchorwatch.data.anchorage.AnchorageCoordinateSource.entries.map{it.name}){"Invalid saved anchorage coordinate source"};count++};requireCount(manifest,YokuliBackupArchive.ANCHORAGES,count)}
        if(manifest.formatVersion>=3)validateTripRecords(files,manifest,sessions,activeAnchorCount>0)
        if(manifest.formatVersion>=YokuliBackupArchive.VERSION)validateGisRecords(files,manifest,sessions)
    }

    private fun validateGisRecords(files:Map<String,File>,manifest:BackupManifestV1,sessionIds:Set<Long>){
        val regions=mutableSetOf<Long>();var count=0L
        forEach(files.getValue(YokuliBackupArchive.GIS_REGIONS),com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity::class.java){row->require(row.id>0&&regions.add(row.id));YokuliBackupArchive.validateCoordinate(row.centerLatitude,row.centerLongitude);count++};requireCount(manifest,YokuliBackupArchive.GIS_REGIONS,count)
        val places=mutableSetOf<Long>();count=0
        forEach(files.getValue(YokuliBackupArchive.GIS_PLACES),com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity::class.java){row->require(row.id>0&&places.add(row.id)&&row.displayName.isNotBlank());YokuliBackupArchive.validateCoordinate(row.centerLatitude,row.centerLongitude);require(row.primaryRegionId==null||row.primaryRegionId in regions);count++};requireCount(manifest,YokuliBackupArchive.GIS_PLACES,count)
        val spots=mutableSetOf<Long>();count=0
        forEach(files.getValue(YokuliBackupArchive.GIS_SPOTS),com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity::class.java){row->require(row.id>0&&spots.add(row.id)&&row.placeId in places&&row.name.isNotBlank());YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);count++};requireCount(manifest,YokuliBackupArchive.GIS_SPOTS,count)
        val visits=mutableSetOf<Long>();count=0
        forEach(files.getValue(YokuliBackupArchive.GIS_VISITS),com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity::class.java){row->require(row.id>0&&visits.add(row.id)&&row.placeId in places);require(row.spotId==null||row.spotId in spots);require(row.anchorSessionId==null||row.anchorSessionId in sessionIds);count++};requireCount(manifest,YokuliBackupArchive.GIS_VISITS,count)
        listOf(
            YokuliBackupArchive.GIS_PLACE_REGIONS to com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceRegionCrossRef::class.java,
            YokuliBackupArchive.GIS_COLLECTIONS to com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity::class.java,
            YokuliBackupArchive.GIS_COLLECTION_PLACES to com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionPlaceCrossRef::class.java,
            YokuliBackupArchive.GIS_PROTECTION to com.yokuli.anchorwatch.data.database.entity.AnchorageProtectionSectorEntity::class.java,
            YokuliBackupArchive.GIS_FACILITIES to com.yokuli.anchorwatch.data.database.entity.AnchorageFacilityEntity::class.java,
            YokuliBackupArchive.GIS_RATINGS to com.yokuli.anchorwatch.data.database.entity.AnchoragePersonalRatingEntity::class.java,
            YokuliBackupArchive.GIS_PHOTOS to com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity::class.java,
        ).forEach{(name,type)->var records=0L;forEach(files.getValue(name),type){records++};requireCount(manifest,name,records)}
    }

    private fun validateTripRecords(files:Map<String,File>,manifest:BackupManifestV1,anchorSessionIds:Set<Long>,anchorActive:Boolean){
        val tripIds=mutableSetOf<Long>();var count=0L;var activeTripCount=0
        forEach(files.getValue(YokuliBackupArchive.TRIPS),BackupTripSessionV3::class.java){row->require(row.value.id>0&&tripIds.add(row.value.id)){"Duplicate trip session id"};if(row.value.active)activeTripCount++;count++};requireCount(manifest,YokuliBackupArchive.TRIPS,count)
        require(activeTripCount<=1){"Backup contains more than one active Trip Watch session"}
        require(!(anchorActive&&activeTripCount>0)){"Backup cannot contain active Anchor Watch and Trip Watch sessions at the same time"}
        count=0;forEach(files.getValue(YokuliBackupArchive.TRIP_SAMPLES),BackupTripSampleV3::class.java){row->require(row.value.tripId in tripIds){"Trip sample references a missing trip"};if(row.value.latitude!=null&&row.value.longitude!=null)YokuliBackupArchive.validateCoordinate(row.value.latitude,row.value.longitude);count++};requireCount(manifest,YokuliBackupArchive.TRIP_SAMPLES,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.TRIP_EVENTS),BackupTripEventV3::class.java){row->require(row.value.tripId in tripIds){"Trip event references a missing trip"};count++};requireCount(manifest,YokuliBackupArchive.TRIP_EVENTS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.TRIP_WAYPOINTS),BackupTripWaypointV3::class.java){row->require(row.value.tripId in tripIds){"Trip waypoint references a missing trip"};YokuliBackupArchive.validateCoordinate(row.value.latitude,row.value.longitude);count++};requireCount(manifest,YokuliBackupArchive.TRIP_WAYPOINTS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.ANCHOR_TELEMETRY),BackupAnchorTelemetryV3::class.java){row->require(row.value.sessionId in anchorSessionIds){"Anchor telemetry references a missing anchor session"};count++};requireCount(manifest,YokuliBackupArchive.ANCHOR_TELEMETRY,count)
        if(manifest.formatVersion>=4){
            count=0;forEach(files.getValue(YokuliBackupArchive.TRIP_CUSTOM_METRICS),BackupTripCustomMetricV4::class.java){row->val value=row.value;require(value.tripId in tripIds){"Custom metric references a missing trip"};require(value.fieldId.length<=300&&value.displayName.length<=200&&(value.textValue?.length?:0)<=2_000&&(value.unit?.length?:0)<=40){"Invalid custom metric text"};require(value.numericValue?.isFinite()!=false&&value.fieldAgeMillis>=0){"Invalid custom metric value"};count++};requireCount(manifest,YokuliBackupArchive.TRIP_CUSTOM_METRICS,count)
            count=0;val dashboardIds=mutableSetOf<String>();val bindingType=object:TypeToken<List<DashboardTileBinding>>(){}.type
            forEach(files.getValue(YokuliBackupArchive.TRIP_DASHBOARDS),BackupTripDashboardV4::class.java){row->val value=row.value;require(value.id.isNotBlank()&&dashboardIds.add(value.id)&&value.id.length<=80&&value.title.length<=120&&value.layoutJson.length<=100_000){"Invalid dashboard"};val bindings:List<DashboardTileBinding> = runCatching{gson.fromJson<List<DashboardTileBinding>>(value.layoutJson,bindingType)}.getOrNull()?:error("Invalid dashboard layout");require(bindings.size<=24&&bindings.all{(it.tileId==null) xor it.nmeaFieldId.isNullOrBlank()}&&bindings.all{it.scale.isFinite()&&it.offset.isFinite()&&(it.label?.length?:0)<=120&&(it.unitOverride?.length?:0)<=40}){"Invalid dashboard binding"};count++};requireCount(manifest,YokuliBackupArchive.TRIP_DASHBOARDS,count)
        }
    }

    private fun requireCount(manifest:BackupManifestV1,name:String,actual:Long){require(manifest.recordCounts[name]==actual){"Record count mismatch for $name"}}
    private fun <T> forEach(file:File,type:Class<T>,action:(T)->Unit){file.bufferedReader().useLines{lines->lines.forEach{line->if(line.isNotBlank()){val envelope=gson.fromJson(line,BackupRecordV1::class.java);require(envelope.schemaVersion==1){"Unsupported record schema"};action(gson.fromJson(envelope.payload,type))}}}}
    private suspend fun <T> importFile(file:File,type:Class<T>,batchSize:Int,insert:suspend(List<T>)->Unit){val batch=ArrayList<T>(batchSize);BufferedReader(InputStreamReader(FileInputStream(file))).use{reader->while(true){val line=reader.readLine()?:break;if(line.isBlank())continue;val envelope=gson.fromJson(line,BackupRecordV1::class.java);batch+=gson.fromJson(envelope.payload,type);if(batch.size==batchSize){insert(batch.toList());batch.clear()}}};if(batch.isNotEmpty())insert(batch)}

    private suspend fun importLegacyAnchoragesIntoGis(){
        val placeDao=database.anchoragePlaceDao();val spotDao=database.anchorageSpotDao();val visitDao=database.anchorageVisitDao();val metadata=database.anchorageMetadataDao()
        anchorageDao.allNow().forEach{legacy->
            if(placeDao.byLegacyId(legacy.id)!=null)return@forEach
            val verified=if(legacy.sourceSessionId!=null)"VERIFIED_BY_SESSION" else "PLANNED"
            val placeId=placeDao.insert(com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity(
                displayName=legacy.name,placeType="UNKNOWN",geometryType="POINT",centerLatitude=legacy.latitude,centerLongitude=legacy.longitude,
                bboxMinLatitude=legacy.latitude,bboxMaxLatitude=legacy.latitude,bboxMinLongitude=legacy.longitude,bboxMaxLongitude=legacy.longitude,
                personalNotes=legacy.notes,verificationStatus=verified,favorite=true,legacyVisitCount=legacy.visitCount,lastVisitedAt=legacy.lastVisitedAt,
                legacySavedAnchorageId=legacy.id,createdAt=legacy.createdAt,updatedAt=legacy.updatedAt,
            ))
            val spotId=spotDao.insert(com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity(
                placeId=placeId,name="Main spot",spotType=if(legacy.sourceSessionId==null)"PLANNED_REFERENCE" else "ANCHOR_SPOT",
                latitude=legacy.latitude,longitude=legacy.longitude,coordinateSource=legacy.coordinateSource,coordinateUncertaintyMeters=legacy.coordinateUncertaintyMeters,
                preferredAlarmRadiusMeters=legacy.preferredAlarmRadiusMeters,typicalWaterDepthMeters=legacy.typicalWaterDepthMeters,typicalRodeLengthMeters=legacy.typicalRodeLengthMeters,
                seabedType=legacy.seabedType,customSeabedText=legacy.customSeabedText,verificationStatus=verified,legacyVisitCount=legacy.visitCount,lastVisitedAt=legacy.lastVisitedAt,
                legacySavedAnchorageId=legacy.id,createdAt=legacy.createdAt,updatedAt=legacy.updatedAt,
            ))
            legacy.rating?.let{metadata.upsertRating(com.yokuli.anchorwatch.data.database.entity.AnchoragePersonalRatingEntity(placeId=placeId,legacyOverallRating=it,updatedAt=legacy.updatedAt))}
            legacy.sourceSessionId?.let{sessionId->anchorDao.session(sessionId)?.let{session->
                val visitId=visitDao.insert(com.yokuli.anchorwatch.data.database.entity.AnchorageVisitEntity(
                    placeId=placeId,spotId=spotId,anchorSessionId=sessionId,visitKind="SESSION",startedAt=session.startedAt,endedAt=session.endedAt,
                    actualAnchorLatitude=session.anchorLatitude,actualAnchorLongitude=session.anchorLongitude,coordinateSource=legacy.coordinateSource,
                    coordinateUncertaintyMeters=legacy.coordinateUncertaintyMeters,waterDepthMeters=legacy.typicalWaterDepthMeters,rodeLengthMeters=legacy.typicalRodeLengthMeters,
                    alarmRadiusMeters=legacy.preferredAlarmRadiusMeters,maxExcursionMeters=session.maxDistanceMeters,alarmCount=session.alarmCount,
                    minDepthMeters=session.minObservedDepthMeters,maxDepthMeters=session.maxObservedDepthMeters,maxWindKnots=session.maxObservedWindKnots,maxWindSource=session.maxObservedWindSource,
                    typicalMotionScore=null,p95MotionScore=null,p95AbsoluteHeelDegrees=null,dominantRollPeriodSeconds=null,impactCount=null,userNotes=legacy.notes,summaryVersion="1",createdAt=legacy.updatedAt,
                ))
                anchorDao.updateSession(session.copy(anchoragePlaceId=placeId,anchorageSpotId=spotId,anchorageVisitId=visitId))
            }}
        }
        val now=System.currentTimeMillis();if(database.anchorageCollectionDao().allNow().isEmpty())database.anchorageCollectionDao().importAll(listOf("Favorites","Want to visit","Backup").mapIndexed{index,name->com.yokuli.anchorwatch.data.database.entity.AnchorageCollectionEntity(id=index+1L,name=name,sortOrder=index,createdAt=now,updatedAt=now)})
    }

    private fun restoreAnchorageMedia(files:Map<String,File>){
        val target=File(context.filesDir,"anchorage_media").apply{mkdirs()}
        files.filterKeys{it.startsWith(YokuliBackupArchive.GIS_MEDIA_PREFIX)}.forEach{(entry,source)->
            val name=File(entry.removePrefix(YokuliBackupArchive.GIS_MEDIA_PREFIX)).name
            val temporary=File(target,".$name.restore")
            source.copyTo(temporary,overwrite=true)
            if(!temporary.renameTo(File(target,name))){temporary.copyTo(File(target,name),overwrite=true);temporary.delete()}
        }
    }

    private fun displayName(uri:String?):String?=uri?.let{value->runCatching{context.contentResolver.query(Uri.parse(value),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0)else null}}.getOrNull()}
    private suspend fun writeTextEntry(zip:ZipOutputStream,name:String,text:String):String=writeEntry(zip,name){writer->writer.write(text)}
    private fun writeBinaryEntry(zip:ZipOutputStream,name:String,file:File):String{
        zip.putNextEntry(ZipEntry(name));val digest=MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use{input->val buffer=ByteArray(64*1024);while(true){val read=input.read(buffer);if(read<0)break;digest.update(buffer,0,read);zip.write(buffer,0,read)}}
        zip.closeEntry();return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private suspend fun writeRecords(zip:ZipOutputStream,name:String,rows:Sequence<Any>):WrittenBackupEntry{
        var count=0L
        val checksum=writeEntry(zip,name){writer->rows.forEach{row->writer.write(gson.toJson(BackupRecordV1(payload=gson.toJsonTree(row).asJsonObject)));writer.newLine();count++}}
        return WrittenBackupEntry(checksum,count)
    }
    private suspend fun <T> writePaged(zip:ZipOutputStream,name:String,load:suspend(Long)->List<T>):WrittenBackupEntry{
        var after=0L;var count=0L
        val checksum=writeEntry(zip,name){writer->while(true){val page=load(after);if(page.isEmpty())break;page.forEach{row->writer.write(gson.toJson(BackupRecordV1(payload=gson.toJsonTree(row).asJsonObject)));writer.newLine();count++};after=(gson.toJsonTree(page.last()).asJsonObject.get("id")?.asLong?:error("Backup row has no id"))}}
        return WrittenBackupEntry(checksum,count)
    }
    private suspend fun writeEntry(zip:ZipOutputStream,name:String,block:suspend (BufferedWriter)->Unit):String{
        zip.putNextEntry(ZipEntry(name));val digest=MessageDigest.getInstance("SHA-256");val writer=BufferedWriter(OutputStreamWriter(DigestingNonClosingOutputStream(zip,digest),Charsets.UTF_8));block(writer);writer.flush();zip.closeEntry();return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private class DigestingNonClosingOutputStream(output:OutputStream,private val digest:MessageDigest):FilterOutputStream(output){override fun write(value:Int){digest.update(value.toByte());out.write(value)};override fun write(buffer:ByteArray,offset:Int,length:Int){digest.update(buffer,offset,length);out.write(buffer,offset,length)};override fun close(){flush()}}
}
