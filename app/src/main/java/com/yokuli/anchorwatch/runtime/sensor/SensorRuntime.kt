package com.yokuli.anchorwatch.runtime.sensor

import com.yokuli.anchorwatch.location.PhoneHeadingRepository
import com.yokuli.anchorwatch.location.PhoneMotionRepository
import javax.inject.Inject
import javax.inject.Singleton

data class SensorRuntimeState(
    val phoneMotionActive: Boolean = false,
    val phoneHeadingActive: Boolean = false,
)

/**
 * Sole lifecycle gateway for optional phone motion and heading sensors.
 *
 * Runtime owners express requirements through RuntimeResourceManager; they do
 * not start or stop Android sensors directly. Existing heading evidence is
 * persisted with the anchor session, so stopping this runtime only prevents
 * new sensor samples from being collected.
 */
@Singleton
class SensorRuntime @Inject constructor(
    private val phoneMotion: PhoneMotionRepository,
    private val phoneHeading: PhoneHeadingRepository,
) {
    @Synchronized
    fun reconcile(needsPhoneMotion: Boolean, needsPhoneHeading: Boolean): SensorRuntimeState {
        val motionActive = if (needsPhoneMotion) {
            phoneMotion.start()
        } else {
            phoneMotion.stop()
            false
        }
        val headingActive = if (needsPhoneHeading) {
            phoneHeading.start()
        } else {
            phoneHeading.stop()
            false
        }
        return SensorRuntimeState(motionActive, headingActive)
    }

    @Synchronized
    fun stop(): SensorRuntimeState = reconcile(false, false)
}
