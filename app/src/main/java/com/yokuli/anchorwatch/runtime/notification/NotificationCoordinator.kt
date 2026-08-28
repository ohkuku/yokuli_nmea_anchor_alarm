package com.yokuli.anchorwatch.runtime.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.yokuli.anchorwatch.MainActivity
import com.yokuli.anchorwatch.service.AnchorForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns every notification channel and notification-manager mutation used by the runtime. */
@Singleton
class NotificationCoordinator @Inject constructor(@ApplicationContext private val context:Context){
    private val manager get()=context.getSystemService(NotificationManager::class.java)

    fun createChannels(statusName:String,eventName:String,alarmName:String){
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(STATUS_CHANNEL,statusName,NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(EVENT_CHANNEL,eventName,NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(ALARM_CHANNEL,alarmName,NotificationManager.IMPORTANCE_HIGH).apply{
                    setSound(null,null)
                    enableVibration(false)
                },
            ),
        )
    }

    fun foregroundNotification(
        text:String,
        alarm:Boolean,
        silent:Boolean=false,
        title:String,
        snoozeLabel:String,
    ):Notification{
        val open=PendingIntent.getActivity(
            context,
            0,
            Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze=PendingIntent.getService(
            context,
            1,
            Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context,if(alarm)ALARM_CHANNEL else STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setPriority(if(alarm)NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setCategory(if(alarm)NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .apply{if(alarm){setFullScreenIntent(open,true);addAction(0,snoozeLabel,snooze)}}
            .build()
    }

    fun publishForeground(notification:Notification)=manager.notify(ONGOING_ID,notification)

    fun publishEvent(title:String,text:String,high:Boolean,notificationId:Int=EVENT_ID){
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context,if(high)EVENT_CHANNEL else STATUS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(if(high)NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    fun cancelEvent(notificationId:Int=EVENT_ID)=manager.cancel(notificationId)

    companion object{
        const val STATUS_CHANNEL="anchor_status"
        const val EVENT_CHANNEL="anchor_events_v2"
        const val ALARM_CHANNEL="anchor_alarm_selectable_v2"
        const val ONGOING_ID=42
        const val EVENT_ID=43
        const val DEPTH_DATA_EVENT_ID=44
        const val WIND_DATA_EVENT_ID=45
    }
}
