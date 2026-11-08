package com.calltune.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager

class CallService : Service() {
    companion object {
        var instance: CallService? = null
            private set
    }
    private val incomingSession = IncomingTuneSession()
    private val legacyIncoming = Any()
    private var destroyed = false
    private var ready = false
    private fun enabled() = !destroyed && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autostart", false)
    private val outgoingGate = OutgoingTuneGate<android.telecom.Call>()

    /** Telecom reports ACTIVE separately from DIALING; phone-state OFFHOOK does not. */
    fun onTelecomCallsChanged(calls: List<android.telecom.Call>) {
        if (!enabled()) { stopIncoming(); stopOutgoing("disabled"); return }
        val connected = calls.any {
            it.state == android.telecom.Call.STATE_ACTIVE || it.state == android.telecom.Call.STATE_HOLDING
        }
        val candidate = calls.firstOrNull {
            it.state == android.telecom.Call.STATE_DIALING &&
                it.details?.hasProperty(android.telecom.Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) != true
        }
        val action = outgoingGate.update(candidate, connected)
        if (action == OutgoingTuneGate.Action.STOP) {
            stopOutgoing(if (connected) "Telecom connected" else "Telecom no dialing call")
        } else if (action == OutgoingTuneGate.Action.START && candidate != null) {
            if (outgoingChoice() != TunePolicy.Outgoing.OFF) {
                val handle = candidate.details?.handle
                val number = handle?.schemeSpecificPart
                if (handle?.scheme == "tel" && !number.isNullOrBlank()) {
                    dialAt = System.currentTimeMillis()
                    sawActive = false
                    startOutgoing(this, number)
                }
            }
        }
        val ringing = calls.firstOrNull { it.state == android.telecom.Call.STATE_RINGING }
        if (connected || ringing == null) {
            stopIncoming()
        } else {
            val number = ringing.details?.handle?.schemeSpecificPart
            if (incomingSession.select(ringing, number)) handleRing(this, number)
        }
    }

    private var player: MediaPlayer? = null
    private var prevMedia: Int? = null
    private val main = Handler(Looper.getMainLooper())

    private val autoStop = Runnable { stopIncoming() }

    // Outgoing audio always plays locally: own tune in Free, receiver tune in Pro/Pro Plus.
    private var outPlayer: MediaPlayer? = null
    @Volatile private var outgoing = false
    // FIX: the phone can still report IDLE for a moment after dialling starts (seen on the Samsung: IDLE at +831 ms).
    // So IDLE only stops the tune after the call has been seen active (OFFHOOK), or after this many ms.
    private val IDLE_GRACE_MS = 6000L
    @Volatile private var sawActive = false
    private var lastSig = ""
    private var dialAt = 0L
    private var polls = 0
    private val outStop = Runnable { stopOutgoing("20 second cap") }

