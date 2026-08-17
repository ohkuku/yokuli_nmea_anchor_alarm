package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import com.yokuli.anchorwatch.domain.condition.TrueWindDirectionSource
import com.yokuli.anchorwatch.domain.condition.WindSpeedSource
import org.junit.Assert.*
import org.junit.Test

class LiveWindRepositoryTest{
    @Test fun trueSpeedWinsAndApparentFallbackCanBeDisabled(){
        val repository=LiveWindRepository()
        repository.accept(NmeaUpdate(apparentWindSpeedKnots=22.0,type="MWV"),1_000)
        assertEquals(WindSpeedSource.APPARENT,repository.state.value.speed(1_100,true)?.second)
        assertNull(repository.state.value.speed(1_100,false))
        repository.accept(NmeaUpdate(trueWindSpeedKnots=18.0,type="MWV"),1_200)
        assertEquals(WindSpeedSource.TRUE,repository.state.value.speed(1_300,true)?.second)
        assertEquals(18.0,repository.state.value.speed(1_300,true)?.first?.value?:Double.NaN,.001)
    }

    @Test fun coherentTrueAngleAndHdtWorkInEitherArrivalOrder(){
        val first=LiveWindRepository();first.accept(NmeaUpdate(trueHeading=200.0,type="HDT"),1_000);first.accept(NmeaUpdate(trueWindAngle=25.0,type="MWV"),2_500)
        assertEquals(225.0,first.state.value.direction(2_500)?.first?.value?:Double.NaN,.001)
        val second=LiveWindRepository();second.accept(NmeaUpdate(trueWindAngle=25.0,type="MWV"),1_000);second.accept(NmeaUpdate(trueHeading=200.0,type="HDT"),2_500)
        assertEquals(225.0,second.state.value.direction(2_500)?.first?.value?:Double.NaN,.001)
    }

    @Test fun staleHdtAndApparentAngleNeverCreateTrueDirection(){
        val repository=LiveWindRepository();repository.accept(NmeaUpdate(trueHeading=200.0,type="HDT"),1_000);repository.accept(NmeaUpdate(trueWindAngle=25.0,type="MWV"),9_000)
        assertNull(repository.state.value.direction(9_000))
        repository.clear();repository.accept(NmeaUpdate(apparentWindAngle=25.0,apparentWindSpeedKnots=15.0,type="MWV"),10_000)
        assertNull(repository.state.value.direction(10_000))
    }

    @Test fun freshMwdWinsOverASeparatelyArrivingDerivedPair(){
        val repository=LiveWindRepository();repository.accept(NmeaUpdate(trueWindDirection=210.0,type="MWD"),1_000);repository.accept(NmeaUpdate(trueHeading=120.0,type="HDT"),2_000);repository.accept(NmeaUpdate(trueWindAngle=40.0,type="MWV"),2_500)
        val direction=repository.state.value.direction(2_500)
        assertEquals(TrueWindDirectionSource.MWD,direction?.second);assertEquals(210.0,direction?.first?.value?:Double.NaN,.001)
    }
}
