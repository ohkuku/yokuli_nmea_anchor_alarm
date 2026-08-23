package com.yokuli.anchorwatch

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageQrBitmapDecoderTest{
    @Test fun galleryBitmapDecoderReadsTheSamePayloadAsTheCameraAnalyzer(){
        val payload="https://anchor.yokuli.app/a?v=1&p=test"
        val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,500,500)
        val bitmap=Bitmap.createBitmap(matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(matrix.width*matrix.height){index->if(matrix[index%matrix.width,index/matrix.width])0xff000000.toInt() else 0xffffffff.toInt()}
        bitmap.setPixels(pixels,0,matrix.width,0,0,matrix.width,matrix.height)
        assertEquals(payload,AnchorageQrBitmapDecoder.decode(bitmap))
        bitmap.recycle()
    }
}
