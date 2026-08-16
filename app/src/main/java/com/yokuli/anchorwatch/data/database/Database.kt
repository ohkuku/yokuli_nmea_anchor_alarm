package com.yokuli.anchorwatch.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
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
    indices = [Index("surveyId"), Index(value = ["surveyId", "timestamp"]), Index(value = ["baseGridX", "baseGridY"])],
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
)

@Dao
interface AnchorDao {
    @Insert suspend fun insertSession(value: AnchorSessionEntity): Long
    @Update suspend fun updateSession(value: AnchorSessionEntity)
    @Query("SELECT * FROM anchor_sessions WHERE active=1 ORDER BY startedAt DESC LIMIT 1") suspend fun active(): AnchorSessionEntity?
    @Query("SELECT * FROM anchor_sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<AnchorSessionEntity>>
    @Query("DELETE FROM alarm_events WHERE sessionId IN (SELECT id FROM anchor_sessions WHERE id=:id AND active=0)") suspend fun deleteCompletedEvents(id: Long)
    @Query("DELETE FROM track_points WHERE sessionId IN (SELECT id FROM anchor_sessions WHERE id=:id AND active=0)") suspend fun deleteCompletedPoints(id: Long)
    @Query("DELETE FROM anchor_sessions WHERE id=:id AND active=0") suspend fun deleteCompletedSessionRow(id: Long): Int
    @Transaction suspend fun deleteCompletedSession(id:Long):Int{deleteCompletedEvents(id);deleteCompletedPoints(id);return deleteCompletedSessionRow(id)}
    @Insert suspend fun insertPoint(value: TrackPointEntity)
    @Query("SELECT * FROM track_points WHERE sessionId=:id ORDER BY timestamp") fun points(id: Long): Flow<List<TrackPointEntity>>
    @Insert suspend fun insertEvent(value: AlarmEventEntity)
    @Query("SELECT * FROM alarm_events WHERE sessionId=:id ORDER BY timestamp") fun events(id: Long): Flow<List<AlarmEventEntity>>
    @Query("SELECT * FROM alarm_events ORDER BY timestamp DESC") fun allEvents(): Flow<List<AlarmEventEntity>>
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
    @Insert suspend fun insertSample(value: DepthSampleEntity)
    @Update suspend fun updateSamples(values: List<DepthSampleEntity>)
    @Query("UPDATE depth_samples SET disposition='ACCEPTED_STEEP_SLOPE', usable=1, integrityReason='Released by coherent three-point slope' WHERE surveyId=:surveyId AND sourceElapsedRealtime IN (:elapsedTimestamps)") suspend fun releaseSlopeSamples(surveyId:Long,elapsedTimestamps:List<Long>)
    @Query("UPDATE sonar_surveys SET name=:name WHERE id=:surveyId") suspend fun rename(surveyId:Long,name:String)
    @Query("UPDATE sonar_surveys SET active=0, endedAt=:endedAt WHERE id=:surveyId") suspend fun finish(surveyId:Long,endedAt:Long)
    @Query("UPDATE sonar_surveys SET sampleCount=(SELECT COUNT(*) FROM depth_samples WHERE surveyId=:surveyId) WHERE id=:surveyId") suspend fun refreshSampleCount(surveyId:Long)
    @Query("DELETE FROM sonar_surveys WHERE id=:surveyId AND active=0") suspend fun deleteCompleted(surveyId:Long):Int
}

@Database(
    entities = [AnchorSessionEntity::class, TrackPointEntity::class, AlarmEventEntity::class,SonarSurveyEntity::class,DepthSampleEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun anchorDao(): AnchorDao
    abstract fun sonarDao(): SonarDao
}
