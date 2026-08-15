package com.yokuli.anchorwatch.domain.anchor
import com.yokuli.anchorwatch.domain.model.*

class AlarmEngine(private val persistenceMillis:Long=8_000,private val requiredFixes:Int=3,private val gpsLossMillis:Long=15_000) {
 private var config:AnchorConfig?=null;private var radialEnabled=false;private var outsideSince:Long?=null;private var outsideCount=0;private var lastFix:Long?=null;private var sum=0.0;private var count=0;private var snap=AlarmSnapshot()
 fun learn(c:AnchorConfig,now:Long=System.nanoTime()/1_000_000):AlarmSnapshot{config=c;radialEnabled=false;outsideSince=null;outsideCount=0;lastFix=now;snap=AlarmSnapshot(AlarmState.LEARNING);return snap}
 fun arm(c:AnchorConfig,now:Long=System.nanoTime()/1_000_000):AlarmSnapshot{config=c;radialEnabled=true;outsideSince=null;outsideCount=0;lastFix=now;snap=AlarmSnapshot(AlarmState.ARMED);return snap}
 fun stop()=AlarmSnapshot(AlarmState.STOPPED).also{snap=it;config=null;radialEnabled=false}
 fun acknowledge():AlarmSnapshot=snap.copy(state=AlarmState.ACKNOWLEDGED,acknowledged=true).also{snap=it}
 fun onFix(f:NavigationFix,now:Long=f.receivedElapsedRealtime):AlarmSnapshot { val c=config?:return snap;if(!f.valid)return tick(now);lastFix=now;if(!radialEnabled){snap=snap.copy(state=AlarmState.LEARNING,type=null);return snap};val d=AnchorGeometry.distanceMeters(c.latitude,c.longitude,f.latitude,f.longitude);sum+=d;count++; val outside=d>c.alarmRadiusMeters;if(outside){if(outsideSince==null)outsideSince=now;outsideCount++}else{outsideSince=null;outsideCount=0};val alarm=outside&&(outsideCount>=requiredFixes||now-(outsideSince?:now)>=persistenceMillis);val state=when{alarm->AlarmState.ALARM;d>c.warningRadiusMeters->AlarmState.WARNING;else->AlarmState.ARMED};snap=AlarmSnapshot(state,if(alarm)AlarmType.ANCHOR_RADIUS_EXCEEDED else null,d,maxOf(snap.maxDistanceMeters,d),snap.minDistanceMeters?.let{minOf(it,d)}?:d,sum/count,snap.acknowledged&&alarm);return snap }
 fun tick(now:Long):AlarmSnapshot { val last=lastFix;if(config!=null&&(last==null||now-last>=gpsLossMillis)){snap=snap.copy(state=AlarmState.ALARM,type=AlarmType.GPS_DATA_LOST)};return snap }
}
