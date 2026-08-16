package com.yokuli.anchorwatch.service
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.MainActivity
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint class BootRestoreReceiver:BroadcastReceiver(){
 @Inject lateinit var dao:AnchorDao;@Inject lateinit var sonarDao:SonarDao;@Inject lateinit var preferences:SettingsRepository
 override fun onReceive(context:Context,intent:Intent){if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return;val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{val active=dao.active();val sonar=sonarDao.active();val settings=preferences.settings.first();if(settings.nmeaSharingEnabled)runCatching{ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",true).putExtra("port",settings.nmeaSharingPort))};if(sonar!=null)runCatching{ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name",sonar.name).putExtra("tideMode",sonar.tideMode).putExtra("manualTideOffset",sonar.manualTideOffsetMeters))};val needsAttention=active?.paused==false||settings.mockEnabled||sonar!=null;if(needsAttention){val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"Monitoring recovery",NotificationManager.IMPORTANCE_HIGH));val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);manager.notify(ID,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Anchor by Yokuli restarted").setContentText(if(sonar!=null)"Sonar recording is being restored. Open the app and verify position, depth and anchor-watch state." else "Open the app to verify and resume anchor safety monitoring.").setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())}}finally{pending.finish()}}}
 companion object{const val CHANNEL="monitor_restore";const val ID=47}
}
