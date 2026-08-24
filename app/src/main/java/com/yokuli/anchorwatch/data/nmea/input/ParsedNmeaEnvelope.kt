package com.yokuli.anchorwatch.data.nmea.input

import com.yokuli.anchorwatch.data.nmea.NmeaUpdate

data class ParsedNmeaEnvelope(
    val rawSentence:String,
    val talkerId:String,
    val sentenceType:String,
    val fullSentenceId:String,
    val receivedElapsedRealtime:Long,
    val update:NmeaUpdate,
)
