package com.yokuli.anchorwatch.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Transparent, bounded-cache raster tiles; no marker-per-sounding rendering. */
class SonarTileProvider(private val grid:SonarGrid):TileProvider{
    private val cache=object:LruCache<String,Tile>(96){}
    override fun getTile(x:Int,y:Int,zoom:Int):Tile{
        if(zoom !in 13..22||x<0||y<0)return TileProvider.NO_TILE
        val extent=1L shl zoom;if(x.toLong()>=extent||y.toLong()>=extent)return TileProvider.NO_TILE
        val key="$zoom/$x/$y";cache.get(key)?.let{return it}
        val halfWorld=PI*SonarGrid.EARTH_RADIUS;val world=halfWorld*2;val tileMeters=world/extent
        val minX=-halfWorld+x*tileMeters;val maxX=minX+tileMeters;val maxY=halfWorld-y*tileMeters;val minY=maxY-tileMeters
        val measured=grid.cellsInBounds(minX-15.0,maxX+15.0,minY-15.0,maxY+15.0);if(measured.isEmpty())return TileProvider.NO_TILE
        val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL}
        val rawCellPixels=(grid.cellSizeMeters/tileMeters*256.0).toFloat()
        data class RenderCell(val px:Float,val py:Float,val depth:Double,val uncertainty:Double,val measured:Boolean)
        val minXi=floor(minX/grid.cellSizeMeters).toLong();val maxXi=floor(maxX/grid.cellSizeMeters).toLong();val minYi=floor(minY/grid.cellSizeMeters).toLong();val maxYi=floor(maxY/grid.cellSizeMeters).toLong()
        val renderSource=if(zoom>=16){
            val radius=ceil(15.0/grid.cellSizeMeters).toLong();val keys=HashSet<Pair<Long,Long>>()
            measured.forEach{cell->for(cx in max(minXi,cell.xIndex-radius)..min(maxXi,cell.xIndex+radius))for(cy in max(minYi,cell.yIndex-radius)..min(maxYi,cell.yIndex+radius))keys+=cx to cy}
            keys.mapNotNull{cellKey->
                val value=grid.cells[cellKey]?.let{Triple(it.depthMeters,it.uncertaintyMeters,true)}?:run{val cx=(cellKey.first+.5)*grid.cellSizeMeters;val cy=(cellKey.second+.5)*grid.cellSizeMeters;grid.inspectProjected(cx,cy)?.takeUnless{it.measured}?.let{Triple(it.depthMeters,it.uncertaintyMeters,false)}}
                value?.let{cellKey to it}
            }
        }else measured.filter{it.xIndex in minXi..maxXi&&it.yIndex in minYi..maxYi}.map{(it.xIndex to it.yIndex) to Triple(it.depthMeters,it.uncertaintyMeters,true)}
        val projected=renderSource.map{(cellKey,value)->
            val (depth,uncertainty,isMeasured)=value
            val cx=(cellKey.first+.5)*grid.cellSizeMeters;val cy=(cellKey.second+.5)*grid.cellSizeMeters
            val px=((cx-minX)/tileMeters*256.0).toFloat();val py=((maxY-cy)/tileMeters*256.0).toFloat()
            RenderCell(px,py,depth,uncertainty,isMeasured)
        }
        val renderCells=if(rawCellPixels>=1.5f)projected else projected.groupBy{floor(it.px/2).toInt() to floor(it.py/2).toInt()}.values.map{bucket->RenderCell(bucket.map{it.px}.average().toFloat(),bucket.map{it.py}.average().toFloat(),bucket.map{it.depth}.average(),bucket.maxOf{it.uncertainty},bucket.any{it.measured})}
        val cellPixels=rawCellPixels.coerceAtLeast(1.5f)
        renderCells.forEach{cell->
            paint.color=depthColor(cell.depth,cell.uncertainty,cell.measured);canvas.drawRect(cell.px-cellPixels/2,cell.py-cellPixels/2,cell.px+cellPixels/2,cell.py+cellPixels/2,paint)
        }
        val bytes=ByteArrayOutputStream().use{stream->bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);stream.toByteArray()};bitmap.recycle()
        return Tile(256,256,bytes).also{cache.put(key,it)}
    }
    private fun depthColor(depth:Double,uncertainty:Double,measured:Boolean):Int{
        val normalized=(depth/50.0).coerceIn(0.0,1.0).toFloat();val hue=25f+normalized*195f;val baseAlpha=(220.0-uncertainty.coerceIn(0.0,8.0)*14.0).toInt().coerceIn(80,220);val alpha=if(measured)baseAlpha else (baseAlpha*.48).toInt().coerceAtLeast(45)
        return Color.HSVToColor(alpha,floatArrayOf(hue,0.82f,0.95f))
    }
}
