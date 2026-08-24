package com.yokuli.anchorwatch.data.anchorage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.yokuli.anchorwatch.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity

data class AnchorageShareCardRow(val label:String,val value:String)
data class AnchorageShareCardModel(val coordinateQuality:String,val rows:List<AnchorageShareCardRow>,val notes:String)

/** Share text remains useful without Anchor Watch; the image QR carries V1 data. */
object AnchorageShareContent {
    const val BRANDING_LINE="Made aboard Yokuli"
    fun coordinates(latitude:Double,longitude:Double):String=
        String.format(Locale.US,"%.7f,%.7f",latitude,longitude)

    fun googleMapsUrl(latitude:Double,longitude:Double):String=
        "https://www.google.com/maps/search/?api=1&query=${coordinates(latitude,longitude)}"

    fun shareText(value:SavedAnchorageEntity):String=buildString{
        append(value.name)
        append('\n')
        append(coordinates(value.latitude,value.longitude))
        if(value.coordinateSource!=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name){
            append('\n')
            append(if(value.coordinateSource==AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE.name)"Approximate estimated-region centre" else "Approximate temporary watch reference")
            value.coordinateUncertaintyMeters?.let{append(" (±${it.toInt()} m)")}
        }
        value.preferredAlarmRadiusMeters?.let{append("\nSaved radius: ${it.toInt()} m")}
        value.typicalWaterDepthMeters?.let{append("\nDepth: ${"%.1f".format(Locale.US,it)} m")}
        value.typicalRodeLengthMeters?.let{append("\nRode: ${it.toInt()} m")}
        if(value.seabedType!=SeabedType.UNKNOWN.name)append("\nSeabed: ${seabedText(value)}")
        value.rating?.let{append("\nRating: $it/5")}
        if(value.notes.isNotBlank()){
            append("\n\n")
            append(value.notes.trim())
        }
        append("\n\n")
        append(googleMapsUrl(value.latitude,value.longitude))
    }

    fun cardModel(value:SavedAnchorageEntity,chinese:Boolean):AnchorageShareCardModel{
        val quality=when(value.coordinateSource){
            AnchorageCoordinateSource.CONFIRMED_ANCHOR.name->if(chinese)"收藏的锚点位置" else "Saved anchor position"
            AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE.name->if(chinese)"估算参考位置" else "Approximate estimated reference"
            else->if(chinese)"临时参考位置" else "Approximate temporary reference"
        }+value.coordinateUncertaintyMeters?.let{if(chinese)" · ±${it.toInt()} 米" else " · ±${it.toInt()} m"}.orEmpty()
        val rows=buildList{
            value.preferredAlarmRadiusMeters?.let{add(AnchorageShareCardRow(if(chinese)"收藏范围" else "Saved radius","${it.toInt()} m"))}
            value.typicalWaterDepthMeters?.let{add(AnchorageShareCardRow(if(chinese)"水深" else "Depth","${"%.1f".format(Locale.US,it)} m"))}
            value.typicalRodeLengthMeters?.let{add(AnchorageShareCardRow(if(chinese)"锚链 / 锚缆" else "Rode","${it.toInt()} m"))}
            if(value.seabedType!=SeabedType.UNKNOWN.name)add(AnchorageShareCardRow(if(chinese)"底质" else "Seabed",localizedSeabed(value,chinese)))
            value.rating?.let{add(AnchorageShareCardRow(if(chinese)"评分" else "Rating","★".repeat(it)+"☆".repeat(5-it)))}
        }
        return AnchorageShareCardModel(quality,rows,value.notes.trim())
    }

    private fun seabedText(value:SavedAnchorageEntity)=if(value.seabedType==SeabedType.OTHER.name)value.customSeabedText?.ifBlank{null}?:"Other" else value.seabedType.lowercase().replace('_',' ').replaceFirstChar{it.titlecase()}
    private fun localizedSeabed(value:SavedAnchorageEntity,chinese:Boolean):String{
        if(!chinese)return seabedText(value)
        return when(runCatching{SeabedType.valueOf(value.seabedType)}.getOrDefault(SeabedType.UNKNOWN)){SeabedType.UNKNOWN->"未知";SeabedType.MUD->"泥";SeabedType.SAND->"沙";SeabedType.MUD_SAND->"泥沙";SeabedType.GRAVEL->"砾石";SeabedType.ROCK->"岩石";SeabedType.WEED->"水草";SeabedType.SHELL->"贝壳";SeabedType.OTHER->value.customSeabedText?.ifBlank{null}?:"其他"}
    }
}

