package com.yokuli.anchorwatch.service
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import com.yokuli.anchorwatch.MainActivity
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint class BootRestoreReceiver:BroadcastReceiver(){
 @Inject lateinit var dao:AnchorDao;@Inject lateinit var preferences:SettingsRepository
 override fun onReceive(context:Context,intent:Intent){if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return;val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{val active=dao.active();val needsAttention=active?.paused==false||preferences.settings.first().mockEnabled;if(needsAttention){val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"Monitoring recovery",NotificationManager.IMPORTANCE_HIGH));val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);manager.notify(ID,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("NMEA Anchor Watch needs attention").setContentText("The phone restarted. Open the app to resume safety monitoring.").setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())}}finally{pending.finish()}}}
 companion object{const val CHANNEL="monitor_restore";const val ID=47}
}
