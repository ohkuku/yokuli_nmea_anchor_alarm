package com.yokuli.anchorwatch.data.sonar

import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.GridCoordinate
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarGridCellEntity
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.SonarGridSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

object SonarGridScope {
    const val SURVEY="SURVEY"
    const val CORRECTED_HISTORY="CORRECTED_HISTORY"
    const val CORRECTED_HISTORY_ID=0L
}

data class SonarGridUpdateDiagnostics(
    val rawSamples:Long=0,
    val gridCells:Long=0,
    val lastUpdatedCell:String?=null,
    val lastUpdateDurationMillis:Long?=null,
    val rebuilding:Boolean=false,
)

data class SonarGridChange(
    val scopeType:String,
    val scopeId:Long,
    val gridX:Long?=null,
    val gridY:Long?=null,
    val cell:SonarGridCellEntity?=null,
    val reload:Boolean=false,
)

/** Recomputes only the 5 m cells touched by a sounding or integrity release. */
@Singleton
class SonarIncrementalGridUpdater @Inject constructor(private val dao:SonarDao){
    private val _changes=MutableSharedFlow<SonarGridChange>(replay=1_024,extraBufferCapacity=256);val changes=_changes.asSharedFlow()
    @Volatile private var cachedRawSamples=0L;@Volatile private var cachedGridCells=0L;@Volatile private var countRefreshedAtNanos=0L
    suspend fun updateCells(surveyId:Long,coordinates:Set<GridCoordinate>):SonarGridUpdateDiagnostics=withContext(Dispatchers.IO){
        val started=System.nanoTime()
        coordinates.forEach{coordinate->
            recomputeSurveyCell(surveyId,coordinate.baseGridX,coordinate.baseGridY)
            recomputeCorrectedCell(coordinate.baseGridX,coordinate.baseGridY)
        }
        diagnostics(coordinates.lastOrNull(),(System.nanoTime()-started)/1_000_000)
    }

    suspend fun rebuildSurvey(surveyId:Long,includeCorrectedHistory:Boolean=true,previousCorrectedCoordinates:Set<GridCoordinate> = emptySet()):SonarGridUpdateDiagnostics=withContext(Dispatchers.IO){
        val started=System.nanoTime();dao.deleteGridScope(SonarGridScope.SURVEY,surveyId)
        val coordinates=dao.usableCellsForSurvey(surveyId).toSet()
        coordinates.forEach{recomputeSurveyCell(surveyId,it.baseGridX,it.baseGridY,emitChange=false)}
        if(includeCorrectedHistory){
            // A survey rebuild must not scan or delete the global corrected
            // history. Only cells that this survey occupied before or after
            // normalization can possibly have changed.
            val affected=previousCorrectedCoordinates+dao.correctedCellsForSurvey(surveyId)
            affected.forEach{recomputeCorrectedCell(it.baseGridX,it.baseGridY,emitChange=false)}
        }
        _changes.emit(SonarGridChange(SonarGridScope.SURVEY,surveyId,reload=true));if(includeCorrectedHistory)_changes.emit(SonarGridChange(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID,reload=true));diagnostics(coordinates.lastOrNull(),(System.nanoTime()-started)/1_000_000,forceCounts=true)
    }

    suspend fun rebuildMissing():SonarGridUpdateDiagnostics=withContext(Dispatchers.IO){
        val surveys=dao.surveys().first();var last:SonarGridUpdateDiagnostics?=null
        for(survey in surveys){
            if(dao.hasSamples(survey.id)&&!dao.hasGridCells(SonarGridScope.SURVEY,survey.id))last=rebuildSurvey(survey.id,includeCorrectedHistory=false)
        }
        val correctedCount=dao.correctedSampleCount();val hasCorrectedGrid=dao.hasGridCells(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID)
        if(correctedCount>0&&(last!=null||!hasCorrectedGrid)){
            dao.deleteGridScope(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID)
            dao.allCorrectedCells().forEach{coordinate->recomputeCorrectedCell(coordinate.baseGridX,coordinate.baseGridY,emitChange=false)}
            _changes.emit(SonarGridChange(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID,reload=true))
        }else if(correctedCount==0L&&hasCorrectedGrid){
            dao.deleteGridScope(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID);_changes.emit(SonarGridChange(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID,reload=true))
        }
        last?:diagnostics(null,null,forceCounts=true)
    }

