package com.yokuli.anchorwatch.di

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.data.vessel.OutputSettingsRepository
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Process-level dependencies used by black-box instrumentation tests and
 * process-recovery checks that must exercise the same objects as the app.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnchorWatchEntryPoint {
    fun dao(): AnchorDao
    fun sonarDao(): SonarDao
    fun sonarRecorder(): SonarSurveyRecorder
    fun preferences(): SettingsRepository
    fun navigation(): NavigationRepository
    fun alarmUi(): AlarmUiRepository
    fun sharingServer(): NmeaSharingServer
    fun acceptedPosition(): AcceptedPositionRepository
    fun runtimeDiagnostics():RuntimeDiagnosticsRepository
    fun outputSettings():OutputSettingsRepository
}
