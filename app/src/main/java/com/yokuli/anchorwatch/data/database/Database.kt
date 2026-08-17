package com.yokuli.anchorwatch.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "anchor_sessions")
data class AnchorSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val anchorLatitude: Double,
    val anchorLongitude: Double,
    val rodeLengthMeters: Double,
    val waterDepthMeters: Double?,
    val bowRollerHeightMeters: Double,
    val gpsAntennaOffsetMeters: Double,
    val expectedSwingRadiusMeters: Double,
    val warningRadiusMeters: Double,
    val alarmRadiusMeters: Double,
    val active: Boolean = true,
    @ColumnInfo(defaultValue = "0") val paused: Boolean = false,
    @ColumnInfo(defaultValue = "'CENTER_DROP'") val placementMode: String = "CENTER_DROP",
    @ColumnInfo(defaultValue = "'RESOLVED'") val centerStatus: String = "RESOLVED",
    val centerResolvedAt: Long? = null,
    @ColumnInfo(defaultValue = "'HIGH'") val centerConfidence: String = "HIGH",
    @ColumnInfo(defaultValue = "0") val centerSampleCount: Int = 0,
    val boatLengthMeters: Double? = null,
    @ColumnInfo(defaultValue = "'BASIC'") val rangeMode: String = "BASIC",
    @ColumnInfo(defaultValue = "'BALANCED'") val safetyPreset: String = "BALANCED",
    val alarmSnoozedUntil: Long? = null,
    val learningReferenceLatitude: Double? = null,
    val learningReferenceLongitude: Double? = null,
    val provisionalAnchorLatitude: Double? = null,
    val provisionalAnchorLongitude: Double? = null,
    val provisionalRadiusMeters: Double? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val positionSource: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'KNOWN'") val anchorPositionMode: String = "KNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val centerSource: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "0") val usePhoneHeading: Boolean = false,
    val candidateId: Long? = null,
    val candidateCreatedAt: Long? = null,
    @ColumnInfo(defaultValue = "'NONE'") val candidateDecision: String = "NONE",
    @ColumnInfo(defaultValue = "0") val candidateNotificationShown: Boolean = false,
    val candidateRmsErrorMeters: Double? = null,
    val candidateAngularCoverageDegrees: Double? = null,
    @ColumnInfo(defaultValue = "0") val candidateAngularSectorCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val candidateSwingReversalCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val candidateTemporalFitConsistent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val candidateEffectiveDurationMillis: Long = 0L,
    @ColumnInfo(defaultValue = "0") val candidateDirectionEvidenceConsistent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val maxDistanceMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val alarmCount: Int = 0,
    /** Explicit setup provenance only; never used as the authoritative anchor coordinate. */
    val savedAnchorageId:Long?=null,
    @ColumnInfo(defaultValue = "0") val depthGuardEnabled:Boolean=false,
    val shallowDepthAlarmMeters:Double?=null,
    val deepDepthAlarmMeters:Double?=null,
    @ColumnInfo(defaultValue = "0") val windGuardEnabled:Boolean=false,
    val windWarningKnots:Double?=null,
    val windAlarmKnots:Double?=null,
    @ColumnInfo(defaultValue = "0") val windShiftEnabled:Boolean=false,
    val windShiftThresholdDegrees:Double?=null,
    @ColumnInfo(defaultValue = "1") val windAllowApparentFallback:Boolean=true,
    val windBaselineDirectionDegrees:Double?=null,
    val windBaselineEstablishedAt:Long?=null,
    val windBaselineSource:String?=null,
    val depthAlarmSnoozedUntil:Long?=null,
    val windAlarmSnoozedUntil:Long?=null,
    val windShiftAlarmSnoozedUntil:Long?=null,
    val minObservedDepthMeters:Double?=null,
    val maxObservedDepthMeters:Double?=null,
    val maxObservedWindKnots:Double?=null,
    val maxObservedWindSource:String?=null,
    @ColumnInfo(defaultValue = "0") val depthAlarmCount:Int=0,
    @ColumnInfo(defaultValue = "0") val windAlarmCount:Int=0,
)

