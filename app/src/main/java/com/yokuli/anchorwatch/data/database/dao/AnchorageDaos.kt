package com.yokuli.anchorwatch.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.yokuli.anchorwatch.data.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao interface AnchorageRegionDao{
    @Query("SELECT * FROM anchorage_regions WHERE id=:id") suspend fun get(id:Long):AnchorageRegionEntity?
    @Query("SELECT * FROM anchorage_regions ORDER BY displayName") fun observeAll():Flow<List<AnchorageRegionEntity>>
    @Query("SELECT * FROM anchorage_regions WHERE custom=1 ORDER BY displayName") suspend fun customRegions():List<AnchorageRegionEntity>
    @Query("SELECT * FROM anchorage_regions WHERE provider=:provider AND externalId=:externalId LIMIT 1") suspend fun byExternalId(provider:String,externalId:String):AnchorageRegionEntity?
    @Query("SELECT * FROM anchorage_regions WHERE bboxMaxLatitude>=:south AND bboxMinLatitude<=:north AND bboxMaxLongitude>=:west AND bboxMinLongitude<=:east ORDER BY userConfirmed DESC,displayName") suspend fun inBounds(south:Double,west:Double,north:Double,east:Double):List<AnchorageRegionEntity>
    @Upsert suspend fun upsert(value:AnchorageRegionEntity):Long
    @Query("SELECT COUNT(*) FROM anchorage_regions") suspend fun count():Long
    @Query("SELECT * FROM anchorage_regions ORDER BY id") suspend fun allNow():List<AnchorageRegionEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchorageRegionEntity>)
    @Query("DELETE FROM anchorage_regions") suspend fun clear()
}

@Dao interface AnchoragePlaceDao{
    @Query("SELECT * FROM anchorage_places WHERE id=:id") suspend fun get(id:Long):AnchoragePlaceEntity?
    @Query("SELECT * FROM anchorage_places WHERE archived=0 ORDER BY COALESCE(lastVisitedAt,updatedAt) DESC") fun observeActive():Flow<List<AnchoragePlaceEntity>>
    @Query("SELECT * FROM anchorage_places ORDER BY id") suspend fun allNow():List<AnchoragePlaceEntity>
    @Query("SELECT * FROM anchorage_places WHERE legacySavedAnchorageId=:legacyId LIMIT 1") suspend fun byLegacyId(legacyId:Long):AnchoragePlaceEntity?
    @Insert suspend fun insert(value:AnchoragePlaceEntity):Long
    @Update suspend fun update(value:AnchoragePlaceEntity)
    @Query("DELETE FROM anchorage_places WHERE id=:id") suspend fun delete(id:Long):Int
    @Query("SELECT COUNT(*) FROM anchorage_places") suspend fun count():Long
    @Query("SELECT COUNT(*) FROM anchorage_places WHERE legacySavedAnchorageId IS NOT NULL") suspend fun migratedLegacyCount():Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchoragePlaceEntity>)
    @Query("DELETE FROM anchorage_places") suspend fun clear()
}

@Dao interface AnchorageSpotDao{
    @Query("SELECT * FROM anchorage_spots WHERE id=:id") suspend fun get(id:Long):AnchorageSpotEntity?
    @Query("SELECT * FROM anchorage_spots WHERE placeId=:placeId ORDER BY COALESCE(lastVisitedAt,updatedAt) DESC") fun observeForPlace(placeId:Long):Flow<List<AnchorageSpotEntity>>
    @Query("SELECT * FROM anchorage_spots WHERE placeId=:placeId ORDER BY id") suspend fun forPlaceNow(placeId:Long):List<AnchorageSpotEntity>
    @Query("SELECT * FROM anchorage_spots ORDER BY id") suspend fun allNow():List<AnchorageSpotEntity>
    @Insert suspend fun insert(value:AnchorageSpotEntity):Long
    @Update suspend fun update(value:AnchorageSpotEntity)
    @Query("DELETE FROM anchorage_spots WHERE id=:id") suspend fun delete(id:Long):Int
    @Query("SELECT COUNT(*) FROM anchorage_spots") suspend fun count():Long
    @Query("SELECT COUNT(*) FROM anchorage_spots WHERE legacySavedAnchorageId IS NOT NULL") suspend fun migratedLegacyCount():Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchorageSpotEntity>)
    @Query("DELETE FROM anchorage_spots") suspend fun clear()
}

