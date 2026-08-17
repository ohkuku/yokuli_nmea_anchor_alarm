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
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Share payloads contain only the saved coordinate and user-entered description. */
object AnchorageShareContent {
    const val BRANDING_LINE="Developed on SV Yokuli"
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
        if(value.notes.isNotBlank()){
            append("\n\n")
            append(value.notes.trim())
        }
        append("\n\n")
        append(googleMapsUrl(value.latitude,value.longitude))
    }
}

/** Builds a self-contained PNG locally; no coordinate or note is sent to a QR service. */
@Singleton
class AnchorageQrImageGenerator @Inject constructor(
    @ApplicationContext private val context:Context,
){
    fun generate(value:SavedAnchorageEntity,chinese:Boolean):File{
        val url=AnchorageShareContent.googleMapsUrl(value.latitude,value.longitude)
        val matrix=QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            QR_SIZE,
            QR_SIZE,
            mapOf(EncodeHintType.MARGIN to 2,EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val qr=Bitmap.createBitmap(QR_SIZE,QR_SIZE,Bitmap.Config.ARGB_8888)
        for(y in 0 until QR_SIZE)for(x in 0 until QR_SIZE){
            qr.setPixel(x,y,if(matrix[x,y])Color.rgb(10,49,57)else Color.WHITE)
        }

        val image=Bitmap.createBitmap(IMAGE_WIDTH,IMAGE_HEIGHT,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(242,250,250))
        paint.color=Color.rgb(11,105,118)
        canvas.drawRect(0f,0f,IMAGE_WIDTH.toFloat(),HEADER_HEIGHT.toFloat(),paint)
        val logo=BitmapFactory.decodeResource(context.resources,R.drawable.anchor_watch_logo)
        canvas.drawBitmap(logo,null,RectF(30f,18f,154f,142f),paint)
        paint.color=Color.WHITE
        paint.textSize=50f
        paint.isFakeBoldText=true
        canvas.drawText("Anchor Watch",180f,74f,paint)
        paint.isFakeBoldText=false
        paint.textSize=28f
        canvas.drawText(AnchorageShareContent.BRANDING_LINE,180f,120f,paint)

        paint.color=Color.rgb(12,46,52)
        paint.textSize=50f
        paint.isFakeBoldText=true
        canvas.drawText(value.name.take(32),PADDING.toFloat(),220f,paint)
        paint.isFakeBoldText=false
        paint.textSize=31f
        canvas.drawText(AnchorageShareContent.coordinates(value.latitude,value.longitude),PADDING.toFloat(),270f,paint)

        val left=(IMAGE_WIDTH-QR_SIZE)/2f
        canvas.drawBitmap(qr,left,315f,paint)
        paint.textAlign=Paint.Align.CENTER
        paint.color=Color.rgb(11,105,118)
        paint.textSize=29f
        paint.isFakeBoldText=true
        canvas.drawText(if(chinese)"扫码在 Google 地图中查看" else "Scan to open in Google Maps",IMAGE_WIDTH/2f,1255f,paint)
        paint.isFakeBoldText=false
        paint.color=Color.rgb(73,91,95)
        paint.textSize=23f
        canvas.drawText(
            if(chinese)"个人记录，仅供参考；请自行核实锚地安全。" else "Personal note only. Verify anchorage safety yourself.",
            IMAGE_WIDTH/2f,
            1305f,
            paint,
        )

        val directory=File(context.cacheDir,"anchorage-shares").apply{mkdirs()}
        val file=File(directory,"anchorage-${value.id.takeIf{it>0}?:value.createdAt}.png")
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
        qr.recycle()
        logo.recycle()
        return file
    }

    private companion object{
        const val IMAGE_WIDTH=1080
        const val IMAGE_HEIGHT=1380
        const val QR_SIZE=840
        const val HEADER_HEIGHT=160
        const val PADDING=72
    }
}