@Entity(tableName="saved_anchorages",indices=[Index("updatedAt"),Index("lastVisitedAt")])
data class SavedAnchorageEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val latitude:Double,
    val longitude:Double,
    val createdAt:Long,
    val updatedAt:Long,
    val lastVisitedAt:Long?=null,
    @ColumnInfo(defaultValue="0") val visitCount:Int=0,
    val preferredAlarmRadiusMeters:Double?=null,
    val typicalWaterDepthMeters:Double?=null,
    val typicalRodeLengthMeters:Double?=null,
    @ColumnInfo(defaultValue="'UNKNOWN'") val seabedType:String="UNKNOWN",
    val customSeabedText:String?=null,
    val rating:Int?=null,
    val notes:String="",
    val sourceSessionId:Long?=null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = AnchorSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val distanceFromAnchor: Double,
    val sog: Double?,
    val cog: Double?,
    val heading: Double?,
    val hdop: Double?,
    val windDirectionTrue: Double? = null,
    val windSpeedKnots: Double? = null,
    val apparentWindAngle: Double? = null,
    val trueWindAngle: Double? = null,
    val trueWindSpeedKnots: Double? = null,
    val apparentWindSpeedKnots: Double? = null,
    @ColumnInfo(defaultValue = "0") val headingMeasured: Boolean = false,
    val headingSampleSequence: Long? = null,
    val windSampleSequence: Long? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val positionSource: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val positionProvider: String = "UNKNOWN",
    val horizontalAccuracyMeters: Double? = null,
    @ColumnInfo(defaultValue = "'TRUSTED'") val fixTrust: String = "TRUSTED",
    @ColumnInfo(defaultValue = "0") val wasQuarantined: Boolean = false,
    val quarantineReason: String? = null,
    @ColumnInfo(defaultValue = "'NONE'") val headingSource: String = "NONE",
    @ColumnInfo(defaultValue = "'UNAVAILABLE'") val headingQuality: String = "UNAVAILABLE",
    val headingEpoch: Long? = null,
)

@Entity(tableName = "alarm_events", indices = [Index("sessionId")])
data class AlarmEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val type: String,
    val detail: String = "",
)

@Entity(tableName = "sonar_surveys")
data class SonarSurveyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val active: Boolean = true,
    @ColumnInfo(defaultValue = "'OFF'") val tideMode: String = "OFF",
    @ColumnInfo(defaultValue = "0") val manualTideOffsetMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val transducerDraftMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val keelOffsetMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val gpsToTransducerMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val configuredDepthReference: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "0") val sounderOffsetMeters: Double = 0.0,
    val tideStationId: String? = null,
    val tideStationName: String? = null,
    val tideStationDistanceMeters: Double? = null,
    @ColumnInfo(defaultValue = "0") val sampleCount: Int = 0,
)

