package com.yokuli.anchorwatch.data.database
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="anchor_sessions") data class AnchorSessionEntity(
 @PrimaryKey(autoGenerate=true)val id:Long=0,
 val startedAt:Long,
 val endedAt:Long?=null,
 val anchorLatitude:Double,
 val anchorLongitude:Double,
 val rodeLengthMeters:Double,
 val waterDepthMeters:Double?,
 val bowRollerHeightMeters:Double,
 val gpsAntennaOffsetMeters:Double,
 val expectedSwingRadiusMeters:Double,
 val warningRadiusMeters:Double,
 val alarmRadiusMeters:Double,
 val active:Boolean=true,
 @ColumnInfo(defaultValue="0")val paused:Boolean=false,
 @ColumnInfo(defaultValue="'CENTER_DROP'")val placementMode:String="CENTER_DROP",
 @ColumnInfo(defaultValue="'RESOLVED'")val centerStatus:String="RESOLVED",
 val centerResolvedAt:Long?=null,
 @ColumnInfo(defaultValue="'HIGH'")val centerConfidence:String="HIGH",
 @ColumnInfo(defaultValue="0")val centerSampleCount:Int=0,
 val boatLengthMeters:Double?=null,
 @ColumnInfo(defaultValue="'BASIC'")val rangeMode:String="BASIC",
 @ColumnInfo(defaultValue="'BALANCED'")val safetyPreset:String="BALANCED",
)
@Entity(tableName="track_points",foreignKeys=[ForeignKey(entity=AnchorSessionEntity::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionId")]) data class TrackPointEntity(@PrimaryKey(autoGenerate=true)val id:Long=0,val sessionId:Long,val timestamp:Long,val latitude:Double,val longitude:Double,val distanceFromAnchor:Double,val sog:Double?,val cog:Double?,val heading:Double?,val hdop:Double?)
@Entity(tableName="alarm_events",indices=[Index("sessionId")]) data class AlarmEventEntity(@PrimaryKey(autoGenerate=true)val id:Long=0,val sessionId:Long,val timestamp:Long,val type:String,val detail:String="")
@Dao interface AnchorDao {
 @Insert suspend fun insertSession(v:AnchorSessionEntity):Long
 @Update suspend fun updateSession(v:AnchorSessionEntity)
 @Query("SELECT * FROM anchor_sessions WHERE active=1 ORDER BY startedAt DESC LIMIT 1") suspend fun active():AnchorSessionEntity?
 @Query("SELECT * FROM anchor_sessions ORDER BY startedAt DESC") fun sessions():Flow<List<AnchorSessionEntity>>
 @Insert suspend fun insertPoint(v:TrackPointEntity)
 @Query("SELECT * FROM track_points WHERE sessionId=:id ORDER BY timestamp") fun points(id:Long):Flow<List<TrackPointEntity>>
 @Insert suspend fun insertEvent(v:AlarmEventEntity)
 @Query("SELECT * FROM alarm_events WHERE sessionId=:id ORDER BY timestamp") fun events(id:Long):Flow<List<AlarmEventEntity>>
}
@Database(entities=[AnchorSessionEntity::class,TrackPointEntity::class,AlarmEventEntity::class],version=2,exportSchema=false) abstract class AppDatabase:RoomDatabase(){abstract fun anchorDao():AnchorDao}
