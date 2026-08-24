package com.yokuli.anchorwatch.data.anchorage

import androidx.room.withTransaction
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.AnchorageGisMetaEntity
import com.yokuli.anchorwatch.data.diagnostics.IncidentLogger
import com.yokuli.anchorwatch.data.diagnostics.IncidentSeverity
import javax.inject.Inject
import javax.inject.Singleton

data class AnchorageLegacyMigrationHealth(val legacyRows:Long,val places:Long,val spots:Long,val verified:Boolean)

@Singleton class AnchorageLegacyMigrationVerifier @Inject constructor(private val database:AppDatabase,private val spatial:AnchorageSpatialIndexRepository,private val search:AnchorageSearchRepository,private val incidents:IncidentLogger){
    suspend fun verifyOnce():AnchorageLegacyMigrationHealth{
        database.anchorageMetadataDao().meta(VERIFIED_AT)?.let{return health(true)}
        val first=health(false)
        if(first.legacyRows!=first.places||first.legacyRows!=first.spots){
            incidents.recordNow("ANCHORAGE","ANCHORAGE_GIS_MIGRATION_MISMATCH",IncidentSeverity.CRITICAL,details=mapOf("legacy_rows" to first.legacyRows,"places" to first.places,"spots" to first.spots))
            // Do not delete or guess. SQL migration is deterministic; retaining
            // legacy rows makes an explicit support/repair operation possible.
            return first
        }
        database.withTransaction{database.anchorageMetadataDao().upsertMeta(AnchorageGisMetaEntity(VERIFIED_AT,System.currentTimeMillis(),null));spatial.verifyAndRepair();search.rebuildAll()}
        incidents.recordNow("ANCHORAGE","ANCHORAGE_GIS_MIGRATION_COMPLETED",details=mapOf("legacy_rows" to first.legacyRows))
        return health(true)
    }
    private suspend fun health(verified:Boolean)=AnchorageLegacyMigrationHealth(database.anchorageDao().allNow().size.toLong(),database.anchoragePlaceDao().migratedLegacyCount(),database.anchorageSpotDao().migratedLegacyCount(),verified)
    companion object{const val VERIFIED_AT="MIGRATION_VERIFIED_AT"}
}
