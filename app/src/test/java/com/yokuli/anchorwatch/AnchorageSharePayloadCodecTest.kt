package com.yokuli.anchorwatch

import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AnchorageSharePayloadCodecTest {
    private val saved=SavedAnchorageEntity(
        name="小湾 · Little Bay",latitude=-36.8123456,longitude=174.7123456,createdAt=10,updatedAt=20,
        preferredAlarmRadiusMeters=55.0,typicalWaterDepthMeters=6.2,typicalRodeLengthMeters=42.0,
        seabedType=SeabedType.MUD_SAND.name,rating=4,notes="背风时较平静 — verify on arrival",
        sourceSessionId=999,coordinateSource=AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE.name,coordinateUncertaintyMeters=18.0,
    )

    @Test fun everyShareableFieldRoundTripsWithoutLeakingLocalIdentity(){
        val encoded=AnchorageSharePayloadCodec.encode(saved)
        val decoded=(AnchorageSharePayloadCodec.decode(encoded.uri) as AnchorageQrDecodeResult.Full).payload
        assertEquals(saved.name,decoded.name)
        assertEquals(saved.latitude,decoded.latitude,0.0)
        assertEquals(saved.notes,decoded.notes)
        assertEquals(saved.preferredAlarmRadiusMeters,decoded.preferredAlarmRadiusMeters)
        assertEquals(saved.coordinateSource,decoded.coordinateSource)
        val imported=AnchorageSharePayloadCodec.toEntity(decoded,100)
        assertEquals(0L,imported.id);assertNull(imported.sourceSessionId);assertNull(imported.lastVisitedAt);assertEquals(0,imported.visitCount)
    }

    @Test fun overlongNotesAreExplicitlyFlaggedAndBounded(){
        val encoded=AnchorageSharePayloadCodec.encode(saved.copy(notes="海".repeat(2_000)))
        assertTrue(encoded.textWasTruncated)
        assertTrue(encoded.payload.notes.length<=AnchorageSharePayloadCodec.MAX_NOTES_CHARS)
        assertTrue(encoded.uri.toByteArray().size<=AnchorageSharePayloadCodec.MAX_QR_URI_BYTES)
        assertTrue(AnchorageSharePayloadCodec.decode(encoded.uri) is AnchorageQrDecodeResult.Full)
        assertNotNull(QRCodeWriter().encode(encoded.uri,BarcodeFormat.QR_CODE,640,640,mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)))
    }

    @Test fun legacyMapAndPlainCoordinatesRemainReadable(){
        val legacy=AnchorageSharePayloadCodec.decode("https://www.google.com/maps/search/?api=1&query=-36.8%2C174.7") as AnchorageQrDecodeResult.Coordinate
        assertEquals(-36.8,legacy.latitude,0.0);assertEquals(174.7,legacy.longitude,0.0)
        assertTrue(AnchorageSharePayloadCodec.decode("-36.8, 174.7") is AnchorageQrDecodeResult.Coordinate)
        assertTrue(AnchorageSharePayloadCodec.decode("https://example.com") is AnchorageQrDecodeResult.Unsupported)
    }

    @Test fun invalidCoordinateAndNewerVersionAreRejected(){
        val invalid=savedPayloadUri(AnchorageSharePayloadV1(name="Bad",latitude=91.0,longitude=174.0))
        assertTrue(AnchorageSharePayloadCodec.decode(invalid) is AnchorageQrDecodeResult.Invalid)
        assertTrue(AnchorageSharePayloadCodec.decode(AnchorageSharePayloadCodec.encode(saved).uri.replace("v=1","v=3")) is AnchorageQrDecodeResult.UnsupportedVersion)
    }

    @Test fun v2KeepsPlaceSpotAndRegionContextWithoutVisitHistory(){
        val payload=AnchorageSharePayloadV2(placeName="Smokehouse Bay",placeType="BAY",regionDisplayPath=listOf("Port FitzRoy","Aotea / Great Barrier Island"),spotName="Inner mud",latitude=-36.18,longitude=175.34,preferredAlarmRadiusMeters=55.0,typicalWaterDepthMeters=7.2,typicalRodeLengthMeters=45.0,seabedType=SeabedType.MUD.name,coordinateSource=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name,approachNotes="Keep north of the reef",notes="Personal observation")
        val encoded=AnchorageSharePayloadCodec.encodeV2(payload)
        val decoded=(AnchorageSharePayloadCodec.decode(encoded.uri) as AnchorageQrDecodeResult.FullV2).payload
        assertEquals(payload,decoded);assertFalse(encoded.uri.contains("visit",ignoreCase=true));assertTrue(encoded.uri.toByteArray().size<=AnchorageSharePayloadCodec.MAX_QR_URI_BYTES)
    }

    @Test fun oversizedRawPayloadIsRejectedBeforeDecode(){
        assertTrue(AnchorageSharePayloadCodec.decode("x".repeat(AnchorageSharePayloadCodec.MAX_RAW_BYTES+1)) is AnchorageQrDecodeResult.Invalid)
    }

    @Test fun missingOrExplicitlyNullRequiredFieldsNeverEscapeAsAnException(){
        listOf("{}","{\"version\":1,\"name\":null,\"latitude\":-36.8,\"longitude\":174.7}").forEach{json->
            val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
            assertTrue(AnchorageSharePayloadCodec.decode("anchorwatch://anchorage?v=1&d=$encoded") is AnchorageQrDecodeResult.Invalid)
        }
    }

    private fun savedPayloadUri(value:AnchorageSharePayloadV1):String{
        val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(value).toByteArray())
        return "anchorwatch://anchorage?v=1&d=$encoded"
    }
}