@Entity(
    tableName = "depth_samples",
    foreignKeys = [ForeignKey(
        entity = SonarSurveyEntity::class,
        parentColumns = ["id"],
        childColumns = ["surveyId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("surveyId"), Index(value = ["surveyId", "timestamp"]), Index(value = ["baseGridX", "baseGridY"]), Index(value = ["surveyId", "baseGridX", "baseGridY"]), Index(value=["surveyId","sourceElapsedRealtime"])],
)
data class DepthSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surveyId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val baseGridX: Long,
    val baseGridY: Long,
    val sourceElapsedRealtime: Long,
    val rawDepthMeters: Double,
    val measuredDepthMeters: Double,
    val normalizedDepthMeters: Double? = null,
    val depthReference: String,
    val sentenceType: String,
    val nmeaOffsetMeters: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val gpsSource: String,
    val positionProvider: String,
    val hdop: Double? = null,
    val sogKnots: Double? = null,
    @ColumnInfo(defaultValue = "'DEGRADED'") val fixTrust: String = "DEGRADED",
    val positionAgeMillis: Long,
    @ColumnInfo(defaultValue = "'ACCEPTED'") val disposition: String = "ACCEPTED",
    @ColumnInfo(defaultValue = "1") val usable: Boolean = true,
    val integrityReason: String? = null,
    @ColumnInfo(defaultValue = "0") val positionCorrectionApplied: Boolean = false,
    @ColumnInfo(defaultValue = "'NONE'") val positionCorrectionMethod: String = "NONE",
    val tideHeightMetersApplied: Double? = null,
    @ColumnInfo(defaultValue = "'OFF'") val tideCorrectionMode: String = "OFF",
    val tideStationId: String? = null,
    val tideStationName: String? = null,
    val tideStationDistanceMeters: Double? = null,
    val tidePredictionYear: Int? = null,
    val tideCorrectionMethod: String? = null,
    val tideSource: String? = null,
    val tideSourceUpdatedAt: Long? = null,
    @ColumnInfo(defaultValue = "'NOT_REQUESTED'") val tideCorrectionStatus: String = "NOT_REQUESTED",
)

@Entity(tableName="tide_prediction_cache",primaryKeys=["stationId","year"])
data class TidePredictionCacheEntity(
    val stationId:String,
    val year:Int,
    val downloadedAt:Long,
    val sourceUrl:String,
    val csv:String,
)

@Entity(
    tableName = "sonar_grid_cells",
    primaryKeys = ["scopeType", "scopeId", "gridX", "gridY"],
    indices = [Index(value = ["scopeType", "scopeId"]), Index(value = ["gridX", "gridY"])],
)
data class SonarGridCellEntity(
    val scopeType: String,
    val scopeId: Long,
    val gridX: Long,
    val gridY: Long,
    val cellSizeMeters: Double = 5.0,
    val depthMeters: Double,
    val uncertaintyMeters: Double,
    val sampleCount: Int,
    val lastUpdatedAt: Long,
)

@Entity(tableName = "linz_depth_cache")
data class LinzDepthCacheEntity(
    @PrimaryKey val cellKey: String,
    val queriedLatitude: Double,
    val queriedLongitude: Double,
    val queriedAt: Long,
    val depthAreaMinMeters: Double? = null,
    val depthAreaMaxMeters: Double? = null,
    val nearestSoundingDepthMeters: Double? = null,
    val nearestSoundingDistanceMeters: Double? = null,
    val nearestSoundingLatitude: Double? = null,
    val nearestSoundingLongitude: Double? = null,
    val nearestContourDepthMeters: Double? = null,
    val nearestContourDistanceMeters: Double? = null,
    val sourceLayers: String = "",
    val status: String,
)

/** Bounded safety log. Never store coordinates, credentials or raw NMEA here. */
@Entity(
    tableName = "incident_log",
    indices = [Index("timestamp"), Index("category"), Index("severity")],
)
data class IncidentLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val elapsedRealtime: Long,
    val severity: String,
    val category: String,
    val event: String,
    val sessionId: Long? = null,
    val details: String = "{}",
)

@Dao
interface AnchorDao {
    @Insert suspend fun insertSession(value: AnchorSessionEntity): Long
    @Update suspend fun updateSession(value: AnchorSessionEntity)
    @Transaction suspend fun updateSessionAndInsertEvent(value:AnchorSessionEntity,event:AlarmEventEntity){updateSession(value);insertEvent(event)}
    @Query("SELECT * FROM anchor_sessions WHERE active=1 ORDER BY startedAt DESC LIMIT 1") suspend fun active(): AnchorSessionEntity?
    @Query("SELECT * FROM anchor_sessions WHERE id=:id LIMIT 1") suspend fun session(id:Long):AnchorSessionEntity?
    @Query("SELECT * FROM anchor_sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<AnchorSessionEntity>>
    @Query("DELETE FROM alarm_events WHERE sessionId IN (SELECT id FROM anchor_sessions WHERE id=:id AND active=0)") suspend fun deleteCompletedEvents(id: Long)
    @Query("DELETE FROM track_points WHERE sessionId IN (SELECT id FROM anchor_sessions WHERE id=:id AND active=0)") suspend fun deleteCompletedPoints(id: Long)
    @Query("DELETE FROM anchor_sessions WHERE id=:id AND active=0") suspend fun deleteCompletedSessionRow(id: Long): Int
    @Transaction suspend fun deleteCompletedSession(id:Long):Int{deleteCompletedEvents(id);deleteCompletedPoints(id);return deleteCompletedSessionRow(id)}
    @Insert suspend fun insertPoint(value: TrackPointEntity)
    @Query("SELECT * FROM track_points WHERE sessionId=:id ORDER BY timestamp") fun points(id: Long): Flow<List<TrackPointEntity>>
    @Query("SELECT * FROM (SELECT * FROM track_points WHERE sessionId=:id ORDER BY timestamp DESC,id DESC LIMIT :limit) ORDER BY timestamp,id") fun recentPoints(id:Long,limit:Int):Flow<List<TrackPointEntity>>
    @Insert suspend fun insertEvent(value: AlarmEventEntity)
    @Query("SELECT * FROM alarm_events WHERE sessionId=:id ORDER BY timestamp") fun events(id: Long): Flow<List<AlarmEventEntity>>
    @Query("SELECT * FROM alarm_events WHERE sessionId=:id ORDER BY timestamp DESC,id DESC LIMIT :limit") suspend fun recentEvents(id:Long,limit:Int):List<AlarmEventEntity>
    @Query("SELECT * FROM anchor_sessions ORDER BY id") suspend fun allSessionsNow():List<AnchorSessionEntity>
    @Query("SELECT * FROM track_points WHERE id>:afterId ORDER BY id LIMIT :limit") suspend fun allPointsPage(afterId:Long,limit:Int):List<TrackPointEntity>
    @Query("SELECT * FROM track_points WHERE id>:afterId AND id<=:throughId ORDER BY id LIMIT :limit") suspend fun allPointsPageThrough(afterId:Long,throughId:Long,limit:Int):List<TrackPointEntity>
    @Query("SELECT * FROM alarm_events WHERE id>:afterId ORDER BY id LIMIT :limit") suspend fun allEventsPage(afterId:Long,limit:Int):List<AlarmEventEntity>
    @Query("SELECT * FROM alarm_events WHERE id>:afterId AND id<=:throughId ORDER BY id LIMIT :limit") suspend fun allEventsPageThrough(afterId:Long,throughId:Long,limit:Int):List<AlarmEventEntity>
    @Query("SELECT COALESCE(MAX(id),0) FROM track_points") suspend fun maxPointId():Long
    @Query("SELECT COALESCE(MAX(id),0) FROM alarm_events") suspend fun maxEventId():Long
    @Query("SELECT COUNT(*) FROM anchor_sessions") suspend fun sessionCount():Long
    @Query("SELECT COUNT(*) FROM track_points") suspend fun pointCount():Long
    @Query("SELECT COUNT(*) FROM alarm_events") suspend fun eventCount():Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importSessions(values:List<AnchorSessionEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importPoints(values:List<TrackPointEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importEvents(values:List<AlarmEventEntity>)
    @Query("DELETE FROM alarm_events") suspend fun clearEvents()
    @Query("DELETE FROM track_points") suspend fun clearPoints()
    @Query("DELETE FROM anchor_sessions") suspend fun clearSessions()
}

@Dao
interface AnchorageDao{
    @Query("SELECT * FROM saved_anchorages ORDER BY COALESCE(lastVisitedAt,updatedAt) DESC") fun anchorages():Flow<List<SavedAnchorageEntity>>
    @Query("SELECT * FROM saved_anchorages WHERE id=:id LIMIT 1") suspend fun get(id:Long):SavedAnchorageEntity?
    @Insert suspend fun insert(value:SavedAnchorageEntity):Long
    @Update suspend fun update(value:SavedAnchorageEntity)
    @Query("DELETE FROM saved_anchorages WHERE id=:id") suspend fun delete(id:Long):Int
    @Query("SELECT * FROM saved_anchorages ORDER BY id") suspend fun allNow():List<SavedAnchorageEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<SavedAnchorageEntity>)
    @Query("DELETE FROM saved_anchorages") suspend fun clear()
}

@Dao
interface SonarDao {
    @Insert suspend fun insertSurvey(value: SonarSurveyEntity): Long
    @Update suspend fun updateSurvey(value: SonarSurveyEntity)
    @Query("SELECT * FROM sonar_surveys WHERE active=1 ORDER BY startedAt DESC LIMIT 1") suspend fun active(): SonarSurveyEntity?
    @Query("SELECT * FROM sonar_surveys WHERE id=:surveyId LIMIT 1") suspend fun survey(surveyId:Long): SonarSurveyEntity?
    @Query("SELECT * FROM sonar_surveys ORDER BY startedAt DESC") fun surveys(): Flow<List<SonarSurveyEntity>>
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId ORDER BY timestamp") fun samples(surveyId:Long): Flow<List<DepthSampleEntity>>
    @Query("SELECT * FROM depth_samples WHERE normalizedDepthMeters IS NOT NULL AND usable=1 ORDER BY timestamp") fun normalizedHistory(): Flow<List<DepthSampleEntity>>
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId AND usable=1 ORDER BY timestamp") suspend fun usableSamples(surveyId:Long): List<DepthSampleEntity>
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId ORDER BY timestamp") suspend fun samplesNow(surveyId:Long): List<DepthSampleEntity>
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId AND (timestamp>:afterTimestamp OR (timestamp=:afterTimestamp AND id>:afterId)) ORDER BY timestamp,id LIMIT :limit") suspend fun samplesPage(surveyId:Long,afterTimestamp:Long,afterId:Long,limit:Int):List<DepthSampleEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM depth_samples WHERE surveyId=:surveyId LIMIT 1)") suspend fun hasSamples(surveyId:Long):Boolean
    @Insert suspend fun insertSample(value: DepthSampleEntity):Long
    @Update suspend fun updateSamples(values: List<DepthSampleEntity>)
    @Query("UPDATE depth_samples SET disposition='ACCEPTED_STEEP_SLOPE', usable=1, integrityReason='Released by coherent three-point slope' WHERE surveyId=:surveyId AND sourceElapsedRealtime IN (:elapsedTimestamps)") suspend fun releaseSlopeSamples(surveyId:Long,elapsedTimestamps:List<Long>)
    @Query("UPDATE sonar_surveys SET name=:name WHERE id=:surveyId") suspend fun rename(surveyId:Long,name:String)
    @Query("UPDATE sonar_surveys SET active=0, endedAt=:endedAt WHERE id=:surveyId") suspend fun finish(surveyId:Long,endedAt:Long)
    @Query("UPDATE sonar_surveys SET sampleCount=(SELECT COUNT(*) FROM depth_samples WHERE surveyId=:surveyId) WHERE id=:surveyId") suspend fun refreshSampleCount(surveyId:Long)
    @Query("UPDATE sonar_surveys SET sampleCount=sampleCount+1 WHERE id=:surveyId") suspend fun incrementSampleCount(surveyId:Long)
    @Transaction suspend fun insertSampleAndIncrement(value:DepthSampleEntity):Long{val id=insertSample(value);incrementSampleCount(value.surveyId);return id}
    @Query("DELETE FROM sonar_surveys WHERE id=:surveyId AND active=0") suspend fun deleteCompleted(surveyId:Long):Int
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId AND baseGridX=:gridX AND baseGridY=:gridY AND usable=1") suspend fun usableSamplesInCell(surveyId:Long,gridX:Long,gridY:Long):List<DepthSampleEntity>
    @Query("SELECT * FROM depth_samples WHERE normalizedDepthMeters IS NOT NULL AND usable=1 AND baseGridX=:gridX AND baseGridY=:gridY") suspend fun correctedSamplesInCell(gridX:Long,gridY:Long):List<DepthSampleEntity>
    @Query("SELECT DISTINCT baseGridX,baseGridY FROM depth_samples WHERE surveyId=:surveyId AND normalizedDepthMeters IS NOT NULL AND usable=1") suspend fun correctedCellsForSurvey(surveyId:Long):List<GridCoordinate>
    @Query("SELECT DISTINCT baseGridX,baseGridY FROM depth_samples WHERE surveyId=:surveyId AND usable=1") suspend fun usableCellsForSurvey(surveyId:Long):List<GridCoordinate>
    @Query("SELECT DISTINCT baseGridX,baseGridY FROM depth_samples WHERE normalizedDepthMeters IS NOT NULL AND usable=1") suspend fun allCorrectedCells():List<GridCoordinate>
    @Query("SELECT COUNT(*) FROM depth_samples WHERE normalizedDepthMeters IS NOT NULL AND usable=1") suspend fun correctedSampleCount():Long
    @Query("SELECT * FROM depth_samples WHERE surveyId=:surveyId AND sourceElapsedRealtime IN (:elapsedTimestamps)") suspend fun samplesByElapsed(surveyId:Long,elapsedTimestamps:List<Long>):List<DepthSampleEntity>
    @Query("SELECT COUNT(*) FROM depth_samples") suspend fun rawSampleCount():Long
    @Query("SELECT COUNT(*) FROM sonar_grid_cells") suspend fun gridCellCount():Long
    @Query("SELECT * FROM sonar_grid_cells WHERE scopeType=:scopeType AND scopeId=:scopeId") suspend fun gridCellsNow(scopeType:String,scopeId:Long):List<SonarGridCellEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM sonar_grid_cells WHERE scopeType=:scopeType AND scopeId=:scopeId LIMIT 1)") suspend fun hasGridCells(scopeType:String,scopeId:Long):Boolean
    @Upsert suspend fun upsertGridCell(value:SonarGridCellEntity)
    @Query("DELETE FROM sonar_grid_cells WHERE scopeType=:scopeType AND scopeId=:scopeId AND gridX=:gridX AND gridY=:gridY") suspend fun deleteGridCell(scopeType:String,scopeId:Long,gridX:Long,gridY:Long)
    @Query("DELETE FROM sonar_grid_cells WHERE scopeType=:scopeType AND scopeId=:scopeId") suspend fun deleteGridScope(scopeType:String,scopeId:Long)
    @Query("SELECT * FROM sonar_surveys ORDER BY id") suspend fun allSurveysNow():List<SonarSurveyEntity>
    @Query("SELECT * FROM depth_samples WHERE id>:afterId ORDER BY id LIMIT :limit") suspend fun allSamplesPage(afterId:Long,limit:Int):List<DepthSampleEntity>
    @Query("SELECT * FROM depth_samples WHERE id>:afterId AND id<=:throughId ORDER BY id LIMIT :limit") suspend fun allSamplesPageThrough(afterId:Long,throughId:Long,limit:Int):List<DepthSampleEntity>
    @Query("SELECT COALESCE(MAX(id),0) FROM depth_samples") suspend fun maxSampleId():Long
    @Query("SELECT COUNT(*) FROM sonar_surveys") suspend fun surveyCount():Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importSurveys(values:List<SonarSurveyEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importSamples(values:List<DepthSampleEntity>)
    @Query("DELETE FROM sonar_grid_cells") suspend fun clearGridCells()
    @Query("DELETE FROM depth_samples") suspend fun clearSamples()
    @Query("DELETE FROM sonar_surveys") suspend fun clearSurveys()
}

data class GridCoordinate(val baseGridX:Long,val baseGridY:Long)

@Dao
interface LinzDepthCacheDao {
    @Query("SELECT * FROM linz_depth_cache WHERE cellKey=:cellKey LIMIT 1") suspend fun get(cellKey:String):LinzDepthCacheEntity?
    @Upsert suspend fun upsert(value:LinzDepthCacheEntity)
    @Query("DELETE FROM linz_depth_cache WHERE queriedAt<:oldestAllowed") suspend fun prune(oldestAllowed:Long)
    @Query("DELETE FROM linz_depth_cache") suspend fun clear()
}

@Dao
interface TidePredictionCacheDao{
    @Query("SELECT * FROM tide_prediction_cache WHERE stationId=:stationId AND year=:year LIMIT 1") suspend fun get(stationId:String,year:Int):TidePredictionCacheEntity?
    @Upsert suspend fun upsert(value:TidePredictionCacheEntity)
    @Query("DELETE FROM tide_prediction_cache") suspend fun clear()
}

@Dao
interface IncidentLogDao {
    @Insert suspend fun insert(value: IncidentLogEntity): Long
    @Query("SELECT * FROM incident_log ORDER BY timestamp DESC, id DESC LIMIT :limit") fun recent(limit: Int = 500): Flow<List<IncidentLogEntity>>
    @Query("SELECT * FROM incident_log WHERE timestamp>=:since ORDER BY timestamp, id LIMIT :limit") suspend fun since(since: Long, limit: Int = 10_000): List<IncidentLogEntity>
    @Query("SELECT COUNT(*) FROM incident_log") suspend fun count(): Long
    @Query("DELETE FROM incident_log WHERE timestamp<:oldestAllowed") suspend fun deleteOlderThan(oldestAllowed: Long): Int
    @Query("DELETE FROM incident_log WHERE id NOT IN (SELECT id FROM incident_log ORDER BY id DESC LIMIT :maxRows)") suspend fun trimToRows(maxRows: Int): Int
    @Query("DELETE FROM incident_log") suspend fun clear(): Int
}

@Database(
    entities = [AnchorSessionEntity::class,SavedAnchorageEntity::class,TrackPointEntity::class,AlarmEventEntity::class,SonarSurveyEntity::class,DepthSampleEntity::class,SonarGridCellEntity::class,LinzDepthCacheEntity::class,TidePredictionCacheEntity::class,IncidentLogEntity::class],
    version = 12,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun anchorDao(): AnchorDao
    abstract fun anchorageDao():AnchorageDao
    abstract fun sonarDao(): SonarDao
    abstract fun linzDepthCacheDao():LinzDepthCacheDao
    abstract fun tidePredictionCacheDao():TidePredictionCacheDao
    abstract fun incidentLogDao():IncidentLogDao
}
