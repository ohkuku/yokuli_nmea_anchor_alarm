package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.output.NmeaRawTxConsolePolicy
import org.junit.Assert.*
import org.junit.Test

class NmeaRawTxConsolePolicyTest{
    private val lines=listOf(
        "12:00:00.000  [POSITION] \$GNRMC,123,A*00",
        "12:00:00.100  [HEADING] \$IIHDT,42.0,T*00",
        "12:00:00.200  [CANONICAL_FEED] \$SDDBT,,f,8.2,M*00",
    )

    @Test fun extractsLogicalStreamAndSentenceType(){
        assertEquals("HEADING",NmeaRawTxConsolePolicy.stream(lines[1]))
        assertEquals("HDT",NmeaRawTxConsolePolicy.sentenceType(lines[1]))
    }

    @Test fun filtersByStreamAndSentenceTypeIndependentlyOrTogether(){
        assertEquals(listOf(lines[1]),NmeaRawTxConsolePolicy.filter(lines,"heading",""))
        assertEquals(listOf(lines[2]),NmeaRawTxConsolePolicy.filter(lines,"","dbt"))
        assertTrue(NmeaRawTxConsolePolicy.filter(lines,"position","hdt").isEmpty())
    }

    @Test fun clearMarkerHidesOnlyTheCurrentUiHistory(){
        assertEquals(listOf(lines[2]),NmeaRawTxConsolePolicy.afterClearMarker(lines,lines[1]))
        assertEquals(lines,NmeaRawTxConsolePolicy.afterClearMarker(lines,"marker aged out of ring"))
    }
}
