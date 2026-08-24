package com.yokuli.anchorwatch.runtime.output

import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness

/** Stream-local prerequisites. A route may be running while one stream waits;
 * another ready stream must never be held behind unrelated calibration. */
object NmeaStreamReadinessPolicy {
    fun position(freshGnss:Boolean)=if(freshGnss)NmeaStreamReadiness.READY else NmeaStreamReadiness.WAITING_POSITION

    fun heading(
        mountCalibrated:Boolean,
        headingAligned:Boolean,
        vesselMounted:Boolean,
        declinationReady:Boolean,
        freshCompass:Boolean,
    )=when{
        !mountCalibrated||!headingAligned||!vesselMounted->NmeaStreamReadiness.WAITING_CALIBRATION
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
}
