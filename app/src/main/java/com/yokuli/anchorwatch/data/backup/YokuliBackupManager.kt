package com.yokuli.anchorwatch.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.LinzDepthCacheDao
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.TidePredictionCacheDao
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sonar.SonarIncrementalGridUpdater
import com.yokuli.anchorwatch.domain.model.AlarmSound
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
    val roomSchemaVersion:Int=11,
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
)

private data class WrittenBackupEntry(val checksum:String,val recordCount:Long)

object BackupRestorePolicy {
    fun blockingReason(anchorActive:Boolean,sonarActive:Boolean,proxyActive:Boolean,sharingActive:Boolean):String?=when{
        anchorActive->"End the active anchor session before restoring a backup."
        sonarActive->"Stop the active sonar survey before restoring a backup."
        proxyActive->"Disable the global GPS proxy before restoring a backup."
        sharingActive->"Disable NMEA Sharing before restoring a backup."
        else->null
    }
}

/** ZIP/NDJSON archive primitives shared by production and corruption tests. */
object YokuliBackupArchive {
    const val FORMAT="YOKULI_BACKUP"
    const val VERSION=1
    const val EXTENSION=".yokuli-backup"
    const val MANIFEST="manifest.json"
    const val SETTINGS="settings.json"
    const val CHECKSUMS="checksums.json"
    const val ANCHORS="data/anchor_sessions.ndjson"
    const val POINTS="data/track_points.ndjson"
    const val EVENTS="data/alarm_events.ndjson"
    const val SURVEYS="data/sonar_surveys.ndjson"
    const val SAMPLES="data/depth_samples.ndjson"
    val required=setOf(MANIFEST,SETTINGS,CHECKSUMS,ANCHORS,POINTS,EVENTS,SURVEYS,SAMPLES)
    val dataFiles=listOf(ANCHORS,POINTS,EVENTS,SURVEYS,SAMPLES)
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
        )}
        var completedManifest:BackupManifestV1?=null
        val raw=context.contentResolver.openOutputStream(uri,"w")?:error("Android could not open the selected backup file")
        raw.use{output->ZipOutputStream(BufferedOutputStream(output)).use{zip->
            val checksums=linkedMapOf<String,String>()
            val settingsV1=BackupSettingsV1(payload=gson.toJsonTree(settings).asJsonObject,customAlarmSoundDisplayName=displayName(settings.customAlarmSoundUri),originalCustomAlarmSoundUri=settings.customAlarmSoundUri)
            checksums[YokuliBackupArchive.SETTINGS]=writeTextEntry(zip,YokuliBackupArchive.SETTINGS,gson.toJson(settingsV1))
            val written=linkedMapOf<String,WrittenBackupEntry>()
            _state.value=_state.value.copy(progress="Exporting anchor sessions…")
            written[YokuliBackupArchive.ANCHORS]=writeRecords(zip,YokuliBackupArchive.ANCHORS,snapshot.sessions.asSequence().map(BackupAnchorSessionV1::from))
            written[YokuliBackupArchive.POINTS]=writePaged(zip,YokuliBackupArchive.POINTS){after->anchorDao.allPointsPageThrough(after,snapshot.pointThroughId,YokuliBackupArchive.PAGE).map(BackupTrackPointV1::from)}
            written[YokuliBackupArchive.EVENTS]=writePaged(zip,YokuliBackupArchive.EVENTS){after->anchorDao.allEventsPageThrough(after,snapshot.eventThroughId,YokuliBackupArchive.PAGE).map(BackupAlarmEventV1::from)}
            written[YokuliBackupArchive.SURVEYS]=writeRecords(zip,YokuliBackupArchive.SURVEYS,snapshot.surveys.asSequence().map(BackupSonarSurveyV1::from))
            _state.value=_state.value.copy(progress="Streaming sonar soundings…")
            written[YokuliBackupArchive.SAMPLES]=writePaged(zip,YokuliBackupArchive.SAMPLES){after->sonarDao.allSamplesPageThrough(after,snapshot.sampleThroughId,YokuliBackupArchive.PAGE).map(BackupDepthSampleV1::from)}
            written.forEach{(name,entry)->checksums[name]=entry.checksum}
            val manifest=BackupManifestV1(
                createdAtUtc=Instant.now().toString(),appVersionName=BuildConfig.VERSION_NAME,
                appVersionCode=BuildConfig.VERSION_CODE,recordCounts=written.mapValues{it.value.recordCount},
                files=listOf(YokuliBackupArchive.SETTINGS)+YokuliBackupArchive.dataFiles,
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
        BackupRestorePolicy.blockingReason(anchorDao.active()!=null,sonarDao.active()!=null,current.mockEnabled,current.nmeaSharingEnabled)?.let(::error)
        _state.value=BackupOperationState(running=true,progress="Validating archive without changing local data…",lastBackupAt=_state.value.lastBackupAt)
        val staging=File(context.cacheDir,"restore-staging-${System.nanoTime()}").apply{mkdirs()}
        try{
            val validation=stageAndValidate(uri,staging)
            _state.value=_state.value.copy(progress="Replacing local Yokuli data…")
            database.withTransaction{
                sonarDao.clearGridCells();linzCache.clear();tideCache.clear()
                sonarDao.clearSamples();sonarDao.clearSurveys()
                anchorDao.clearEvents();anchorDao.clearPoints();anchorDao.clearSessions()
                importFile(validation.files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importSessions(rows.map(BackupAnchorSessionV1::toEntity).map{if(it.active)it.copy(paused=true,alarmSnoozedUntil=null)else it})}
                importFile(validation.files.getValue(YokuliBackupArchive.POINTS),BackupTrackPointV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importPoints(rows.map(BackupTrackPointV1::toEntity))}
                importFile(validation.files.getValue(YokuliBackupArchive.EVENTS),BackupAlarmEventV1::class.java,YokuliBackupArchive.PAGE){rows->anchorDao.importEvents(rows.map(BackupAlarmEventV1::toEntity))}
                importFile(validation.files.getValue(YokuliBackupArchive.SURVEYS),BackupSonarSurveyV1::class.java,YokuliBackupArchive.PAGE){rows->sonarDao.importSurveys(rows.map(BackupSonarSurveyV1::toEntity).map{if(it.active)it.copy(active=false,endedAt=it.endedAt?:System.currentTimeMillis())else it})}
                importFile(validation.files.getValue(YokuliBackupArchive.SAMPLES),BackupDepthSampleV1::class.java,YokuliBackupArchive.PAGE){rows->sonarDao.importSamples(rows.map(BackupDepthSampleV1::toEntity))}
            }
            val imported=validation.appSettings.copy(
                mockEnabled=false,nmeaSharingEnabled=false,customAlarmSoundUri=null,
                alarmSound=if(validation.appSettings.alarmSound==AlarmSound.CUSTOM)AlarmSound.SYSTEM_ALARM else validation.appSettings.alarmSound,
            )
            val settingsError=runCatching{settingsRepository.save(imported)}.exceptionOrNull()
            val rebuildError=runCatching{gridUpdater.rebuildMissing()}.exceptionOrNull()
            val message=buildString{
                append("Backup data restored. Active watches were restored paused; sonar recording was closed.")
                if(settingsError!=null)append(" Some preferences could not be restored and remain unchanged.")
                if(rebuildError!=null)append(" The derived sonar map will be rebuilt the next time Yokuli starts.")
                append(" Restart Yokuli before use.")
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
                require(entry.name in YokuliBackupArchive.required){"Unexpected backup entry: ${entry.name}"}
                require(found.put(entry.name,File(staging,entry.name.replace('/','_')))==null){"Duplicate backup entry: ${entry.name}"}
                val target=found.getValue(entry.name);FileOutputStream(target).use{out->
                    val buffer=ByteArray(64*1024);var entryBytes=0L
                    while(true){val read=zip.read(buffer);if(read<0)break;entryBytes+=read;archiveBytes+=read;require(entryBytes<=YokuliBackupArchive.MAX_ENTRY_BYTES&&archiveBytes<=YokuliBackupArchive.MAX_ARCHIVE_BYTES){"Backup exceeds safety size limits"};out.write(buffer,0,read)}
                };zip.closeEntry()
            }
        }
        require(found.keys.containsAll(YokuliBackupArchive.required)){"Backup is missing required files: ${YokuliBackupArchive.required-found.keys}"}
        val manifest=gson.fromJson(found.getValue(YokuliBackupArchive.MANIFEST).readText(),BackupManifestV1::class.java)
        require(manifest.format==YokuliBackupArchive.FORMAT){"This is not a Yokuli backup"};require(manifest.formatVersion==YokuliBackupArchive.VERSION){"Unsupported Yokuli backup version ${manifest.formatVersion}"}
        @Suppress("UNCHECKED_CAST") val checksums=gson.fromJson(found.getValue(YokuliBackupArchive.CHECKSUMS).readText(),Map::class.java) as Map<String,String>
        (YokuliBackupArchive.required-YokuliBackupArchive.CHECKSUMS).forEach{name->require(checksums[name]==YokuliBackupArchive.sha256(found.getValue(name))){"Checksum mismatch for $name"}}
        val settings=gson.fromJson(found.getValue(YokuliBackupArchive.SETTINGS).readText(),BackupSettingsV1::class.java);require(settings.schemaVersion==1){"Unsupported settings schema"}
        val appSettings=requireNotNull(gson.fromJson(settings.payload,AppSettings::class.java)){"Backup settings payload is invalid"}
        validateRecords(found,manifest)
        return BackupValidation(manifest,settings,appSettings,found)
    }

    private fun validateRecords(files:Map<String,File>,manifest:BackupManifestV1){
        val sessions=mutableSetOf<Long>();var count=0L
        forEach(files.getValue(YokuliBackupArchive.ANCHORS),BackupAnchorSessionV1::class.java){row->YokuliBackupArchive.validateCoordinate(row.anchorLatitude,row.anchorLongitude);row.learningReferenceLatitude?.let{YokuliBackupArchive.validateCoordinate(it,requireNotNull(row.learningReferenceLongitude))};require(sessions.add(row.id)){"Duplicate anchor session id"};count++}
        requireCount(manifest,YokuliBackupArchive.ANCHORS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.POINTS),BackupTrackPointV1::class.java){row->require(row.sessionId in sessions){"Track point references a missing anchor session"};YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);count++};requireCount(manifest,YokuliBackupArchive.POINTS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.EVENTS),BackupAlarmEventV1::class.java){row->require(row.sessionId in sessions){"Alarm event references a missing anchor session"};count++};requireCount(manifest,YokuliBackupArchive.EVENTS,count)
        val surveys=mutableSetOf<Long>();count=0;forEach(files.getValue(YokuliBackupArchive.SURVEYS),BackupSonarSurveyV1::class.java){row->require(surveys.add(row.id)){"Duplicate sonar survey id"};count++};requireCount(manifest,YokuliBackupArchive.SURVEYS,count)
        count=0;forEach(files.getValue(YokuliBackupArchive.SAMPLES),BackupDepthSampleV1::class.java){row->require(row.surveyId in surveys){"Depth sample references a missing sonar survey"};YokuliBackupArchive.validateCoordinate(row.latitude,row.longitude);require(row.rawDepthMeters.isFinite()&&row.rawDepthMeters>0){"Invalid raw sonar depth"};count++};requireCount(manifest,YokuliBackupArchive.SAMPLES,count)
    }

    private fun requireCount(manifest:BackupManifestV1,name:String,actual:Long){require(manifest.recordCounts[name]==actual){"Record count mismatch for $name"}}
    private fun <T> forEach(file:File,type:Class<T>,action:(T)->Unit){file.bufferedReader().useLines{lines->lines.forEach{line->if(line.isNotBlank()){val envelope=gson.fromJson(line,BackupRecordV1::class.java);require(envelope.schemaVersion==1){"Unsupported record schema"};action(gson.fromJson(envelope.payload,type))}}}}
    private suspend fun <T> importFile(file:File,type:Class<T>,batchSize:Int,insert:suspend(List<T>)->Unit){val batch=ArrayList<T>(batchSize);BufferedReader(InputStreamReader(FileInputStream(file))).use{reader->while(true){val line=reader.readLine()?:break;if(line.isBlank())continue;val envelope=gson.fromJson(line,BackupRecordV1::class.java);batch+=gson.fromJson(envelope.payload,type);if(batch.size==batchSize){insert(batch.toList());batch.clear()}}};if(batch.isNotEmpty())insert(batch)}

    private fun displayName(uri:String?):String?=uri?.let{value->runCatching{context.contentResolver.query(Uri.parse(value),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0)else null}}.getOrNull()}
    private suspend fun writeTextEntry(zip:ZipOutputStream,name:String,text:String):String=writeEntry(zip,name){writer->writer.write(text)}
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
