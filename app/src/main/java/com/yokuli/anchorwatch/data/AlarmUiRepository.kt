package com.yokuli.anchorwatch.data

import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-process bridge so a foreground Activity can offer actions in addition to the notification. */
@Singleton
class AlarmUiRepository @Inject constructor() {
    private val mutableSnapshot = MutableStateFlow(AlarmSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    fun publish(value: AlarmSnapshot) { mutableSnapshot.value = value }
    fun clear() { mutableSnapshot.value = AlarmSnapshot() }
}
