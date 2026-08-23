package com.yokuli.anchorwatch.data.anchorage

import com.google.gson.Gson
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

data class AnchorageSharePayloadV1(
    val version:Int=1,
    val name:String,
    val latitude:Double,
    val longitude:Double,
    val preferredAlarmRadiusMeters:Double?=null,
    val typicalWaterDepthMeters:Double?=null,
    val typicalRodeLengthMeters:Double?=null,
    val seabedType:String=SeabedType.UNKNOWN.name,
    val customSeabedText:String?=null,
    val rating:Int?=null,
    val notes:String="",
    val coordinateSource:String=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name,
    val coordinateUncertaintyMeters:Double?=null,
)

data class EncodedAnchorageShareV1(
    val uri:String,
    val payload:AnchorageSharePayloadV1,
    val textWasTruncated:Boolean,
)

sealed interface AnchorageQrDecodeResult {
    data class Full(val payload:AnchorageSharePayloadV1):AnchorageQrDecodeResult
    data class Coordinate(val latitude:Double,val longitude:Double):AnchorageQrDecodeResult
    data class UnsupportedVersion(val version:Int?):AnchorageQrDecodeResult
    data class Invalid(val reason:String):AnchorageQrDecodeResult
    data object Unsupported:AnchorageQrDecodeResult
}

/** Versioned, local-only anchorage QR contract shared by the writer and scanner. */
object AnchorageSharePayloadCodec {
    private val gson=Gson()

