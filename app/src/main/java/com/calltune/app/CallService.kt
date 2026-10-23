package com.calltune.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager

class CallService : Service() {
    private var player: MediaPlayer? = null
    private var prevRinger: Int? = null
    @Volatile private var ringing = false
    private val main = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    ringing = true
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    handleRing(context, number)
                }
                else -> { ringing = false; stop() } // answered or ended
            }
        }
    }

    private fun handleRing(ctx: Context, number: String?) {
        val specific = number?.let { if (it.isBlank()) null else TuneStore.findSpecific(ctx, it) }
        if (specific != null) { play(specific); return }
        val default = TuneStore.findDefault(ctx)
        if (number.isNullOrBlank()) { default?.let { play(it) }; return }
        val cached = Cloud.cachedFile(ctx, number)
        val first = if (cached != null) Uri.fromFile(cached) else default
        first?.let { play(it) }
        Thread {
            val f = Cloud.fetch(ctx, number)
            if (f != null && cached == null) main.post { if (ringing) play(Uri.fromFile(f)) }
        }.start()
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

    private fun play(uri: Uri) {
        stop()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@CallService, uri)
                isLooping = true
                prepare()
                start()
            }
            silenceRinger()
        } catch (e: Exception) { stop() }
    }

    private fun silenceRinger() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val am = getSystemService(AudioManager::class.java)
        if (prevRinger == null) prevRinger = am.ringerMode
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    private fun restoreRinger() {
        prevRinger?.let {
            try { getSystemService(AudioManager::class.java).ringerMode = it } catch (_: Exception) {}
        }
        prevRinger = null
    }

    private fun stop() {
        restoreRinger()
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
