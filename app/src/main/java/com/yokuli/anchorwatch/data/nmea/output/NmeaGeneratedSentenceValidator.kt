package com.yokuli.anchorwatch.data.nmea.output

import com.yokuli.anchorwatch.data.nmea.NmeaChecksum

/** Final boundary for App-generated NMEA 0183. Every normal and diagnostic
 * sentence must be one ASCII frame with checksum and CRLF, no longer than the
 * 82-byte NMEA 0183 sentence limit (including delimiters and terminator). */
object NmeaGeneratedSentenceValidator {
    const val MAX_SENTENCE_BYTES=82

    fun isValid(sentence:String):Boolean{
        if(!sentence.endsWith("\r\n")||sentence.dropLast(2).contains('\r')||sentence.dropLast(2).contains('\n'))return false
        if(sentence.any{it.code !in 0x20..0x7e&&it!='\r'&&it!='\n'})return false
        if(sentence.toByteArray(Charsets.US_ASCII).size>MAX_SENTENCE_BYTES)return false
        val body=sentence.dropLast(2)
        if(!body.startsWith("$")||body.count{it=='$'}!=1||body.count{it=='*'}!=1)return false
        val checksum=body.substringAfter('*',"")
        return checksum.length==2&&checksum.all{it.isDigit()||it.uppercaseChar() in 'A'..'F'}&&NmeaChecksum.validate(body,true)
    }
}