@Dao interface AnchorageVisitDao{
    @Query("SELECT * FROM anchorage_visits WHERE id=:id") suspend fun get(id:Long):AnchorageVisitEntity?
    @Query("SELECT * FROM anchorage_visits WHERE placeId=:placeId ORDER BY startedAt DESC") fun observeForPlace(placeId:Long):Flow<List<AnchorageVisitEntity>>
    @Query("SELECT * FROM anchorage_visits WHERE placeId=:placeId ORDER BY startedAt DESC") suspend fun forPlaceNow(placeId:Long):List<AnchorageVisitEntity>
    @Query("SELECT * FROM anchorage_visits WHERE anchorSessionId=:sessionId LIMIT 1") suspend fun bySession(sessionId:Long):AnchorageVisitEntity?
    @Insert suspend fun insert(value:AnchorageVisitEntity):Long
    @Update suspend fun update(value:AnchorageVisitEntity)
    @Query("SELECT COUNT(*) FROM anchorage_visits") suspend fun count():Long
    @Query("SELECT * FROM anchorage_visits ORDER BY id") suspend fun allNow():List<AnchorageVisitEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchorageVisitEntity>)
    @Query("DELETE FROM anchorage_visits") suspend fun clear()
}

@Dao interface AnchorageCollectionDao{
    @Query("SELECT * FROM anchorage_collections ORDER BY sortOrder,name") fun observeAll():Flow<List<AnchorageCollectionEntity>>
    @Query("SELECT * FROM anchorage_collections ORDER BY id") suspend fun allNow():List<AnchorageCollectionEntity>
    @Insert suspend fun insert(value:AnchorageCollectionEntity):Long
    @Update suspend fun update(value:AnchorageCollectionEntity)
    @Query("DELETE FROM anchorage_collections WHERE id=:id") suspend fun delete(id:Long):Int
    @Upsert suspend fun setMembership(value:AnchorageCollectionPlaceCrossRef)
    @Query("DELETE FROM anchorage_collection_places WHERE collectionId=:collectionId AND placeId=:placeId") suspend fun removeMembership(collectionId:Long,placeId:Long)
    @Query("SELECT * FROM anchorage_collection_places ORDER BY collectionId,placeId") suspend fun membershipsNow():List<AnchorageCollectionPlaceCrossRef>
    @Query("SELECT c.* FROM anchorage_collections c JOIN anchorage_collection_places x ON x.collectionId=c.id WHERE x.placeId=:placeId ORDER BY c.sortOrder,c.name") suspend fun forPlace(placeId:Long):List<AnchorageCollectionEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchorageCollectionEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importMemberships(values:List<AnchorageCollectionPlaceCrossRef>)
    @Query("DELETE FROM anchorage_collection_places") suspend fun clearMemberships()
    @Query("DELETE FROM anchorage_collections") suspend fun clear()
}

