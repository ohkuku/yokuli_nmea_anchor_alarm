package com.yokuli.anchorwatch.domain.vessel

data class TripTransitionInput(
    val nowElapsedRealtime:Long,
    val nmeaExpected:Boolean,
    val nmeaAvailable:Boolean,
    val depthExpected:Boolean,
    val depthAvailable:Boolean,
    val windExpected:Boolean,
    val windAvailable:Boolean,
    val phoneMotionExpected:Boolean,
    val phoneMotionAvailable:Boolean,
    val phoneMountSuspect:Boolean,
    val motionScore:Double?,
)

data class TripTransitionEvent(val type:String,val severity:String,val detailJson:String="{}")

/**
 * Edge-triggered Trip event policy. Missing optional instruments are not logged
 * until they have first been observed; NMEA and requested phone motion are
 * explicit runtime expectations and therefore may open a gap immediately.
 */
class TripEventTransitionTracker {
    private var nmeaAvailable:Boolean?=null
    private var depthAvailable:Boolean?=null
    private var windAvailable:Boolean?=null
    private var motionAvailable:Boolean?=null
    private var mountSuspect=false
    private var highMotionStartedAt:Long?=null
    private var highMotionActive=false
    private var calmStartedAt:Long?=null

    fun reset(){
        nmeaAvailable=null;depthAvailable=null;windAvailable=null;motionAvailable=null
        mountSuspect=false;highMotionStartedAt=null;highMotionActive=false;calmStartedAt=null
    }

    fun update(value:TripTransitionInput):List<TripTransitionEvent> = buildList {
        // Once NMEA has appeared during this trip, keep monitoring it even if
        // the trip did not start with NMEA. This records a later disconnect as
        // a real edge without creating a false gap for phone-only trips.
        if(value.nmeaExpected||value.nmeaAvailable||nmeaAvailable!=null){
            edge(nmeaAvailable,value.nmeaAvailable,"NMEA_DATA_GAP","NMEA_DATA_RESTORED")?.let(::add)
            nmeaAvailable=value.nmeaAvailable
        }

        if(value.depthExpected||value.depthAvailable||depthAvailable!=null){
            edge(depthAvailable,value.depthAvailable,"DEPTH_DATA_UNAVAILABLE","DEPTH_DATA_RESTORED")?.let(::add)
            depthAvailable=value.depthAvailable
        }
        if(value.windExpected||value.windAvailable||windAvailable!=null){
            edge(windAvailable,value.windAvailable,"WIND_DATA_UNAVAILABLE","WIND_DATA_RESTORED")?.let(::add)
            windAvailable=value.windAvailable
        }

        if(value.phoneMotionExpected){
            edge(motionAvailable,value.phoneMotionAvailable,"PHONE_MOTION_UNAVAILABLE","PHONE_MOTION_AVAILABLE")?.let(::add)
            motionAvailable=value.phoneMotionAvailable
        }
        if(value.phoneMountSuspect&&!mountSuspect)add(TripTransitionEvent("PHONE_MOUNT_SUSPECT","WARNING"))
        mountSuspect=value.phoneMountSuspect

        val score=value.motionScore
        when{
            !highMotionActive&&score!=null&&score>=70.0->{
                val started=highMotionStartedAt?:value.nowElapsedRealtime.also{highMotionStartedAt=it}
                if(value.nowElapsedRealtime-started>=10_000L){
                    highMotionActive=true;calmStartedAt=null
                    add(TripTransitionEvent("HIGH_MOTION","ATTENTION","{\"motionScore\":${"%.1f".format(java.util.Locale.US,score)}}"))
                }
            }
            !highMotionActive->{highMotionStartedAt=null}
            score!=null&&score<50.0->{
                val started=calmStartedAt?:value.nowElapsedRealtime.also{calmStartedAt=it}
                if(value.nowElapsedRealtime-started>=20_000L){
                    highMotionActive=false;highMotionStartedAt=null;calmStartedAt=null
                    add(TripTransitionEvent("HIGH_MOTION_CLEARED","INFO"))
                }
            }
            else->calmStartedAt=null
        }
    }

    private fun edge(previous:Boolean?,current:Boolean,lost:String,restored:String):TripTransitionEvent? = when{
        previous==null&&!current->TripTransitionEvent(lost,"WARNING")
        previous==true&&!current->TripTransitionEvent(lost,"WARNING")
        previous==false&&current->TripTransitionEvent(restored,"INFO")
        else->null
    }
}