    fun encode(value:SavedAnchorageEntity):EncodedAnchorageShareV1{
        val originalName=value.name.trim()
        val originalNotes=value.notes
        val originalCustom=value.customSeabedText
        var payload=AnchorageSharePayloadV1(
            name=originalName.codePointPrefix(MAX_NAME_CHARS),
            latitude=value.latitude,
            longitude=value.longitude,
            preferredAlarmRadiusMeters=value.preferredAlarmRadiusMeters,
            typicalWaterDepthMeters=value.typicalWaterDepthMeters,
            typicalRodeLengthMeters=value.typicalRodeLengthMeters,
            seabedType=value.seabedType,
            customSeabedText=originalCustom?.codePointPrefix(MAX_CUSTOM_SEABED_CHARS),
            rating=value.rating,
            notes=originalNotes.codePointPrefix(MAX_NOTES_CHARS),
            coordinateSource=value.coordinateSource,
            coordinateUncertaintyMeters=value.coordinateUncertaintyMeters,
        )
        validate(payload)?.let{throw IllegalArgumentException(it)}
        fun pack(candidate:AnchorageSharePayloadV1):Pair<ByteArray,String>{
            val json=gson.toJson(candidate).toByteArray(Charsets.UTF_8)
            require(json.size<=MAX_DECODED_BYTES){"Anchorage details are too large for a QR code."}
            val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json)
            return json to "$SCHEME://$HOST?v=$VERSION&d=$encoded"
        }
        var encodedPayload=pack(payload)
        if(encodedPayload.second.toByteArray(Charsets.UTF_8).size>MAX_QR_URI_BYTES){
            // Version-40 QR at error-correction M holds much less than the
            // decoder's defensive 8 KiB input ceiling. Find the largest whole
            // Unicode-code-point prefix that fits the real share-card budget.
            val note=payload.notes;var low=0;var high=note.codePointCount(0,note.length)
            while(low<high){val mid=(low+high+1)/2;val candidate=payload.copy(notes=note.codePointPrefix(mid));if(pack(candidate).second.toByteArray(Charsets.UTF_8).size<=MAX_QR_URI_BYTES)low=mid else high=mid-1}
            payload=payload.copy(notes=note.codePointPrefix(low));encodedPayload=pack(payload)
        }
        require(encodedPayload.second.toByteArray(Charsets.UTF_8).size<=MAX_QR_URI_BYTES){"Anchorage details cannot fit in a QR code."}
        val truncated=payload.name!=originalName||payload.notes!=originalNotes||payload.customSeabedText!=originalCustom
        return EncodedAnchorageShareV1(encodedPayload.second,payload,truncated)
    }

    fun decode(raw:String):AnchorageQrDecodeResult{
        val text=raw.trim()
        if(text.isEmpty())return AnchorageQrDecodeResult.Invalid("The QR code is empty.")
        if(text.toByteArray(Charsets.UTF_8).size>MAX_RAW_BYTES)return AnchorageQrDecodeResult.Invalid("The QR payload is too large.")
        val uri=runCatching{URI(text)}.getOrNull()
        if(uri?.scheme.equals(SCHEME,true)&&uri?.host.equals(HOST,true)){
            val query=parseQuery(uri?.rawQuery)
            val version=query["v"]?.toIntOrNull()
            if(version!=VERSION)return AnchorageQrDecodeResult.UnsupportedVersion(version)
            val data=query["d"]?:return AnchorageQrDecodeResult.Invalid("The anchorage data is missing.")
            val bytes=runCatching{Base64.getUrlDecoder().decode(data)}.getOrElse{return AnchorageQrDecodeResult.Invalid("The anchorage data is damaged.")}
            if(bytes.size>MAX_DECODED_BYTES)return AnchorageQrDecodeResult.Invalid("The decoded anchorage is too large.")
            val payload=runCatching{gson.fromJson(String(bytes,Charsets.UTF_8),AnchorageSharePayloadV1::class.java)}.getOrNull()?:return AnchorageQrDecodeResult.Invalid("The anchorage data is not valid JSON.")
            // Gson can construct a Kotlin data class with null in a declared non-null
            // property when a foreign/malformed payload omits that field. Keep all
            // untrusted validation behind the result boundary so scanning can never
            // crash the app before the user reaches the confirmation screen.
            val validation=runCatching{validate(payload)}.getOrElse{return AnchorageQrDecodeResult.Invalid("The anchorage data contains missing or invalid fields.")}
            validation?.let{return AnchorageQrDecodeResult.Invalid(it)}
            return AnchorageQrDecodeResult.Full(payload)
        }
        parseGoogleMapsCoordinate(uri)?.let{return AnchorageQrDecodeResult.Coordinate(it.first,it.second)}
        parseCoordinate(text)?.let{return AnchorageQrDecodeResult.Coordinate(it.first,it.second)}
        return AnchorageQrDecodeResult.Unsupported
    }

    fun toEntity(payload:AnchorageSharePayloadV1,now:Long=System.currentTimeMillis())=SavedAnchorageEntity(
        id=0,name=payload.name,latitude=payload.latitude,longitude=payload.longitude,createdAt=now,updatedAt=now,
        preferredAlarmRadiusMeters=payload.preferredAlarmRadiusMeters,typicalWaterDepthMeters=payload.typicalWaterDepthMeters,
        typicalRodeLengthMeters=payload.typicalRodeLengthMeters,seabedType=payload.seabedType,customSeabedText=payload.customSeabedText,
        rating=payload.rating,notes=payload.notes,sourceSessionId=null,coordinateSource=payload.coordinateSource,
        coordinateUncertaintyMeters=payload.coordinateUncertaintyMeters,
    )

    fun coordinateEntity(latitude:Double,longitude:Double,name:String,now:Long=System.currentTimeMillis())=SavedAnchorageEntity(
        id=0,name=name.trim(),latitude=latitude,longitude=longitude,createdAt=now,updatedAt=now,sourceSessionId=null,
        coordinateSource=AnchorageCoordinateSource.TEMPORARY_WATCH_REFERENCE.name,
    )

    private fun parseGoogleMapsCoordinate(uri:URI?):Pair<Double,Double>?{
        if(uri==null||uri.scheme !in setOf("http","https"))return null
        val host=uri.host?.lowercase()?:return null
        if(host!="google.com"&&!host.endsWith(".google.com"))return null
        val query=parseQuery(uri.rawQuery)
        return parseCoordinate(query["query"]?:query["q"]?:return null)
    }

    private fun parseQuery(raw:String?):Map<String,String> = raw.orEmpty().split('&').mapNotNull{part->
        val index=part.indexOf('=');if(index<0)null else runCatching{URLDecoder.decode(part.substring(0,index),Charsets.UTF_8.name()) to URLDecoder.decode(part.substring(index+1),Charsets.UTF_8.name())}.getOrNull()
    }.toMap()

    private fun parseCoordinate(value:String):Pair<Double,Double>?{
        val match=COORDINATE.matchEntire(value.trim())?:return null
        val latitude=match.groupValues[1].toDoubleOrNull()?:return null
        val longitude=match.groupValues[2].toDoubleOrNull()?:return null
        return (latitude to longitude).takeIf{validCoordinate(latitude,longitude)}
    }

    private fun validate(value:AnchorageSharePayloadV1):String?=when{
        value.version!=VERSION->"This anchorage uses an unsupported format version."
        value.name.isBlank()||value.name.codePointCount(0,value.name.length)>MAX_NAME_CHARS->"The anchorage name is invalid."
        !validCoordinate(value.latitude,value.longitude)->"The anchorage coordinate is invalid."
        !validOptional(value.preferredAlarmRadiusMeters,0.0,10_000.0,false)->"The saved radius is invalid."
        !validOptional(value.typicalWaterDepthMeters,0.0,12_000.0,true)->"The saved depth is invalid."
        !validOptional(value.typicalRodeLengthMeters,0.0,20_000.0,true)->"The saved rode length is invalid."
        value.seabedType !in SeabedType.entries.map{it.name}->"The seabed type is not recognized."
        (value.customSeabedText?.let{it.codePointCount(0,it.length)}?:0)>MAX_CUSTOM_SEABED_CHARS->"The seabed description is too long."
        value.rating!=null&&value.rating !in 1..5->"The rating is invalid."
        value.notes.codePointCount(0,value.notes.length)>MAX_NOTES_CHARS->"The notes are too long."
        value.coordinateSource !in AnchorageCoordinateSource.entries.map{it.name}->"The coordinate quality is not recognized."
        !validOptional(value.coordinateUncertaintyMeters,0.0,100_000.0,true)->"The coordinate uncertainty is invalid."
        else->null
    }

    private fun validCoordinate(latitude:Double,longitude:Double)=latitude.isFinite()&&longitude.isFinite()&&latitude in -90.0..90.0&&longitude in -180.0..180.0
    private fun validOptional(value:Double?,minimum:Double,maximum:Double,allowZero:Boolean)=value==null||(value.isFinite()&&value in minimum..maximum&&(allowZero||value>minimum))
    private fun String.codePointPrefix(maximum:Int):String{val count=codePointCount(0,length);if(count<=maximum)return this;return substring(0,offsetByCodePoints(0,maximum))}

    const val VERSION=1
    const val MAX_NAME_CHARS=120
    const val MAX_NOTES_CHARS=1_500
    const val MAX_CUSTOM_SEABED_CHARS=120
    const val MAX_RAW_BYTES=16*1024
    const val MAX_DECODED_BYTES=8*1024
    /** Conservative byte-mode budget below QR version 40 / correction M. */
    const val MAX_QR_URI_BYTES=2_200
    private const val SCHEME="anchorwatch"
    private const val HOST="anchorage"
    private val COORDINATE=Regex("^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*,\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))$")
}