@Dao interface AnchorageMetadataDao{
    @Query("SELECT * FROM anchorage_place_regions WHERE placeId=:placeId ORDER BY sortOrder") suspend fun regionsForPlace(placeId:Long):List<AnchoragePlaceRegionCrossRef>
    @Upsert suspend fun upsertPlaceRegions(values:List<AnchoragePlaceRegionCrossRef>)
    @Query("DELETE FROM anchorage_place_regions WHERE placeId=:placeId") suspend fun clearPlaceRegions(placeId:Long)
    @Query("SELECT * FROM anchorage_personal_ratings WHERE placeId=:placeId") suspend fun rating(placeId:Long):AnchoragePersonalRatingEntity?
    @Upsert suspend fun upsertRating(value:AnchoragePersonalRatingEntity)
    @Query("SELECT * FROM anchorage_protection_sectors WHERE placeId=:placeId ORDER BY medium,sector") suspend fun protection(placeId:Long):List<AnchorageProtectionSectorEntity>
    @Upsert suspend fun upsertProtection(values:List<AnchorageProtectionSectorEntity>)
    @Query("SELECT * FROM anchorage_facilities WHERE placeId=:placeId ORDER BY type") suspend fun facilities(placeId:Long):List<AnchorageFacilityEntity>
    @Upsert suspend fun upsertFacilities(values:List<AnchorageFacilityEntity>)
    @Query("SELECT * FROM anchorage_place_summaries WHERE placeId=:placeId") suspend fun summary(placeId:Long):AnchoragePlaceSummaryEntity?
    @Upsert suspend fun upsertSummary(value:AnchoragePlaceSummaryEntity)
    @Query("DELETE FROM anchorage_place_summaries WHERE placeId=:placeId") suspend fun deleteSummary(placeId:Long)
    @Query("SELECT * FROM anchorage_gis_meta WHERE `key`=:key") suspend fun meta(key:String):AnchorageGisMetaEntity?
    @Upsert suspend fun upsertMeta(value:AnchorageGisMetaEntity)
    @Query("SELECT * FROM anchorage_place_regions ORDER BY placeId,sortOrder") suspend fun allPlaceRegions():List<AnchoragePlaceRegionCrossRef>
    @Query("SELECT * FROM anchorage_protection_sectors ORDER BY placeId,medium,sector") suspend fun allProtection():List<AnchorageProtectionSectorEntity>
    @Query("SELECT * FROM anchorage_facilities ORDER BY placeId,type") suspend fun allFacilities():List<AnchorageFacilityEntity>
    @Query("SELECT * FROM anchorage_personal_ratings ORDER BY placeId") suspend fun allRatings():List<AnchoragePersonalRatingEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importPlaceRegions(values:List<AnchoragePlaceRegionCrossRef>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importProtection(values:List<AnchorageProtectionSectorEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importFacilities(values:List<AnchorageFacilityEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importRatings(values:List<AnchoragePersonalRatingEntity>)
    @Query("DELETE FROM anchorage_place_regions") suspend fun clearPlaceRegionsAll()
    @Query("DELETE FROM anchorage_protection_sectors") suspend fun clearProtection()
    @Query("DELETE FROM anchorage_facilities") suspend fun clearFacilities()
    @Query("DELETE FROM anchorage_personal_ratings") suspend fun clearRatings()
    @Query("DELETE FROM anchorage_place_summaries") suspend fun clearSummaries()
}

@Dao interface AnchoragePhotoDao{
    @Query("SELECT * FROM anchorage_photos WHERE placeId=:placeId ORDER BY COALESCE(capturedAt,createdAt) DESC") fun observeForPlace(placeId:Long):Flow<List<AnchoragePhotoEntity>>
    @Query("SELECT * FROM anchorage_photos ORDER BY id") suspend fun allNow():List<AnchoragePhotoEntity>
    @Query("SELECT * FROM anchorage_photos WHERE placeId=:placeId ORDER BY COALESCE(capturedAt,createdAt) DESC") suspend fun forPlaceNow(placeId:Long):List<AnchoragePhotoEntity>
    @Query("SELECT * FROM anchorage_photos WHERE placeId=:placeId ORDER BY COALESCE(capturedAt,createdAt) DESC LIMIT 1") suspend fun firstForPlace(placeId:Long):AnchoragePhotoEntity?
    @Insert suspend fun insert(value:AnchoragePhotoEntity):Long
    @Delete suspend fun delete(value:AnchoragePhotoEntity)
    @Query("SELECT COUNT(*) FROM anchorage_photos WHERE placeId=:placeId") suspend fun countForPlace(placeId:Long):Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun importAll(values:List<AnchoragePhotoEntity>)
    @Query("DELETE FROM anchorage_photos") suspend fun clear()
}

@Dao interface AnchorageSearchDao{
    @Query("SELECT p.* FROM anchorage_search_fts f JOIN anchorage_places p ON p.id=f.placeId WHERE anchorage_search_fts MATCH :query AND p.archived=0 ORDER BY p.favorite DESC,COALESCE(p.lastVisitedAt,p.updatedAt) DESC LIMIT :limit") suspend fun search(query:String,limit:Int=100):List<AnchoragePlaceEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(value:AnchorageSearchFtsEntity):Long
    @Query("DELETE FROM anchorage_search_fts WHERE placeId=:placeId") suspend fun deletePlace(placeId:Long)
    @Query("DELETE FROM anchorage_search_fts") suspend fun clear()
}

@Dao interface AnchorageSpatialDao{
    @RawQuery(observedEntities=[AnchoragePlaceEntity::class]) suspend fun places(query:SupportSQLiteQuery):List<AnchoragePlaceEntity>
    @RawQuery(observedEntities=[AnchorageSpotEntity::class]) suspend fun spots(query:SupportSQLiteQuery):List<AnchorageSpotEntity>
}
