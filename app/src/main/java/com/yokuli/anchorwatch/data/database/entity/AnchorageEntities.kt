package com.yokuli.anchorwatch.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.google.gson.annotations.SerializedName

@Entity(
    tableName="anchorage_regions",
    foreignKeys=[ForeignKey(entity=AnchorageRegionEntity::class,parentColumns=["id"],childColumns=["parentRegionId"],onDelete=ForeignKey.SET_NULL)],
    indices=[Index("parentRegionId"),Index(value=["provider","externalId"],unique=true),Index("featureType"),Index("updatedAt")],
)
data class AnchorageRegionEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val parentRegionId:Long?=null,
    val displayName:String,
    val officialName:String?=null,
    val alternateNamesJson:String="[]",
    val provider:String,
    val externalId:String?=null,
    val featureType:String,
    val geometryType:String,
    val geometryGeoJson:String?=null,
    val centerLatitude:Double,
    val centerLongitude:Double,
    val bboxMinLatitude:Double,
    val bboxMaxLatitude:Double,
    val bboxMinLongitude:Double,
    val bboxMaxLongitude:Double,
    val official:Boolean=false,
    val userConfirmed:Boolean=false,
    val custom:Boolean=false,
    val sourceUpdatedAt:Long?=null,
    val lastResolvedAt:Long?=null,
    val createdAt:Long,
    val updatedAt:Long,
)

@Entity(
    tableName="anchorage_places",
    foreignKeys=[ForeignKey(entity=AnchorageRegionEntity::class,parentColumns=["id"],childColumns=["primaryRegionId"],onDelete=ForeignKey.SET_NULL)],
    indices=[Index("primaryRegionId"),Index("updatedAt"),Index("lastVisitedAt"),Index("planningStatus"),Index("favorite"),Index(value=["legacySavedAnchorageId"],unique=true)],
)
data class AnchoragePlaceEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val primaryRegionId:Long?=null,
    val displayName:String,
    val officialName:String?=null,
    val aliasesJson:String="[]",
    val placeType:String,
    val geometryType:String,
    val geometryGeoJson:String?=null,
    val centerLatitude:Double,
    val centerLongitude:Double,
    val bboxMinLatitude:Double,
    val bboxMaxLatitude:Double,
    val bboxMinLongitude:Double,
    val bboxMaxLongitude:Double,
    val description:String="",
    val personalNotes:String="",
    val verificationStatus:String,
    val planningStatus:String="NONE",
    val favorite:Boolean=false,
    val archived:Boolean=false,
    val visitCountCached:Int=0,
    val legacyVisitCount:Int=0,
    val lastVisitedAt:Long?=null,
    val legacySavedAnchorageId:Long?=null,
    val createdAt:Long,
    val updatedAt:Long,
)

@Entity(
    tableName="anchorage_place_regions",
    primaryKeys=["placeId","regionId"],
    foreignKeys=[
        ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=AnchorageRegionEntity::class,parentColumns=["id"],childColumns=["regionId"],onDelete=ForeignKey.CASCADE),
    ],
    indices=[Index("regionId"),Index(value=["placeId","sortOrder"])],
)
data class AnchoragePlaceRegionCrossRef(val placeId:Long,val regionId:Long,val relationType:String,val sortOrder:Int)

@Entity(
    tableName="anchorage_spots",
    foreignKeys=[ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("placeId"),Index(value=["placeId","lastVisitedAt"]),Index(value=["legacySavedAnchorageId"],unique=true),Index("updatedAt")],
)
data class AnchorageSpotEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val placeId:Long,
    val name:String,
    val spotType:String,
    val latitude:Double,
    val longitude:Double,
    val coordinateSource:String,
    val coordinateUncertaintyMeters:Double?=null,
    val preferredAlarmRadiusMeters:Double?=null,
    val typicalWaterDepthMeters:Double?=null,
    val typicalRodeLengthMeters:Double?=null,
    val seabedType:String="UNKNOWN",
    val customSeabedText:String?=null,
    val approachNotes:String="",
    val personalNotes:String="",
    val verificationStatus:String,
    val visitCountCached:Int=0,
    val legacyVisitCount:Int=0,
    val lastVisitedAt:Long?=null,
    val legacySavedAnchorageId:Long?=null,
    val createdAt:Long,
    val updatedAt:Long,
)

@Entity(
    tableName="anchorage_visits",
    foreignKeys=[
        ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=AnchorageSpotEntity::class,parentColumns=["id"],childColumns=["spotId"],onDelete=ForeignKey.SET_NULL),
        ForeignKey(entity=AnchorSessionEntity::class,parentColumns=["id"],childColumns=["anchorSessionId"],onDelete=ForeignKey.SET_NULL),
    ],
    indices=[Index("placeId"),Index("spotId"),Index(value=["anchorSessionId"],unique=true),Index("startedAt")],
)
data class AnchorageVisitEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val placeId:Long,
    val spotId:Long?,
    val anchorSessionId:Long?,
    val visitKind:String,
    val startedAt:Long,
    val endedAt:Long?,
    val actualAnchorLatitude:Double?,
    val actualAnchorLongitude:Double?,
    val coordinateSource:String?,
    val coordinateUncertaintyMeters:Double?,
    val waterDepthMeters:Double?,
    val rodeLengthMeters:Double?,
    val alarmRadiusMeters:Double?,
    val maxExcursionMeters:Double?,
    val alarmCount:Int,
    val minDepthMeters:Double?,
    val maxDepthMeters:Double?,
    val maxWindKnots:Double?,
    val maxWindSource:String?,
    val typicalMotionScore:Double?,
    val p95MotionScore:Double?,
    val p95AbsoluteHeelDegrees:Double?,
    val dominantRollPeriodSeconds:Double?,
    val impactCount:Int?,
    val userNotes:String="",
    val summaryVersion:String,
    val createdAt:Long,
)

