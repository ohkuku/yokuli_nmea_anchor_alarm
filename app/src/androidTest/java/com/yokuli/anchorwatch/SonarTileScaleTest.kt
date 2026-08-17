package com.yokuli.anchorwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yokuli.anchorwatch.data.database.SonarGridCellEntity
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.map.SonarTileProvider
import kotlin.math.PI
import kotlin.math.floor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SonarTileScaleTest{
    @Test fun oneHundredThousandCellsUseSpatialBucketsAndBoundedTileCache(){
        val origin=SonarGrid.project(-36.8485,174.7633)
        val baseX=floor(origin.first/5.0).toLong();val baseY=floor(origin.second/5.0).toLong()
        val cells=ArrayList<SonarGridCellEntity>(100_000)
        repeat(100_000){index->val x=baseX+index%400;val y=baseY+index/400;cells+=SonarGridCellEntity("SURVEY",1,x,y,5.0,4.0+(index%80)/10.0,.3,3,1)}
        val grid=SonarGrid.fromPersisted(cells)
        assertTrue(grid.cellsInBounds(origin.first-20.0,origin.first+20.0,origin.second-20.0,origin.second+20.0).size<200)
        val provider=SonarTileProvider(grid);val zoom=18;val extent=1L shl zoom;val halfWorld=PI*SonarGrid.EARTH_RADIUS;val tileMeters=halfWorld*2/extent
        val tileX=floor((origin.first+halfWorld)/tileMeters).toInt();val tileY=floor((halfWorld-origin.second)/tileMeters).toInt()
        repeat(140){offset->provider.getTile(tileX+offset%14,tileY+offset/14,zoom)}
        assertTrue(provider.cacheEntryCount()<=96)
    }
}
