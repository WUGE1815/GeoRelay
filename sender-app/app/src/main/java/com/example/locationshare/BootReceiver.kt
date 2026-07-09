package com.example.locationshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 开机广播接收器
 *
 * ⚠️ 重要：此接收器仅用于在设备重启后提醒用户
 * 不会自动开启定位服务！
 * 用户必须手动打开 APP 并点击"开始共享"才会启动定位
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "设备已开机，发送提醒通知（不自动开启定位）")

            // 仅弹出一条通知提醒用户，不会自动启动任何服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                NotificationManagerCompat.from(context).notify(
                    9999,
                    createReminderNotification(context)
                )
            } else {
                @Suppress("DEPRECATION")
                NotificationManagerCompat.from(context).notify(
                    9999,
                    createReminderNotification(context)
                )
            }
        }
    }

    private fun createReminderNotification(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, "location_share_channel")
            .setContentTitle("设备已重启")
            .setContentText("位置共享不会自动开启，如需共享请打开 APP 手动启动")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
    }
}
