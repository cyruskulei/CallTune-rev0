package com.calltune.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager

class CallService : Service() {
    private var player: MediaPlayer? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    TuneStore.find(context, number)?.let { play(it) }
                }
                else -> stop() // answered or ended
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ct", "CallTune", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ct")
            .setContentTitle("CallTune is active")
            .setContentText("Playing your chosen tunes for incoming calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        startForeground(1, n)
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
    }

    private fun play(uri: android.net.Uri) {
        stop()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@CallService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { stop() }
    }

    private fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    override fun onDestroy() {
        stop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
