package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.CoordinateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateParserTest {
    @Test fun acceptsCommaOrWhitespaceAndTrimsInput(){
        val comma=CoordinateParser.parse("  -36.812345, 174.712345 ").getOrThrow()
        val spaces=CoordinateParser.parse("-36.812345 174.712345").getOrThrow()
        assertEquals(comma,spaces)
    }
    @Test fun rejectsOutOfRangeAndMalformedCoordinates(){
        listOf("91, 1","0, -181","","NaN, 2","1,2,3","hello").forEach{assertTrue(it,CoordinateParser.parse(it).isFailure)}
    }
}
