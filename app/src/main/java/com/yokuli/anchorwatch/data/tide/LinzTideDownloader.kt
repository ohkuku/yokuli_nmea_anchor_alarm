package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.tide.TideStation
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LinzTideDownloader @Inject constructor(){
    fun secondaryPortsUrl()=CURRENT_SECONDARY_PORTS_URL
    fun dailyPredictionUrl(station:TideStation,year:Int):String{
        val encoded=URLEncoder.encode(requireNotNull(station.csvName),Charsets.UTF_8.name()).replace("+","%20")
        return "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/$encoded%20$year.csv"
    }

    suspend fun download(station:TideStation,year:Int):Pair<String,String> = withContext(Dispatchers.IO){
        val source=dailyPredictionUrl(station,year)
        val connection=(URL(source).openConnection() as HttpURLConnection).apply{
            connectTimeout=12_000;readTimeout=20_000;instanceFollowRedirects=true
            setRequestProperty("User-Agent","Anchor-by-Yokuli/1.0 (LINZ predicted tide cache)")
        }
        try{
            if(connection.responseCode !in 200..299)error("LINZ tide HTTP ${connection.responseCode}")
            val bytes=connection.inputStream.use{stream->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8_192);var total=0
                while(true){val count=stream.read(buffer);if(count<0)break;total+=count;if(total>1_000_000)error("LINZ tide response too large");output.write(buffer,0,count)}
                output.toByteArray()
            }
            source to bytes.toString(Charsets.UTF_8)
        }finally{connection.disconnect()}
    }

    suspend fun downloadSecondaryPorts():Pair<String,String> = withContext(Dispatchers.IO){
        val source=secondaryPortsUrl()
        val connection=(URL(source).openConnection() as HttpURLConnection).apply{
            connectTimeout=8_000;readTimeout=12_000;instanceFollowRedirects=true
            setRequestProperty("User-Agent","Anchor-by-Yokuli/1.0 (LINZ secondary-port cache)")
        }
        try{
            if(connection.responseCode !in 200..299)error("LINZ secondary ports HTTP ${connection.responseCode}")
            val bytes=connection.inputStream.use{stream->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8_192);var total=0
                while(true){val count=stream.read(buffer);if(count<0)break;total+=count;if(total>500_000)error("LINZ secondary-port response too large");output.write(buffer,0,count)}
                output.toByteArray()
            }
            source to bytes.toString(Charsets.UTF_8)
        }finally{connection.disconnect()}
    }

    companion object{
        const val CURRENT_SECONDARY_PORTS_URL="https://www.linz.govt.nz/sites/default/files/2026-06/NZNA_Secondary-ports-2026-27.csv"
    }
}