    private fun callStateName(): String = try {
        @Suppress("DEPRECATION")
        when (getSystemService(TelephonyManager::class.java).callState) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "?"
        }
    } catch (e: Exception) { "ERR" }

    // Appends to the 'When I call' line (keeps the last 900 characters).
    private fun trace(msg: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val ms = if (dialAt > 0) System.currentTimeMillis() - dialAt else 0
        val old = prefs.getString("out_log", "") ?: ""
        val line = "$t +${ms}ms $msg"
        val all = if (old.isEmpty()) line else "$old | $line"
        prefs.edit().putString("out_log", all.takeLast(2500)).apply()
    }

    private val outWatch = object : Runnable {
        override fun run() {
            if (!outgoing) return
            val state = callStateName()
            polls++
            // Log only when something changes: call state, audio mode, whether our player is still playing, call volume.
            val sig = try {
                val am = getSystemService(AudioManager::class.java)
                val playing = try { outPlayer?.isPlaying } catch (_: Exception) { null }
                "state=$state mode=${am.mode} playing=$playing callvol=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}"
            } catch (_: Exception) { "state=$state" }
            if (sig != lastSig) { lastSig = sig; trace(sig) }
            val idle = state == "IDLE"
            if (state == "OFFHOOK" || state == "RINGING") sawActive = true
            if (idle && (sawActive || System.currentTimeMillis() - dialAt >= IDLE_GRACE_MS)) stopOutgoing("watchdog saw IDLE")
            else main.postDelayed(this, 500)
        }
    }

    private val outReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!enabled()) return
            if (getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage == packageName) return
            if (outgoingChoice() == TunePolicy.Outgoing.OFF) return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: resultData
            if (number.isNullOrBlank()) { note("out_log", "outgoing call, number not provided"); return }
            note("out_log", "dialing $number")
            dialAt = System.currentTimeMillis()
            polls = 0
            sawActive = false
            lastSig = ""
            startOutgoing(context, number)
        }
    }

    private fun mode(): CallTuneMode = CallTuneMode.fromSetting(
        getSharedPreferences("settings", MODE_PRIVATE).getString("mode", "free"))

    private fun outgoingChoice(): TunePolicy.Outgoing = TunePolicy.outgoing(mode(),
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("caller_hears", true))

    private fun ownTune(ctx: Context): Uri = TuneStore.findDefault(ctx)
        ?: Uri.parse("android.resource://$packageName/${R.raw.default_tune}")

    private fun isEmergency(number: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29)
            getSystemService(TelephonyManager::class.java).isEmergencyNumber(number)
        else android.telephony.PhoneNumberUtils.isEmergencyNumber(number)
    } catch (_: Exception) { true }

    private fun startOutgoing(ctx: Context, number: String) {
        stopOutgoing("restart")
        // Both Telecom and the legacy event path must leave emergency calls alone.
        if (!enabled() || isEmergency(number)) return
        val choice = outgoingChoice()
        if (choice == TunePolicy.Outgoing.OFF) return
        outgoing = true
        val generation = outgoingGate.token()
        if (choice == TunePolicy.Outgoing.OWN) {
            playOutgoing(ownTune(ctx), "playing my own tune on this phone", true)
            return // Free playback never looks up the other person's cloud tune.
        }
        val cached = Cloud.cachedFile(ctx, number)
        if (cached != null) {
            playOutgoing(Uri.fromFile(cached))
            Thread { Cloud.fetch(ctx, number) }.start() // refresh for next time
            return
        }
        Thread {
            val f = Cloud.fetch(ctx, number)
            main.post {
                if (enabled() && outgoing && outgoingGate.isCurrent(generation) && f != null) playOutgoing(Uri.fromFile(f))
                else if (outgoing && outgoingGate.isCurrent(generation)) note("out_log", "receiver tune unavailable; no custom outgoing audio")
            }
        }.start()
    }

    private fun playOutgoing(uri: Uri, description: String = "playing receiver tune on this phone",
                             allowOwnFallback: Boolean = false) {
        if (!enabled() || !outgoing) return
        val token = outgoingGate.token()
        outPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        val media = MediaPlayer()
        outPlayer = media
        fun recover() {
            if (outPlayer !== media) return
            if (allowOwnFallback && enabled() && outgoing && outgoingGate.isCurrent(token))
                playOutgoing(Uri.parse("android.resource://$packageName/${R.raw.default_tune}"),
                    "playing built-in fallback on this phone")
            else stopOutgoing("player failed")
        }
        try {
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            media.setDataSource(this, uri)
            media.isLooping = true
            media.setOnPreparedListener {
                if (outPlayer !== media || !enabled() || !outgoing || !outgoingGate.isCurrent(token)) return@setOnPreparedListener
                try {
                    media.start()
                    note("out_log", description)
                    trace("started, state=${callStateName()}")
                    main.removeCallbacks(outStop)
                    main.postDelayed(outStop, 20_000)
                    main.removeCallbacks(outWatch)
                    main.postDelayed(outWatch, 500)
                } catch (e: Exception) {
                    note("out_log", "start failed: ${e.javaClass.simpleName}")
                    recover()
                }
            }
            media.setOnErrorListener { _, what, extra ->
                note("out_log", "player error: $what/$extra")
                recover()
                true
            }
            media.prepareAsync()
        } catch (e: Exception) {
            note("out_log", "prepare failed: ${e.javaClass.simpleName}")
            recover()
        }
    }

    private fun stopOutgoing(why: String = "unspecified") {
        if (outgoing || outPlayer != null) trace("STOP: $why (state=${callStateName()})")
        outgoing = false
        outgoingGate.invalidate()
        main.removeCallbacks(outStop)
        main.removeCallbacks(outWatch)
        outPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        outPlayer = null
    }
    private fun stopIncoming() {
        incomingSession.invalidate()
        stop()
    }

    private fun mayPlay(token: Long) = enabled() && incomingSession.isCurrent(token) && isRinging()

    private fun isRinging(): Boolean = try {
        RinomInCallService.current?.let { service ->
            return service.calls.any { it.state == android.telecom.Call.STATE_RINGING } &&
                service.calls.none { it.state == android.telecom.Call.STATE_ACTIVE || it.state == android.telecom.Call.STATE_HOLDING }
        }
        @Suppress("DEPRECATION")
        getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_RINGING
    } catch (e: Exception) { false }

    private fun note(key: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString(key, "$t $msg").apply()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!enabled()) return
            // Telecom owns call selection while we are the default dialer. Do not let
            // duplicate/second-SIM broadcasts restart its player or stop outgoing audio.
            RinomInCallService.current?.let { onTelecomCallsChanged(it.calls); return }
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (incomingSession.select(legacyIncoming, number)) handleRing(context, number)
            } else if (outgoing) {
                trace("broadcast state=$state")
                if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) sawActive = true
                if (state == TelephonyManager.EXTRA_STATE_IDLE &&
                    (sawActive || System.currentTimeMillis() - dialAt >= IDLE_GRACE_MS)) stopOutgoing("IDLE broadcast")
            } else if (!isRinging()) {
                // Call answered or ended (extra "idle" signals from a second SIM are ignored).
                note("stop_log", "stopped: state=$state")
                stopIncoming()
            } else {
                note("stop_log", "ignored stray state=$state while ringing")
            }
        }
    }

    private fun handleRing(ctx: Context, number: String?) {
        if (!enabled()) return
        val id = incomingSession.token()
        note("ring_log", "incoming ring; number available=${!number.isNullOrBlank()}")
        val selectedMode = mode()
        val specific = if (selectedMode == CallTuneMode.PRO_PLUS)
            number?.takeIf { it.isNotBlank() }?.let { TuneStore.findSpecific(ctx, it) }
            else null
        // The receiver always hears their own local audio. Only Pro Plus uses
        // caller assignments; no incoming mode downloads the caller's cloud tune.
        val selected = when (TunePolicy.incoming(selectedMode, specific != null)) {
            TunePolicy.Incoming.ASSIGNED -> requireNotNull(specific)
            TunePolicy.Incoming.OWN -> ownTune(ctx)
        }
        note("ring_log", "incoming ring; mode=${selectedMode.setting}; assigned=${specific != null}; number available=${!number.isNullOrBlank()}")
        if (mayPlay(id)) play(selected)
    }

    override fun onCreate() {
        super.onCreate()
        note("svc_log", "service starting")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ct", "CallTune", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ct")
            .setContentTitle("CallTune is active")
            .setContentText("Playing your chosen tunes for incoming calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        try {
            startForeground(1, n)
        } catch (e: Exception) {
            note("svc_log", "foreground start failed: ${e.javaClass.simpleName}")
            stopSelf()
            return
        }
        if (!enabled()) { stopSelf(); return }
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
        val o = IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(outReceiver, o, Context.RECEIVER_EXPORTED)
            else registerReceiver(outReceiver, o)
        } catch (e: Exception) { note("out_log", "could not listen for outgoing calls: ${e.javaClass.simpleName}") }
        ready = true
        instance = this
        note("svc_log", "service ready; phone-state listener registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready || !enabled()) { stopSelf(); return START_NOT_STICKY }
        // onCreate can run after the ringing broadcast. Recover the current call
        // instead of waiting for a second call to initialise playback.
        val telecom = RinomInCallService.current
        if (telecom != null) onTelecomCallsChanged(telecom.calls)
        else if (isRinging() && incomingSession.select(legacyIncoming, null)) handleRing(this, null)
        return START_STICKY
    }

    private fun play(uri: Uri, allowFallback: Boolean = true) {
        stop()
        val token = incomingSession.token()
        if (!mayPlay(token)) return
        val media = MediaPlayer()
        fun recover() {
            if (player !== media) return
            stop()
            if (allowFallback && mayPlay(token))
                play(Uri.parse("android.resource://$packageName/${R.raw.default_tune}"), false)
        }
        player = media // Retain it before setup so every failure can release it.
        try {
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            media.setDataSource(this, uri)
            media.isLooping = true
            media.setOnPreparedListener {
                if (player !== media || !mayPlay(token)) {
                    if (player === media) stop()
                    return@setOnPreparedListener
                }
                try {
                    media.start()
                    note("play_log", "playing OK; incoming tune")
                    raiseVolume()
                    main.postDelayed(autoStop, 60_000)
                } catch (e: Exception) {
                    note("play_log", "start failed: ${e.javaClass.simpleName}")
                    recover()
                }
            }
            media.setOnErrorListener { _, what, extra ->
                note("play_log", "player error: $what/$extra")
                recover()
                true
            }
            media.prepareAsync()
        } catch (e: Exception) {
            note("play_log", "prepare failed: ${e.javaClass.simpleName}")
            recover()
        }
    }

    private fun raiseVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (prevMedia == null) prevMedia = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.8).toInt()
            if (target > (prevMedia ?: 0))
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {}
    }

    private fun restoreVolume() {
        prevMedia?.let {
            try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            catch (_: Exception) {}
        }
        prevMedia = null
    }

    private fun stop() {
        main.removeCallbacks(autoStop)
        restoreVolume()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    override fun onDestroy() {
        destroyed = true
        ready = false
        if (instance === this) instance = null
        note("svc_log", "service stopped")
        stopIncoming()
        stopOutgoing("service destroyed")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { unregisterReceiver(outReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