@Entity(tableName="anchorage_collections",indices=[Index("sortOrder"),Index("updatedAt")])
data class AnchorageCollectionEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val description:String="",val icon:String?=null,val sortOrder:Int,val createdAt:Long,val updatedAt:Long)

@Entity(
    tableName="anchorage_collection_places",
    primaryKeys=["collectionId","placeId"],
    foreignKeys=[
        ForeignKey(entity=AnchorageCollectionEntity::class,parentColumns=["id"],childColumns=["collectionId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE),
    ],indices=[Index("placeId")],
)
data class AnchorageCollectionPlaceCrossRef(val collectionId:Long,val placeId:Long,val addedAt:Long)

@Entity(
    tableName="anchorage_protection_sectors",primaryKeys=["placeId","medium","sector"],
    foreignKeys=[ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],
)
data class AnchorageProtectionSectorEntity(val placeId:Long,val medium:String,val sector:String,val rating:String,val source:String,val confidence:Double?=null,val notes:String="",val updatedAt:Long)

@Entity(
    tableName="anchorage_facilities",primaryKeys=["placeId","type"],
    foreignKeys=[ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],
)
data class AnchorageFacilityEntity(val placeId:Long,val type:String,val availability:String,val source:String,val notes:String="",val updatedAt:Long)

@Entity(
    tableName="anchorage_personal_assessments",
    foreignKeys=[ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],
)
data class AnchoragePersonalAssessmentEntity(
    @PrimaryKey val placeId:Long,
    @SerializedName(value="wouldReturn",alternate=["overallPreference"]) val wouldReturn:String="UNKNOWN",
    val holding:String="UNKNOWN",
    val comfort:String="UNKNOWN",
    val shoreAccess:String="UNKNOWN",
    val crowding:String="UNKNOWN",
    val quietness:String="UNKNOWN",
    val notes:String="",
    val legacyOverallRating:Int?=null,
    val updatedAt:Long,
){
    /** Accepts v5 backup values where assessment fields were integer stars. */
    fun normalized()=copy(
        wouldReturn=wouldReturn.normalizeWouldReturn(),
        holding=holding.normalizeAssessment(),comfort=comfort.normalizeAssessment(),shoreAccess=shoreAccess.normalizeAssessment(),crowding=crowding.normalizeAssessment(),quietness=quietness.normalizeAssessment(),
        notes=notes.take(20_000),
    )
    private fun String?.normalizeWouldReturn()=when(this?.uppercase()){"YES","MAYBE","NO"->uppercase();else->"UNKNOWN"}
    private fun String?.normalizeAssessment()=when(this?.uppercase()){"GOOD","AVERAGE","POOR"->uppercase();"4","5"->"GOOD";"3"->"AVERAGE";"1","2"->"POOR";else->"UNKNOWN"}
}

@Entity(
    tableName="anchorage_photos",
    foreignKeys=[
        ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=AnchorageSpotEntity::class,parentColumns=["id"],childColumns=["spotId"],onDelete=ForeignKey.SET_NULL),
        ForeignKey(entity=AnchorageVisitEntity::class,parentColumns=["id"],childColumns=["visitId"],onDelete=ForeignKey.SET_NULL),
    ],indices=[Index("placeId"),Index("spotId"),Index("visitId")],
)
data class AnchoragePhotoEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,val placeId:Long,val spotId:Long?=null,val visitId:Long?=null,
    val relativeFileName:String,val thumbnailRelativeFileName:String?,val mimeType:String,val sha256:String,val width:Int?,val height:Int?,val caption:String="",val capturedAt:Long?=null,val createdAt:Long,
)

@Entity(
    tableName="anchorage_place_summaries",
    foreignKeys=[ForeignKey(entity=AnchoragePlaceEntity::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],
)
data class AnchoragePlaceSummaryEntity(@PrimaryKey val placeId:Long,val generatedAt:Long,val engineVersion:String,val json:String)

@Fts4
@Entity(tableName="anchorage_search_fts")
data class AnchorageSearchFtsEntity(
    @PrimaryKey @ColumnInfo(name="rowid") val rowId:Long,
    val placeId:Long,
    val placeName:String,
    val aliases:String,
    val regionPath:String,
    val spotNames:String,
    val notes:String,
)

@Entity(tableName="anchorage_gis_meta")
data class AnchorageGisMetaEntity(@PrimaryKey val key:String,val longValue:Long?,val textValue:String?)

@Entity(
    tableName="anchorage_region_packs",
    foreignKeys=[ForeignKey(entity=AnchorageRegionEntity::class,parentColumns=["id"],childColumns=["regionId"],onDelete=ForeignKey.CASCADE)],
)
data class AnchorageRegionPackEntity(@PrimaryKey val regionId:Long,val downloadedAt:Long,val providerRevision:String?,val featureCount:Int)
