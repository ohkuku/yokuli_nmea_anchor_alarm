package com.yokuli.anchorwatch.runtime.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.yokuli.anchorwatch.domain.model.AlarmSound
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sin

data class AlarmPlayback(val started:Boolean,val volume:Int)

/** Owns the complete MediaPlayer/audio-focus/vibration lifecycle. */
@Singleton
class AlarmAudioController @Inject constructor(@ApplicationContext private val context:Context){
    private val audio=context.getSystemService(AudioManager::class.java)
    private val vibrator=context.getSystemService(Vibrator::class.java)
    private var player:MediaPlayer?=null
    private var focus:AudioFocusRequest?=null
    private var playingSelection:Pair<AlarmSound,String?>?=null

    @Synchronized fun start(sound:AlarmSound,customUri:String?):AlarmPlayback{
        val volume=audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val requested=sound to customUri.takeIf{sound==AlarmSound.CUSTOM}
        if(player?.isPlaying==true&&playingSelection==requested)return AlarmPlayback(true,volume)
        stop()
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).build().also(audio::requestAudioFocus)
        val builtIn=anchorAlarmUri();val selected=if(sound==AlarmSound.CUSTOM)customUri?.let{runCatching{Uri.parse(it)}.getOrNull()}else builtIn
        val started=play(selected)||play(builtIn)||play(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
        if(started){playingSelection=requested;vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,800,400),0))}
        return AlarmPlayback(started,volume)
    }

    @Synchronized fun stop(){
        val current=player;player=null
        playingSelection=null
        runCatching{if(current?.isPlaying==true)current.stop()};runCatching{current?.reset()};runCatching{current?.release()}
        focus?.let{runCatching{audio.abandonAudioFocusRequest(it)}};focus=null;vibrator.cancel()
    }

    private fun play(uri:Uri?):Boolean{
        if(uri==null)return false
        val candidate=MediaPlayer()
        return runCatching{candidate.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());candidate.setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK);if(uri.scheme=="file")candidate.setDataSource(requireNotNull(uri.path))else candidate.setDataSource(context,uri);candidate.setVolume(1f,1f);candidate.isLooping=true;candidate.prepare();candidate.start();player=candidate;true}.getOrElse{runCatching{candidate.release()};false}
    }

    private fun anchorAlarmUri():Uri?=runCatching{
        val file=File(context.cacheDir,"yokuli-anchor-alarm.wav")
        if(!file.exists()||file.length()<1_000){
            val rate=22_050;val seconds=4;val samples=rate*seconds;val pcmBytes=samples*2
            val bytes=ByteBuffer.allocate(44+pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray());bytes.putInt(36+pcmBytes);bytes.put("WAVEfmt ".toByteArray());bytes.putInt(16);bytes.putShort(1.toShort());bytes.putShort(1.toShort());bytes.putInt(rate);bytes.putInt(rate*2);bytes.putShort(2.toShort());bytes.putShort(16.toShort());bytes.put("data".toByteArray());bytes.putInt(pcmBytes)
            for(index in 0 until samples){val time=index.toDouble()/rate;val frequency=if(((time/.42).toInt()%2)==0)760.0 else 1040.0;val pulse=.45+.55*abs(sin(2.0*Math.PI*time*2.0));val value=(sin(2.0*Math.PI*frequency*time)*Short.MAX_VALUE*.58*pulse).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt());bytes.putShort(value.toShort())}
            file.outputStream().use{it.write(bytes.array())}
        }
        Uri.fromFile(file)
    }.getOrNull()
}