/** Builds a self-contained PNG locally; no coordinate or note is sent to a QR service. */
@Singleton
class AnchorageQrImageGenerator @Inject constructor(
    @ApplicationContext private val context:Context,
){
    fun generate(value:SavedAnchorageEntity,chinese:Boolean):File{
        val encoded=AnchorageSharePayloadCodec.encode(value)
        val model=AnchorageShareContent.cardModel(value,chinese)
        val matrix=QRCodeWriter().encode(
            encoded.uri,
            BarcodeFormat.QR_CODE,
            QR_SIZE,
            QR_SIZE,
            mapOf(EncodeHintType.MARGIN to 3,EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        var qr:Bitmap?=null
        var image:Bitmap?=null
        var logo:Bitmap?=null
        return try {
            val qrBitmap=Bitmap.createBitmap(QR_SIZE,QR_SIZE,Bitmap.Config.ARGB_8888).also{qr=it}
            for(y in 0 until QR_SIZE)for(x in 0 until QR_SIZE){
                qrBitmap.setPixel(x,y,if(matrix[x,y])Color.rgb(10,49,57)else Color.WHITE)
            }

            val imageBitmap=Bitmap.createBitmap(IMAGE_WIDTH,IMAGE_HEIGHT,Bitmap.Config.ARGB_8888).also{image=it}
            val canvas=Canvas(imageBitmap)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(242,250,250))
            paint.color=Color.rgb(11,105,118)
            canvas.drawRect(0f,0f,IMAGE_WIDTH.toFloat(),HEADER_HEIGHT.toFloat(),paint)
            val logoBitmap=requireNotNull(BitmapFactory.decodeResource(context.resources,R.drawable.anchor_watch_logo)){
                "Anchor Watch logo could not be decoded."
            }.also{logo=it}
            canvas.drawBitmap(logoBitmap,null,RectF(30f,18f,154f,142f),paint)
        paint.color=Color.WHITE
        paint.textSize=48f
        paint.isFakeBoldText=true
        canvas.drawText("Anchor Watch",180f,74f,paint)
        paint.isFakeBoldText=false
        paint.textSize=28f
        canvas.drawText(AnchorageShareContent.BRANDING_LINE,180f,120f,paint)

        paint.color=Color.rgb(12,46,52)
        paint.textSize=50f
        paint.isFakeBoldText=true
        canvas.drawText(value.name.take(36),PADDING.toFloat(),220f,paint)
        paint.isFakeBoldText=false
        paint.textSize=31f
        canvas.drawText(AnchorageShareContent.coordinates(value.latitude,value.longitude),PADDING.toFloat(),270f,paint)
        paint.textSize=26f
        paint.color=Color.rgb(11,105,118)
        canvas.drawText(model.coordinateQuality,PADDING.toFloat(),312f,paint)

        var detailsY=365f
        paint.color=Color.rgb(73,91,95)
        paint.textSize=27f
        model.rows.forEach{row->
            canvas.drawText(row.label,PADDING.toFloat(),detailsY,paint)
            paint.textAlign=Paint.Align.RIGHT;paint.isFakeBoldText=true;paint.color=Color.rgb(12,46,52)
            canvas.drawText(row.value,(IMAGE_WIDTH-PADDING).toFloat(),detailsY,paint)
            paint.textAlign=Paint.Align.LEFT;paint.isFakeBoldText=false;paint.color=Color.rgb(73,91,95);detailsY+=42f
        }
        if(model.notes.isNotBlank()){
            paint.color=Color.rgb(12,46,52);paint.textSize=27f;paint.isFakeBoldText=true
            canvas.drawText(if(chinese)"说明" else "Notes",PADDING.toFloat(),580f,paint)
            paint.isFakeBoldText=false;paint.textSize=24f;paint.color=Color.rgb(73,91,95)
            drawWrapped(canvas,model.notes,PADDING.toFloat(),616f,(IMAGE_WIDTH-PADDING*2).toFloat(),31f,4,paint)
        }

        val left=(IMAGE_WIDTH-QR_SIZE)/2f
        canvas.drawBitmap(qrBitmap,left,QR_TOP.toFloat(),paint)
        paint.textAlign=Paint.Align.CENTER
        paint.color=Color.rgb(11,105,118)
        paint.textSize=29f
        paint.isFakeBoldText=true
        canvas.drawText(if(chinese)"使用 Anchor Watch 扫码查看或收藏" else "Scan with Anchor Watch to view or save",IMAGE_WIDTH/2f,1442f,paint)
        paint.isFakeBoldText=false
        paint.color=Color.rgb(73,91,95)
        paint.textSize=21f
        if(encoded.textWasTruncated)canvas.drawText(if(chinese)"二维码中的超长文字已缩短；分享文字仍保留完整内容。" else "Long text was shortened in the QR; the shared text remains complete.",IMAGE_WIDTH/2f,1483f,paint)
        paint.textAlign=Paint.Align.LEFT
        drawWrapped(canvas,if(chinese)"仅为个人收藏参考。下锚前请重新确认当前水深、交通、天气和周围环境。" else "Personal saved reference only. Check current depth, traffic, weather and surroundings before anchoring.",PADDING.toFloat(),1552f,(IMAGE_WIDTH-PADDING*2).toFloat(),30f,3,paint)

        val directory=File(context.cacheDir,"anchorage-shares").apply{mkdirs()}
        val file=File(directory,"anchorage-${value.id.takeIf{it>0}?:value.createdAt}.png")
        file.outputStream().use{output->
            check(imageBitmap.compress(Bitmap.CompressFormat.PNG,100,output)){"Anchorage share image could not be encoded."}
        }
        file
        } finally {
            logo?.let{if(!it.isRecycled)it.recycle()}
            image?.let{if(!it.isRecycled)it.recycle()}
            qr?.let{if(!it.isRecycled)it.recycle()}
        }
    }

    private fun drawWrapped(canvas:Canvas,text:String,x:Float,firstBaseline:Float,width:Float,lineHeight:Float,maxLines:Int,paint:Paint){
        var remaining=text.replace('\n',' ').trim();var baseline=firstBaseline
        repeat(maxLines){index->
            if(remaining.isEmpty())return
            var count=paint.breakText(remaining,true,width,null).coerceAtLeast(1)
            if(count<remaining.length){val whitespace=remaining.substring(0,count).indexOfLast{it.isWhitespace()};if(whitespace>count/2)count=whitespace}
            var line=remaining.substring(0,count).trim()
            remaining=remaining.substring(count).trimStart()
            if(index==maxLines-1&&remaining.isNotEmpty()){while(paint.measureText("$line…")>width&&line.isNotEmpty())line=line.dropLast(1);line+="…"}
            canvas.drawText(line,x,baseline,paint);baseline+=lineHeight
        }
    }

    private companion object{
        const val IMAGE_WIDTH=1080
        const val IMAGE_HEIGHT=1700
        const val QR_SIZE=640
        const val QR_TOP=740
        const val HEADER_HEIGHT=160
        const val PADDING=72
    }
}

@Singleton
class AnchorageV2QrImageGenerator @Inject constructor(@ApplicationContext private val context:Context){
    fun generate(place:AnchoragePlaceEntity,spot:AnchorageSpotEntity,regionPath:List<String>):File{
        val payload=AnchorageSharePayloadV2(placeName=place.displayName,placeType=place.placeType,regionDisplayPath=regionPath,spotName=spot.name,latitude=spot.latitude,longitude=spot.longitude,preferredAlarmRadiusMeters=spot.preferredAlarmRadiusMeters,typicalWaterDepthMeters=spot.typicalWaterDepthMeters,typicalRodeLengthMeters=spot.typicalRodeLengthMeters,seabedType=spot.seabedType,customSeabedText=spot.customSeabedText,coordinateSource=spot.coordinateSource,coordinateUncertaintyMeters=spot.coordinateUncertaintyMeters,approachNotes=spot.approachNotes,notes=spot.personalNotes)
        val encoded=AnchorageSharePayloadCodec.encodeV2(payload)
        val matrix=QRCodeWriter().encode(encoded.uri,BarcodeFormat.QR_CODE,720,720,mapOf(EncodeHintType.MARGIN to 3,EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val image=Bitmap.createBitmap(1080,1380,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        return try{
            canvas.drawColor(Color.rgb(242,250,250));paint.color=Color.rgb(11,105,118);canvas.drawRect(0f,0f,1080f,170f,paint)
            BitmapFactory.decodeResource(context.resources,R.drawable.anchor_watch_logo)?.let{logo->canvas.drawBitmap(logo,null,RectF(30f,20f,160f,150f),paint);logo.recycle()}
            paint.color=Color.WHITE;paint.textSize=48f;paint.isFakeBoldText=true;canvas.drawText("Anchor Watch",190f,78f,paint);paint.textSize=27f;paint.isFakeBoldText=false;canvas.drawText(AnchorageShareContent.BRANDING_LINE,190f,125f,paint)
            paint.color=Color.rgb(12,46,52);paint.textSize=48f;paint.isFakeBoldText=true;canvas.drawText(place.displayName.take(34),60f,245f,paint);paint.textSize=32f;paint.isFakeBoldText=false;canvas.drawText(spot.name.take(48),60f,300f,paint)
            val qr=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888);for(y in 0 until 720)for(x in 0 until 720)qr.setPixel(x,y,if(matrix[x,y])Color.rgb(10,49,57)else Color.WHITE);canvas.drawBitmap(qr,180f,355f,paint);qr.recycle()
            paint.textAlign=Paint.Align.CENTER;paint.color=Color.rgb(11,105,118);paint.textSize=27f;paint.isFakeBoldText=true;canvas.drawText("Scan with Anchor Watch",540f,1130f,paint);paint.isFakeBoldText=false;paint.textSize=24f;paint.color=Color.rgb(73,91,95);canvas.drawText(AnchorageShareContent.coordinates(spot.latitude,spot.longitude),540f,1180f,paint);canvas.drawText("Personal reference · verify conditions before anchoring",540f,1240f,paint)
            val directory=File(context.cacheDir,"anchorage-shares").apply{mkdirs()};File(directory,"anchorage-place-${place.id}-spot-${spot.id}.png").also{file->file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
        }finally{image.recycle()}
    }
}
