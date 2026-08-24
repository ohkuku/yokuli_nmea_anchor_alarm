package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.backup.YokuliBackupArchive
import org.junit.Assert.*
import org.junit.Test

class AnchorageBackupV5ContractTest{
    @Test fun v5CarriesNormalizedGisWithoutDerivedCaches(){
        assertEquals(5,YokuliBackupArchive.VERSION)
        assertTrue(YokuliBackupArchive.required.containsAll(YokuliBackupArchive.gisFiles))
        assertFalse(YokuliBackupArchive.dataFiles.any{it.contains("summary")||it.contains("fts")||it.contains("rtree")})
        assertEquals(YokuliBackupArchive.requiredV4,YokuliBackupArchive.requiredFor(4))
    }
}
