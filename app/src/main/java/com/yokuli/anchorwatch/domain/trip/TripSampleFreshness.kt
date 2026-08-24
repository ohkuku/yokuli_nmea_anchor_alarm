package com.yokuli.anchorwatch.domain.trip

/** Field-specific availability rules shared by the recorder/report boundary.
 * Apparent wind deliberately has no dependency on true-wind fields. */
object TripSampleFreshness {
    const val WIND_USABLE_MILLIS=60_000L
    const val ATTITUDE_USABLE_MILLIS=5_000L

    fun trueWindAvailable(speedKnots:Double?,speedAgeMillis:Long?)=speedKnots!=null&&(speedAgeMillis?:Long.MAX_VALUE)<=WIND_USABLE_MILLIS
    fun apparentWindAvailable(speedKnots:Double?,speedAgeMillis:Long?)=speedKnots!=null&&(speedAgeMillis?:Long.MAX_VALUE)<=WIND_USABLE_MILLIS
    fun attitudeUsable(ageMillis:Long?,quality:String,mountSuspect:Boolean)=ageMillis!=null&&ageMillis<=ATTITUDE_USABLE_MILLIS&&quality=="GOOD"&&!mountSuspect
}
