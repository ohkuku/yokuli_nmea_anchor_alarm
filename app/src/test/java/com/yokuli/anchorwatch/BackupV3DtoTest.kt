package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.backup.*
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.vessel.VesselDataSettings
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.WatchWorkspaceMode
import com.yokuli.anchorwatch.location.vessel.DeviceBowAxis
import com.yokuli.anchorwatch.location.vessel.SensorQuaternion
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibration
import org.junit.Assert.*
import org.junit.Test

class BackupV3DtoTest{
    @Test fun tripSessionWrapperKeepsStablePagingId(){val value=TripSessionEntity(id=9,name="Hauraki",startedAt=1,boatLengthMeters=10.6,draftMeters=1.8,positionPreference="AUTO",headingPreference="BOAT",phoneMotionEnabled=true,mountCalibrationVersion=1);val dto=BackupTripSessionV3.from(value);assertEquals(9,dto.id);assertEquals(value,dto.value)}
    @Test fun vesselWorkspaceLayoutsDraftOutputAndMountCalibrationArePartOfV3Settings(){
        val calibration=VesselMountCalibration(4,DeviceBowAxis.RIGHT,SensorQuaternion(.9,.1,.2,.3),1234)
        val value=BackupVesselSettingsV3(value=VesselDataSettings(watchWorkspace=WatchWorkspaceMode.TRIP,draftMeters=1.8,navLayout=listOf(InstrumentTileId.SOG)),output=NmeaDeviceOutputSettings(true),mountCalibration=calibration)
        assertEquals(4,YokuliBackupArchive.VERSION);assertEquals(3,value.schemaVersion)
        assertEquals(WatchWorkspaceMode.TRIP,value.value.watchWorkspace);assertEquals(1.8,value.value.draftMeters!!,.01)
        assertEquals(listOf(InstrumentTileId.SOG),value.value.navLayout);assertTrue(value.output.phonePositionEnabled);assertEquals(calibration,value.mountCalibration)
    }
}
