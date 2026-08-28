package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness

/** Stream-local prerequisites. A route may be running while one stream waits;
 * another ready stream must never be held behind unrelated calibration. */
object NmeaStreamReadinessPolicy {
    fun position(freshGnss:Boolean)=if(freshGnss)NmeaStreamReadiness.READY else NmeaStreamReadiness.WAITING_POSITION

    fun heading(
        headingAligned:Boolean,
        declinationReady:Boolean,
        freshCompass:Boolean,
    )=when{
        !headingAligned->NmeaStreamReadiness.WAITING_CALIBRATION
        !declinationReady->NmeaStreamReadiness.WAITING_POSITION
        !freshCompass->NmeaStreamReadiness.STANDBY
        else->NmeaStreamReadiness.READY
    }

    fun motion(mountCalibrated:Boolean,vesselMounted:Boolean,freshAttitude:Boolean)=when{
        !mountCalibrated||!vesselMounted->NmeaStreamReadiness.WAITING_CALIBRATION
        !freshAttitude->NmeaStreamReadiness.STANDBY
        else->NmeaStreamReadiness.READY
    }

    fun sensor(fresh:Boolean)=if(fresh)NmeaStreamReadiness.READY else NmeaStreamReadiness.STANDBY

    /** Maps an exact stream-local suppression reason to presentation state.
     * WAITING_CALIBRATION is reserved for a real missing/obsolete calibration;
     * the exact reason remains separately visible in TX diagnostics. */
    fun forSuppression(stream:AnchorWatchNmeaStream,reason:String?):NmeaStreamReadiness=when{
        stream==AnchorWatchNmeaStream.HEADING&&reason in setOf("HEADING_ALIGNMENT_REQUIRED","HEADING_ALIGNMENT_EPOCH_MISMATCH")->NmeaStreamReadiness.WAITING_CALIBRATION
        stream==AnchorWatchNmeaStream.POSITION&&reason=="PHONE_GPS_STALE"->NmeaStreamReadiness.WAITING_POSITION
        else->NmeaStreamReadiness.STANDBY
    }
}
