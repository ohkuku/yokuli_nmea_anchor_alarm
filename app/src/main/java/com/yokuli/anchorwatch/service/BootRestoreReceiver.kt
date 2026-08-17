package com.yokuli.anchorwatch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.MainActivity
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reboot is an explicit monitoring gap. Location safety is never claimed to
 * have continued through it: anchor watch becomes resumable/paused and an
 * interrupted sonar survey is safely closed. Only the non-location sharing
 * runtime may be restored from BOOT_COMPLETED.
 */
@AndroidEntryPoint
class BootRestoreReceiver:BroadcastReceiver(){
    @Inject lateinit var dao:AnchorDao
    @Inject lateinit var sonarDao:SonarDao
    @Inject lateinit var preferences:SettingsRepository

    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch{try{
            val now=System.currentTimeMillis();val active=dao.active();val sonar=sonarDao.active();var settings=preferences.settings.first();var interrupted=false
            if(active?.paused==false){
                interrupted=true;dao.updateSession(active.copy(paused=true,alarmSnoozedUntil=null));dao.insertEvent(AlarmEventEntity(sessionId=active.id,timestamp=now,type="MONITORING_INTERRUPTED_BY_REBOOT",detail="USER_MUST_RESUME"))
            }
            if(sonar!=null){interrupted=true;sonarDao.refreshSampleCount(sonar.id);sonarDao.finish(sonar.id,now)}
            if(settings.mockEnabled){interrupted=true;settings=settings.copy(mockEnabled=false);preferences.save(settings)}
            if(settings.nmeaSharingEnabled)runCatching{ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",true).putExtra("port",settings.nmeaSharingPort))}
            if(interrupted)notifyRecovery(context,sonar!=null)
        }finally{pending.finish()}}
    }

    private fun notifyRecovery(context:Context,sonarInterrupted:Boolean){
        val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"Monitoring recovery",NotificationManager.IMPORTANCE_HIGH))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text=if(sonarInterrupted)"Reboot interrupted monitoring and closed the sonar survey. Open Yokuli, verify live data, then explicitly resume anchor watch or start a new survey." else "Reboot interrupted location monitoring. Open Yokuli, verify the GPS source, then explicitly resume anchor watch."
        manager.notify(ID,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Yokuli monitoring needs confirmation").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    companion object{const val CHANNEL="monitor_restore";const val ID=47}
}
