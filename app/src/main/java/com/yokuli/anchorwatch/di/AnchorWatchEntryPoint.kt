package com.yokuli.anchorwatch.di

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
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
    fun preferences(): SettingsRepository
    fun navigation(): NavigationRepository
}