    suspend fun deleteSurvey(surveyId:Long):Boolean=withContext(Dispatchers.IO){
        val survey=dao.survey(surveyId)?:return@withContext false
        if(survey.active)return@withContext false
        val corrected=dao.correctedCellsForSurvey(surveyId).toSet();dao.deleteGridScope(SonarGridScope.SURVEY,surveyId)
        val deleted=dao.deleteCompleted(surveyId)>0
        if(deleted){corrected.forEach{recomputeCorrectedCell(it.baseGridX,it.baseGridY)};_changes.emit(SonarGridChange(SonarGridScope.SURVEY,surveyId,reload=true))}
        deleted
    }

    private suspend fun recomputeSurveyCell(surveyId:Long,x:Long,y:Long,emitChange:Boolean=true){
        val samples=dao.usableSamplesInCell(surveyId,x,y)
        writeCell(SonarGridScope.SURVEY,surveyId,x,y,samples,corrected=false,emitChange=emitChange)
    }

    private suspend fun recomputeCorrectedCell(x:Long,y:Long,emitChange:Boolean=true){
        val samples=dao.correctedSamplesInCell(x,y)
        writeCell(SonarGridScope.CORRECTED_HISTORY,SonarGridScope.CORRECTED_HISTORY_ID,x,y,samples,corrected=true,emitChange=emitChange)
    }

    private suspend fun writeCell(scope:String,scopeId:Long,x:Long,y:Long,samples:List<DepthSampleEntity>,corrected:Boolean,emitChange:Boolean){
        if(samples.isEmpty()){dao.deleteGridCell(scope,scopeId,x,y);if(emitChange)_changes.emit(SonarGridChange(scope,scopeId,x,y,null));return}
        val cell=SonarGrid.aggregateCell(x,y,samples.mapNotNull{sample->
            val depth=if(corrected)sample.normalizedDepthMeters else sample.normalizedDepthMeters?:sample.measuredDepthMeters
            depth?.let{SonarGridSample(sample.latitude,sample.longitude,it,sample.horizontalAccuracyMeters,qualityWeight(sample))}
        })
        val entity=SonarGridCellEntity(scope,scopeId,x,y,5.0,cell.depthMeters,cell.uncertaintyMeters,cell.sampleCount,System.currentTimeMillis());dao.upsertGridCell(entity);if(emitChange)_changes.emit(SonarGridChange(scope,scopeId,x,y,entity))
    }

    private fun qualityWeight(sample:DepthSampleEntity):Double{
        val trust=if(sample.fixTrust==FixTrust.TRUSTED.name)1.0 else .35
        val speed=if((sample.sogKnots?:0.0)>12.0).5 else 1.0
        return trust*speed
    }

    private suspend fun diagnostics(last:GridCoordinate?,duration:Long?,forceCounts:Boolean=false):SonarGridUpdateDiagnostics{
        val now=System.nanoTime();if(forceCounts||countRefreshedAtNanos==0L||now-countRefreshedAtNanos>=60_000_000_000L){cachedRawSamples=dao.rawSampleCount();cachedGridCells=dao.gridCellCount();countRefreshedAtNanos=now}
        return SonarGridUpdateDiagnostics(rawSamples=cachedRawSamples,gridCells=cachedGridCells,lastUpdatedCell=last?.let{"${it.baseGridX},${it.baseGridY}"},lastUpdateDurationMillis=duration)
    }
}
