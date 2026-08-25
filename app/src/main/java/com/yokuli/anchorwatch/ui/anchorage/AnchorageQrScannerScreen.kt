package com.yokuli.anchorwatch

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnchorageQrScannerScreen(onClose:()->Unit,onSave:(SavedAnchorageEntity)->Unit,onSaveV2:(AnchorageSharePayloadV2)->Unit={payload->onSave(payload.toLegacyImportEntity())}){
    val context=LocalContext.current
    var permissionGranted by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var permissionRequested by remember{mutableStateOf(false)}
    var result by remember{mutableStateOf<AnchorageQrDecodeResult?>(null)}
    var cameraError by remember{mutableStateOf<String?>(null)}
    var torchEnabled by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val galleryDecodeError=tr("No readable QR code was found in that image.","图片中没有找到可读取的二维码。")
    fun decodeGallery(uri:Uri?){
        if(uri==null)return
        scope.launch{
            val raw=withContext(Dispatchers.IO){AnchorageQrBitmapDecoder.decodeUri(context,uri)}
            if(raw==null)cameraError=galleryDecodeError else result=AnchorageSharePayloadCodec.decode(raw)
        }
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent(),::decodeGallery)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->permissionRequested=true;permissionGranted=granted}
    LaunchedEffect(Unit){if(!permissionGranted)permission.launch(Manifest.permission.CAMERA)}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
        Column(Modifier.fillMaxSize().statusBarsPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClose){Icon(Icons.Default.Close,tr("Close scanner","关闭扫码"))}
                Text(tr("Scan anchorage QR","扫描锚地二维码"),style=MaterialTheme.typography.titleLarge)
            }
            when{
                !permissionGranted->CameraPermissionMessage(permissionRequested,{permission.launch(Manifest.permission.CAMERA)},{gallery.launch("image/*")},onClose)
                result!=null->AnchorageQrImportPreview(requireNotNull(result),onClose,{result=null;cameraError=null},onSave,onSaveV2)
                else->Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally){
                    Box(Modifier.fillMaxWidth().weight(1f)){
                        AnchorageCameraPreview(torchEnabled,onDecoded={raw->result=AnchorageSharePayloadCodec.decode(raw)},onError={cameraError=it})
                        Box(Modifier.align(Alignment.Center).size(250.dp).border(3.dp,Color.White,MaterialTheme.shapes.medium))
                        FilledTonalIconButton({torchEnabled=!torchEnabled},Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("anchorage_qr_torch")){Icon(if(torchEnabled)Icons.Default.FlashOn else Icons.Default.FlashOff,tr("Toggle torch","切换闪光灯"))}
                    }
                    cameraError?.let{Text(it,Modifier.padding(horizontal=20.dp,vertical=6.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                    Text(tr("Point the camera at a Boat Watch anchorage QR.","将相机对准 Boat Watch 锚地二维码。"),Modifier.padding(18.dp),style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton({gallery.launch("image/*")},Modifier.fillMaxWidth().padding(horizontal=18.dp).testTag("anchorage_qr_gallery")){Icon(Icons.Default.PhotoLibrary,null);Spacer(Modifier.width(8.dp));Text(tr("Choose QR image","选择二维码图片"))}
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable private fun CameraPermissionMessage(requested:Boolean,retry:()->Unit,gallery:()->Unit,close:()->Unit){
    Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Icon(Icons.Default.QrCodeScanner,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp));Text(tr("Camera permission is required","需要相机权限"),style=MaterialTheme.typography.titleLarge)
        Text(if(requested)tr("Permission was not granted. Camera frames are processed only on this device and are never uploaded.","尚未获得权限。相机画面只在本机处理，不会上传。") else tr("Boat Watch asks for camera access only when you open the QR scanner.","Boat Watch 只会在你打开扫码功能时申请相机权限。"),Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium)
        Button(retry,Modifier.fillMaxWidth().testTag("anchorage_qr_permission_retry")){Text(tr("Try again","重试"))}
        OutlinedButton(gallery,Modifier.fillMaxWidth().testTag("anchorage_qr_gallery_without_camera")){Text(tr("Choose QR image instead","改为选择二维码图片"))}
        TextButton(close){Text(tr("Cancel","取消"))}
    }
}

@Composable private fun AnchorageQrImportPreview(result:AnchorageQrDecodeResult,close:()->Unit,scanAgain:()->Unit,save:(SavedAnchorageEntity)->Unit,saveV2:(AnchorageSharePayloadV2)->Unit){
    when(result){
        is AnchorageQrDecodeResult.Full->{
            val payload=result.payload
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(tr("Shared anchorage","收到的共享锚地"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                Text(payload.name,style=MaterialTheme.typography.headlineSmall)
                Text("${"%.7f".format(payload.latitude)}, ${"%.7f".format(payload.longitude)}")
                ImportLine(tr("Saved radius","收藏范围"),payload.preferredAlarmRadiusMeters?.let{"${it.toInt()} m"})
                ImportLine(tr("Depth","水深"),payload.typicalWaterDepthMeters?.let{"%.1f m".format(it)})
                ImportLine(tr("Rode","锚链 / 锚缆"),payload.typicalRodeLengthMeters?.let{"${it.toInt()} m"})
                ImportLine(tr("Seabed","底质"),payload.seabedType.lowercase().replace('_',' '))
                ImportLine(tr("Rating","评分"),payload.rating?.let{"★".repeat(it)})
                if(payload.coordinateSource!=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name)Text(tr("Approximate shared reference${payload.coordinateUncertaintyMeters?.let{" · ±${it.toInt()} m"}.orEmpty()}","共享的估算参考位置${payload.coordinateUncertaintyMeters?.let{" · ±${it.toInt()} 米"}.orEmpty()}"),color=MaterialTheme.colorScheme.tertiary)
                if(payload.notes.isNotBlank()){HorizontalDivider();Text(tr("Notes","说明"),style=MaterialTheme.typography.labelLarge);Text(payload.notes)}
                SharedReferenceSafetyCopy()
                Button({save(AnchorageSharePayloadCodec.toEntity(payload));close()},Modifier.fillMaxWidth().testTag("save_scanned_anchorage")){Text(tr("Save anchorage","收藏锚地"))}
                OutlinedButton(scanAgain,Modifier.fillMaxWidth()){Text(tr("Scan again","重新扫描"))}
                TextButton(close,Modifier.fillMaxWidth()){Text(tr("Cancel","取消"))}
            }
        }
        is AnchorageQrDecodeResult.FullV2->{
            val payload=result.payload
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(tr("Shared Place and Spot","收到的地点与锚点"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                Text(payload.placeName,style=MaterialTheme.typography.headlineSmall)
                Text("${tr("Spot","锚点")}: ${payload.spotName}",style=MaterialTheme.typography.titleMedium)
                if(payload.regionDisplayPath.isNotEmpty())Text(payload.regionDisplayPath.joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.7f".format(payload.latitude)}, ${"%.7f".format(payload.longitude)}")
                ImportLine(tr("Saved radius","收藏范围"),payload.preferredAlarmRadiusMeters?.let{"${it.toInt()} m"})
                ImportLine(tr("Depth","水深"),payload.typicalWaterDepthMeters?.let{"%.1f m".format(it)})
                ImportLine(tr("Rode","锚链 / 锚缆"),payload.typicalRodeLengthMeters?.let{"${it.toInt()} m"})
                ImportLine(tr("Seabed","底质"),payload.seabedType.lowercase().replace('_',' '))
                if(payload.approachNotes.isNotBlank())Text(payload.approachNotes)
                SharedReferenceSafetyCopy()
                Text(tr("The library will keep Place and Spot as separate identities. No visit history or photos are imported.","锚地库会分别保留地点与锚点身份，不会导入访问历史或照片。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button({saveV2(payload);close()},Modifier.fillMaxWidth().testTag("save_scanned_anchorage_v2")){Text(tr("Import Place and Spot","导入地点和锚点"))}
                OutlinedButton(scanAgain,Modifier.fillMaxWidth()){Text(tr("Scan again","重新扫描"))}
                TextButton(close,Modifier.fillMaxWidth()){Text(tr("Cancel","取消"))}
            }
        }
        is AnchorageQrDecodeResult.Coordinate->{
            var name by remember{mutableStateOf("")}
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(tr("Anchorage coordinate","锚地坐标"),style=MaterialTheme.typography.headlineSmall)
                Text("${"%.7f".format(result.latitude)}, ${"%.7f".format(result.longitude)}")
                Text(tr("This older QR contained coordinates only. Add a name before saving; no setup details will be invented.","这个旧二维码只包含坐标。收藏前请填写名称；应用不会编造下锚参数。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name,{name=it.take(AnchorageSharePayloadCodec.MAX_NAME_CHARS)},label={Text(tr("Name *","名称 *"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                SharedReferenceSafetyCopy()
                Button({save(AnchorageSharePayloadCodec.coordinateEntity(result.latitude,result.longitude,name));close()},Modifier.fillMaxWidth().testTag("save_scanned_coordinate"),enabled=name.isNotBlank()){Text(tr("Save anchorage","收藏锚地"))}
                OutlinedButton(scanAgain,Modifier.fillMaxWidth()){Text(tr("Scan again","重新扫描"))}
                TextButton(close,Modifier.fillMaxWidth()){Text(tr("Cancel","取消"))}
            }
        }
        is AnchorageQrDecodeResult.UnsupportedVersion->QrFailure(tr("Newer sharing format","较新的分享格式"),tr("This anchorage was shared by a newer Boat Watch format. Update the app to read version ${result.version?:"?"}.","该锚地使用了更新的 Boat Watch 格式。请更新应用后读取版本 ${result.version?:"?"}。"),scanAgain,close)
        is AnchorageQrDecodeResult.Invalid->QrFailure(
            tr("Invalid anchorage QR","锚地二维码无效"),
            tr("Invalid data: ${result.reason}","二维码内容损坏或字段超出允许范围。"),
            scanAgain,
            close,
        )
        AnchorageQrDecodeResult.Unsupported->QrFailure(tr("Not a Boat Watch anchorage","不是 Boat Watch 锚地"),tr("This QR code does not contain a supported Boat Watch anchorage. No link was opened.","该二维码不包含受支持的 Boat Watch 锚地；应用没有打开其中的链接。"),scanAgain,close)
    }
}

private fun AnchorageSharePayloadV2.toLegacyImportEntity()=SavedAnchorageEntity(
    name=placeName,latitude=latitude,longitude=longitude,createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis(),
    preferredAlarmRadiusMeters=preferredAlarmRadiusMeters,typicalWaterDepthMeters=typicalWaterDepthMeters,typicalRodeLengthMeters=typicalRodeLengthMeters,
    seabedType=seabedType,customSeabedText=customSeabedText,notes=notes,coordinateSource=coordinateSource,coordinateUncertaintyMeters=coordinateUncertaintyMeters,
)

@Composable private fun QrFailure(title:String,detail:String,retry:()->Unit,close:()->Unit){Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Icons.Default.QrCodeScanner,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp));Text(title,style=MaterialTheme.typography.titleLarge);Text(detail,Modifier.padding(vertical=12.dp));Button(retry,Modifier.fillMaxWidth()){Text(tr("Scan again","重新扫描"))};TextButton(close){Text(tr("Cancel","取消"))}}}
@Composable private fun ImportLine(label:String,value:String?){if(value!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value)}}
@Composable private fun SharedReferenceSafetyCopy(){Text(tr("Shared saved reference only. Check current depth, traffic, weather and surroundings before anchoring.","这只是他人分享的收藏参考。下锚前请重新确认当前水深、交通、天气和周围环境。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}

@Composable private fun AnchorageCameraPreview(torchEnabled:Boolean,onDecoded:(String)->Unit,onError:(String)->Unit){
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onDecoded)
    val errorCallback by rememberUpdatedState(onError)
    var camera by remember{mutableStateOf<Camera?>(null)}
    val previewView=remember{PreviewView(context).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}}
    AndroidView(factory={previewView},modifier=Modifier.fillMaxSize().testTag("anchorage_qr_camera"))
    DisposableEffect(previewView,lifecycleOwner){
        val executor=Executors.newSingleThreadExecutor()
        val providerFuture=ProcessCameraProvider.getInstance(context)
        var provider:ProcessCameraProvider?=null
        val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor,AnchorageQrAnalyzer{raw->ContextCompat.getMainExecutor(context).execute{callback(raw)}})
        providerFuture.addListener({
            runCatching{
                provider=providerFuture.get()
                val preview=Preview.Builder().build().also{it.setSurfaceProvider(previewView.surfaceProvider)}
                provider?.unbindAll()
                camera=provider?.bindToLifecycle(lifecycleOwner,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
            }.onFailure{errorCallback(it.message?:"Camera could not be started.")}
        },ContextCompat.getMainExecutor(context))
        onDispose{analysis.clearAnalyzer();runCatching{provider?.unbindAll()};camera=null;executor.shutdownNow()}
    }
    LaunchedEffect(camera,torchEnabled){camera?.cameraControl?.enableTorch(torchEnabled)}
}

internal object AnchorageQrBitmapDecoder{
    fun decodeUri(context:android.content.Context,uri:Uri):String?=runCatching{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1
        while(bounds.outWidth/sample>MAX_SIDE||bounds.outHeight/sample>MAX_SIDE)sample*=2
        val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:return null
        bitmap.useDecoded(::decode)
    }.getOrNull()

    fun decode(bitmap:Bitmap):String?{
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val source=RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
        return runCatching{MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),HINTS).text}.getOrNull()
    }

    private inline fun <T> Bitmap.useDecoded(block:(Bitmap)->T):T=try{block(this)}finally{recycle()}
    private const val MAX_SIDE=2_048
    private val HINTS=mapOf<DecodeHintType,Any>(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8")
}

private class AnchorageQrAnalyzer(private val decoded:(String)->Unit):ImageAnalysis.Analyzer{
    private val emitted=AtomicBoolean(false)
    private val reader=MultiFormatReader().apply{setHints(EnumMap<DecodeHintType,Any>(DecodeHintType::class.java).apply{put(DecodeHintType.POSSIBLE_FORMATS,listOf(BarcodeFormat.QR_CODE));put(DecodeHintType.TRY_HARDER,true);put(DecodeHintType.CHARACTER_SET,"UTF-8")})}
    private var lastAttempt=0L
    override fun analyze(image:ImageProxy){
        try{
            if(emitted.get())return
            val now=SystemClock.elapsedRealtime();if(now-lastAttempt<110L)return;lastAttempt=now
            val frame=compactLuma(image)
            val source=PlanarYUVLuminanceSource(frame.bytes,frame.width,frame.height,0,0,frame.width,frame.height,false)
            val text=runCatching{reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text}.getOrNull()
            reader.reset()
            if(text!=null&&emitted.compareAndSet(false,true))decoded(text)
        }finally{image.close()}
    }

    private data class LumaFrame(val bytes:ByteArray,val width:Int,val height:Int)
    private fun compactLuma(image:ImageProxy):LumaFrame{
        val plane=image.planes[0];val buffer=plane.buffer.duplicate().apply{rewind()};val width=image.width;val height=image.height;val raw=ByteArray(width*height)
        for(y in 0 until height)for(x in 0 until width)raw[y*width+x]=buffer.get(y*plane.rowStride+x*plane.pixelStride)
        return when((image.imageInfo.rotationDegrees%360+360)%360){
            90->{val rotated=ByteArray(raw.size);for(y in 0 until height)for(x in 0 until width)rotated[x*height+(height-1-y)]=raw[y*width+x];LumaFrame(rotated,height,width)}
            180->{val rotated=ByteArray(raw.size);for(y in 0 until height)for(x in 0 until width)rotated[(height-1-y)*width+(width-1-x)]=raw[y*width+x];LumaFrame(rotated,width,height)}
            270->{val rotated=ByteArray(raw.size);for(y in 0 until height)for(x in 0 until width)rotated[(width-1-x)*height+y]=raw[y*width+x];LumaFrame(rotated,height,width)}
            else->LumaFrame(raw,width,height)
        }
    }
}
