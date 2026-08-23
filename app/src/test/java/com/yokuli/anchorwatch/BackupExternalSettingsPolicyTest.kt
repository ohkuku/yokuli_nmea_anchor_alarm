package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.backup.BackupExternalSettingsPolicy
import com.yokuli.anchorwatch.data.preferences.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExternalSettingsPolicyTest{
    private val enabled=AppSettings(
        offlineMapEnabled=true,
        offlineMapName="Coromandel.mbtiles",
        offlineMapAttribution="Local chart provider",
    )

    @Test fun missingExternalMbTilesCannotRestoreAFakeEnabledState(){
        val restored=BackupExternalSettingsPolicy.reconcileOfflineMap(enabled,installed=false)
        assertFalse(restored.offlineMapEnabled)
        assertNull(restored.offlineMapName)
        assertNull(restored.offlineMapAttribution)
    }

    @Test fun existingLocalMbTilesKeepsTheRestoredPreference(){
        assertTrue(BackupExternalSettingsPolicy.reconcileOfflineMap(enabled,installed=true).offlineMapEnabled)
    }
}
