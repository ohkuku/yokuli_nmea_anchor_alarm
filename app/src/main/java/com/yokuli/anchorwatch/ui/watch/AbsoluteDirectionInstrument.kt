package com.yokuli.anchorwatch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.domain.navigation.NmeaCourseTrustGate
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
import com.yokuli.anchorwatch.domain.vessel.VesselReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class AbsoluteDirectionState(
    val headingTrueDegrees:Double?=null,
    val headingMagneticDegrees:Double?=null,
    val cogTrueDegrees:Double?=null,
    val twdFromTrueDegrees:Double?=null,
)

object AbsoluteDirectionPolicy{
    fun resolve(state:MainUiState,cogTrusted:Boolean):AbsoluteDirectionState{
        val data=state.vesselData
        val trueHeading=data.headingTrueDegrees.value.takeIf{data.headingTrueDegrees.freshness==VesselDataFreshness.FRESH&&data.headingTrueDegrees.reference!=VesselReference.MagneticNorth}
        val magnetic=data.headingMagneticDegrees.value.takeIf{trueHeading==null&&data.headingMagneticDegrees.freshness==VesselDataFreshness.FRESH}
        val cog=data.cogTrueDegrees.value.takeIf{cogTrusted&&data.cogTrueDegrees.freshness==VesselDataFreshness.FRESH}
        val twd=data.trueWind.directionDegrees.value.takeIf{data.trueWind.directionDegrees.freshness==VesselDataFreshness.FRESH}
        return AbsoluteDirectionState(trueHeading,magnetic,cog,twd)
    }

    fun shortestTarget(previousUnwrapped:Double,targetNormalized:Double):Double{
        val previous=((previousUnwrapped%360.0)+360.0)%360.0
        val delta=((targetNormalized-previous+540.0)%360.0)-180.0
        return previousUnwrapped+delta
    }
}

/** Presentation-only COG gate for Phone GNSS. Boat NMEA uses the existing
 * NmeaCourseTrustGate output owned by MainViewModel. */
class PhoneCoursePointerGate{
    private var trusted=false
    fun update(sogKnots:Double?,fresh:Boolean):Boolean{
        if(!fresh||sogKnots==null){trusted=false;return false}
        if(!trusted&&sogKnots>=NmeaCourseTrustGate.ENTER_SPEED_KNOTS)trusted=true
        else if(trusted&&sogKnots<NmeaCourseTrustGate.EXIT_SPEED_KNOTS)trusted=false
        return trusted
    }
}

