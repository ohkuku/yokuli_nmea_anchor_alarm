package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.trip.TripReplayData
import com.yokuli.anchorwatch.data.trip.TripReplayColorMode
import com.yokuli.anchorwatch.data.trip.TripReplayPoint
import com.yokuli.anchorwatch.data.trip.TripReplayPolicy
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun TripReplayDialog(session:TripSessionEntity,vm:MainViewModel,dismiss:()->Unit){
    val replay by produceState<TripReplayData?>(null,session.id){value=vm.tripReplay(session.id)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Trip Replay","航程回放"))},confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}},text={
        val data=replay
        if(data==null)Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        else if(data.points.isEmpty())Text(tr("No recorded samples are available for replay.","没有可供回放的记录样本。"))
        else TripReplayContent(data)
    })
}

@Composable
private fun TripReplayContent(data:TripReplayData){
    var index by remember(data){mutableIntStateOf(0)};var playing by remember{mutableStateOf(false)};var colorMode by rememberSaveable{mutableStateOf(TripReplayColorMode.SOG)}
    val point=data.points[index.coerceIn(0,data.points.lastIndex)]
    LaunchedEffect(playing,index,data.points.size){if(playing){if(index<data.points.lastIndex){delay(250);index++}else playing=false}}
    Column(Modifier.heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        val positioned=data.points.filter{it.latitude!=null&&it.longitude!=null}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){TripReplayColorMode.entries.forEach{mode->FilterChip(colorMode==mode,{colorMode=mode},label={Text(when(mode){TripReplayColorMode.SOG->"SOG";TripReplayColorMode.BSP->"BSP";TripReplayColorMode.HEEL->tr("Heel","横倾");TripReplayColorMode.TWS->"TWS";TripReplayColorMode.AWS->"AWS";TripReplayColorMode.MOTION->tr("Motion","运动");TripReplayColorMode.DEPTH->tr("Depth","水深")})})}}
        if(BuildConfig.MAPS_CONFIGURED&&positioned.isNotEmpty()){
            val initial=positioned.first();val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(LatLng(initial.latitude!!,initial.longitude!!),13f)}
            GoogleMap(Modifier.fillMaxWidth().height(250.dp),cameraPositionState=camera,uiSettings=MapUiSettings(compassEnabled=false,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false)){
                replaySegments(positioned,colorMode).forEach{segment->Polyline(points=segment.points,color=segment.color,width=5f)}
                val currentLatitude=point.latitude;val currentLongitude=point.longitude
                if(currentLatitude!=null&&currentLongitude!=null)Marker(state=remember(currentLatitude,currentLongitude){MarkerState(LatLng(currentLatitude,currentLongitude))},title=tr("Replay position","回放位置"))
            }
        }
        Text(DateFormat.getDateTimeInstance().format(Date(point.timestamp)),style=MaterialTheme.typography.titleSmall)
        if(data.points.size>1)Slider(index.toFloat(),{index=it.toInt().coerceIn(0,data.points.lastIndex)},valueRange=0f..data.points.lastIndex.toFloat())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            OutlinedButton({data.markers.lastOrNull{it.pointIndex<index}?.let{index=it.pointIndex}},Modifier.weight(1f),enabled=data.markers.any{it.pointIndex<index}){Text(tr("Previous event","上一事件"))}
            Button({playing=!playing},Modifier.weight(1f)){Text(if(playing)tr("Pause","暂停") else tr("Play","播放"))}
            OutlinedButton({data.markers.firstOrNull{it.pointIndex>index}?.let{index=it.pointIndex}},Modifier.weight(1f),enabled=data.markers.any{it.pointIndex>index}){Text(tr("Next event","下一事件"))}
        }
        data.markers.lastOrNull{it.pointIndex<=index}?.takeIf{kotlin.math.abs(it.pointIndex-index)<=1}?.let{Text("${it.type} · ${it.title}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){ReplayValue("SOG",point.sogKnots,"%.1f kn");ReplayValue("COG",point.cogDegrees,"%03.0f°");ReplayValue(tr("Heading","船首向"),point.headingDegrees,"%03.0f°")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){ReplayValue("BSP",point.boatSpeedKnots,"%.1f kn");ReplayValue("TWS / AWS",point.trueWindKnots?:point.apparentWindKnots,"%.1f kn");ReplayValue(tr("Depth","水深"),point.depthMeters,"%.1f m")}
        ReplayValue(tr("Heel","横倾"),point.heelDegrees,"%+.1f°")
        ReplayValue(tr("Motion","运动"),point.motionScore,"%.0f")
    }
}

private data class ReplaySegment(val points:List<LatLng>,val color:androidx.compose.ui.graphics.Color)
private fun replaySegments(points:List<TripReplayPoint>,mode:TripReplayColorMode):List<ReplaySegment>{
    if(points.size<2)return emptyList();val result=mutableListOf<ReplaySegment>();var bucket=TripReplayPolicy.colorBucket(points.first(),mode);var current=mutableListOf(LatLng(points.first().latitude!!,points.first().longitude!!))
    points.drop(1).forEach{point->val next=TripReplayPolicy.colorBucket(point,mode);val latLng=LatLng(point.latitude!!,point.longitude!!);if(next!=bucket&&current.size>1){result+=ReplaySegment(current.toList(),replayColor(bucket));current=mutableListOf(current.last(),latLng);bucket=next}else current+=latLng};if(current.size>1)result+=ReplaySegment(current,replayColor(bucket));return result
}
private fun replayColor(bucket:Int)=when(bucket){0->androidx.compose.ui.graphics.Color(0xFF00A896);1->androidx.compose.ui.graphics.Color(0xFFFFB000);2->androidx.compose.ui.graphics.Color(0xFFD62828);else->androidx.compose.ui.graphics.Color(0xFF78909C)}

@Composable private fun ReplayValue(label:String,value:Double?,format:String){Column(horizontalAlignment=Alignment.Start){Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value?.let{format.format(it)}?:"—",style=MaterialTheme.typography.bodyMedium)}}
