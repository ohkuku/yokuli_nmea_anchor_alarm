package com.yokuli.anchorwatch.data.linz

enum class LinzDepthStatus{IDLE,LOADING,AVAILABLE,NO_DATA,OFFLINE,NOT_CONFIGURED,ERROR}

data class LinzDepthReference(
    val queriedLatitude:Double=0.0,val queriedLongitude:Double=0.0,val queriedAt:Long=0L,
    val depthAreaMinMeters:Double?=null,val depthAreaMaxMeters:Double?=null,
    val nearestSoundingDepthMeters:Double?=null,val nearestSoundingDistanceMeters:Double?=null,
    val nearestSoundingLatitude:Double?=null,val nearestSoundingLongitude:Double?=null,
    val nearestContourDepthMeters:Double?=null,val nearestContourDistanceMeters:Double?=null,
    val sourceLayers:List<String> = emptyList(),val status:LinzDepthStatus=LinzDepthStatus.IDLE,val cached:Boolean=false,
)

data class LinzDepthDiagnostics(
    val requests:Long=0,val cacheHits:Long=0,val cacheMisses:Long=0,val lastHttpCode:Int?=null,
    val lastQueryAt:Long?=null,val lastQueryLatitude:Double?=null,val lastQueryLongitude:Double?=null,
    val layerIds:List<String> = emptyList(),val message:String="Idle",
)

object LinzDepthPresentation{
    data class Text(val primary:String,val secondary:String?)
    fun text(value:LinzDepthReference,chinese:Boolean=false):Text{
        val base=when{
            value.status==LinzDepthStatus.NOT_CONFIGURED->Text(if(chinese)"未配置" else "Not configured",null)
            value.status==LinzDepthStatus.LOADING->Text(if(chinese)"正在加载…" else "Loading…",null)
            value.status in setOf(LinzDepthStatus.OFFLINE,LinzDepthStatus.ERROR)&&value.depthAreaMinMeters==null&&value.nearestSoundingDepthMeters==null&&value.nearestContourDepthMeters==null->Text(if(chinese)"不可用" else "Unavailable",null)
            value.nearestSoundingDepthMeters!=null&&(value.nearestSoundingDistanceMeters?:Double.POSITIVE_INFINITY)<=25.0->Text("${format(value.nearestSoundingDepthMeters)} ${if(chinese)"米" else "m"}",if(chinese)"最近测深点 · 距离 ${distance(value.nearestSoundingDistanceMeters)} 米" else "Nearest sounding · ${distance(value.nearestSoundingDistanceMeters)} m away")
            value.depthAreaMinMeters!=null&&value.depthAreaMaxMeters!=null->Text("${format(value.depthAreaMinMeters)}–${format(value.depthAreaMaxMeters)} ${if(chinese)"米" else "m"}",value.nearestSoundingDepthMeters?.let{if(chinese)"最近测深点 ${format(it)} 米 · 距离 ${distance(value.nearestSoundingDistanceMeters)} 米" else "Nearest sounding ${format(it)} m · ${distance(value.nearestSoundingDistanceMeters)} m away"})
            value.nearestSoundingDepthMeters!=null->Text(if(chinese)"最近测深点 ${format(value.nearestSoundingDepthMeters)} 米" else "Nearest sounding ${format(value.nearestSoundingDepthMeters)} m",if(chinese)"距离 ${distance(value.nearestSoundingDistanceMeters)} 米" else "${distance(value.nearestSoundingDistanceMeters)} m away")
            value.nearestContourDepthMeters!=null->Text(if(chinese)"附近 ${format(value.nearestContourDepthMeters)} 米等深线" else "Near ${format(value.nearestContourDepthMeters)} m contour",if(chinese)"距离 ${distance(value.nearestContourDistanceMeters)} 米" else "${distance(value.nearestContourDistanceMeters)} m away")
            else->Text(if(chinese)"附近没有 LINZ 水深参考" else "No LINZ depth reference nearby",null)
        }
        val cached=when{value.cached&&value.status==LinzDepthStatus.OFFLINE->if(chinese)"离线缓存" else "Cached · offline";value.cached->if(chinese)"缓存" else "Cached";else->null}
        return if(cached==null)base else base.copy(secondary=listOfNotNull(base.secondary,cached).joinToString(" · "))
    }
    private fun distance(value:Double?)=value?.toInt()?.toString()?:"—"
    private fun format(value:Double)=if(kotlin.math.abs(value-kotlin.math.round(value))<.05)value.toInt().toString() else "%.1f".format(java.util.Locale.US,value)
}