@Composable
internal fun AbsoluteDirectionInstrument(state:MainUiState,compact:Boolean=false,modifier:Modifier=Modifier){
    val data=state.vesselData
    val phoneGate=remember{PhoneCoursePointerGate()}
    val boatCog=data.cogTrueDegrees.source==VesselDataSource.BOAT_NMEA
    val cogTrusted=if(boatCog)state.trustedNmeaCourse?.isFresh(android.os.SystemClock.elapsedRealtime())==true
    else phoneGate.update(data.sogKnots.value,data.sogKnots.freshness==VesselDataFreshness.FRESH&&data.cogTrueDegrees.freshness==VesselDataFreshness.FRESH)
    val directions=AbsoluteDirectionPolicy.resolve(state,cogTrusted)
    val awa=data.apparentWind.angleDegrees.value.takeIf{data.apparentWind.angleDegrees.freshness!=VesselDataFreshness.STALE}
    val twa=data.trueWind.angleDegrees.value.takeIf{data.trueWind.angleDegrees.freshness!=VesselDataFreshness.STALE}
    ElevatedCard(modifier.fillMaxSize().testTag("sail_compass")){
        Column(Modifier.fillMaxSize().padding(horizontal=if(compact)6.dp else 10.dp,vertical=if(compact)2.dp else 6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceEvenly){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                DirectionLabel("HDG",directions.headingTrueDegrees,"T",directions.headingMagneticDegrees)
                DirectionLabel("COG",directions.cogTrueDegrees,"T")
                DirectionLabel("TWD",directions.twdFromTrueDegrees,"T",suffix=" · FROM")
            }
            AbsoluteRose(directions,Modifier.size(if(compact)88.dp else 150.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                Text("AWA ${awa?.let(::relativeWindSide)?:"—"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Text("TWA ${twa?.let(::relativeWindSide)?:"—"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
            }
            if(!compact&&directions.headingTrueDegrees==null&&directions.headingMagneticDegrees!=null)Text(tr("Magnetic HDG is not plotted on the true-reference rose","磁船首向不会绘制在真北参考罗盘上"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun RowScope.DirectionLabel(label:String,trueValue:Double?,reference:String,magneticValue:Double?=null,suffix:String=""){
    val text=when{trueValue!=null->"$label ${"%03.0f".format(trueValue)}°$reference$suffix";magneticValue!=null->"$label ${"%03.0f".format(magneticValue)}°M";else->"$label —"}
    Text(text,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,maxLines=1)
}

@Composable private fun AbsoluteRose(value:AbsoluteDirectionState,modifier:Modifier){
    val primary=MaterialTheme.colorScheme.primary;val cogColor=MaterialTheme.colorScheme.secondary;val windColor=MaterialTheme.colorScheme.tertiary;val grid=MaterialTheme.colorScheme.outline.copy(alpha=.55f);val surface=MaterialTheme.colorScheme.surface
    val heading=rememberUnwrappedAngle(value.headingTrueDegrees);val cog=rememberUnwrappedAngle(value.cogTrueDegrees);val twd=rememberUnwrappedAngle(value.twdFromTrueDegrees)
    Canvas(modifier.testTag("absolute_direction_rose")){
        val center=Offset(size.width/2,size.height/2);val radius=size.minDimension*.45f
        drawCircle(grid,radius,center,style=Stroke(2f))
        repeat(12){index->val angle=Math.toRadians(index*30.0);val outer=bearingOffset(center,radius,angle);val inner=bearingOffset(center,radius-if(index%3==0)12f else 7f,angle);drawLine(grid,inner,outer,if(index%3==0)3f else 1.5f)}
        fun outward(angleDegrees:Float?,color:Color,scale:Float){if(angleDegrees==null)return;val angle=Math.toRadians(angleDegrees.toDouble());val tip=bearingOffset(center,radius*scale,angle);drawLine(color,center,tip,5f);drawCircle(color,5f,tip)}
        outward(heading,primary,.88f);outward(cog,cogColor,.68f)
        // TWD is the bearing the wind comes FROM: the marker is placed at
        // that source bearing and the arrow head points inward to the centre.
        twd?.let{degrees->
            val angle=Math.toRadians(degrees.toDouble());val source=bearingOffset(center,radius*.96f,angle);val inner=bearingOffset(center,radius*.30f,angle);drawLine(windColor,source,inner,5f)
            val direction=Offset(center.x-source.x,center.y-source.y);val length=kotlin.math.sqrt(direction.x*direction.x+direction.y*direction.y).coerceAtLeast(1f);val unit=Offset(direction.x/length,direction.y/length);val side=Offset(-unit.y,unit.x);val head=Path().apply{moveTo(inner.x,inner.y);lineTo(inner.x-unit.x*12+side.x*7,inner.y-unit.y*12+side.y*7);lineTo(inner.x-unit.x*12-side.x*7,inner.y-unit.y*12-side.y*7);close()};drawPath(head,windColor)
        }
        drawCircle(surface,6f,center);drawCircle(grid,6f,center,style=Stroke(2f))
    }
}

@Composable private fun rememberUnwrappedAngle(target:Double?):Float?{
    var unwrapped by remember{mutableDoubleStateOf(target?:0.0)}
    LaunchedEffect(target){target?.let{unwrapped=AbsoluteDirectionPolicy.shortestTarget(unwrapped,it)}}
    val animated by animateFloatAsState(unwrapped.toFloat(),tween(260),label="marine-bearing")
    return target?.let{animated}
}

private fun bearingOffset(center:Offset,radius:Float,angleRadians:Double)=Offset(center.x+sin(angleRadians).toFloat()*radius,center.y-cos(angleRadians).toFloat()*radius)
private fun relativeWindSide(value:Double)="%.0f° %s".format(abs(value),if(value<0)"P" else "S")
