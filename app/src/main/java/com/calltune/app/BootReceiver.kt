package com.calltune.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts CallTune after the phone boots, if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val on = sp.getBoolean("autostart", false)
        val time = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        if (!on) {
            sp.edit().putString("boot_log", "$time: boot seen, but autostart was OFF (tap Start first)").apply()
            return
        }
        try {
            context.startForegroundService(Intent(context, CallService::class.java))
            sp.edit().putString("boot_log", "$time: boot seen, start requested").apply()
        } catch (e: Exception) {
            sp.edit().putString("boot_log", "$time: boot seen, start blocked: ${e.javaClass.simpleName}").apply()
            // Some Android versions block this at boot: ask the user to tap instead.
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("ct_boot", "CallTune reminder", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(context, "ct_boot")
                .setContentTitle("CallTune is off")
                .setContentText("Tap to open CallTune and start it")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        }
    }
}
