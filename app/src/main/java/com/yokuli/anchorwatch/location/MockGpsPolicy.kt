package com.yokuli.anchorwatch.location

class MockGpsPolicy(private val staleTimeoutMillis:Long,updateHz:Int){
 private val intervalMillis=1000L/updateHz.coerceIn(1,5);private var startedAt:Long?=null;private var lastValidFix:Long?=null;private var lastPublished:Long?=null
 fun start(now:Long){startedAt=now;lastValidFix=null;lastPublished=null}
 fun onValidFix(now:Long):Boolean{lastValidFix=now;val due=lastPublished?.let{now-it>=intervalMillis}?:true;if(due)lastPublished=now;return due}
 fun isStale(now:Long):Boolean=(lastValidFix?:startedAt)?.let{now-it>=staleTimeoutMillis}?:false
}
