package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.backup.BackupAnchorSessionV2
import com.yokuli.anchorwatch.data.backup.BackupSavedAnchorageV2
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import org.junit.Assert.*
import org.junit.Test

class BackupV2DtoTest{
    @Test fun conditionFieldsRoundTripWithoutChangingV1Contract(){val entity=AnchorSessionEntity(startedAt=1,anchorLatitude=-36.8,anchorLongitude=175.1,rodeLengthMeters=40.0,waterDepthMeters=6.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=39.0,warningRadiusMeters=45.0,alarmRadiusMeters=50.0,depthGuardEnabled=true,shallowDepthAlarmMeters=2.5,windGuardEnabled=true,windWarningKnots=25.0,windAlarmKnots=35.0,windShiftEnabled=true,windShiftThresholdDegrees=70.0,candidateTrackDiameterMeters=31.0,candidateFittedRadiusMeters=25.0,candidateMaximumRodeMeters=39.0,candidateGpsMarginMeters=3.0,candidateRadialObservable=true,candidateObservabilityReason="OBSERVABLE");assertEquals(entity,BackupAnchorSessionV2.from(entity).toEntity())}
    @Test fun savedAnchorageRoundTripsAsIndependentSnapshot(){val value=SavedAnchorageEntity(id=7,name="Little Bay",latitude=-36.8,longitude=175.1,createdAt=1,updatedAt=2,visitCount=3,preferredAlarmRadiusMeters=50.0,rating=5,notes="Sheltered",sourceSessionId=99);assertEquals(value,BackupSavedAnchorageV2.from(value).toEntity())}
}
