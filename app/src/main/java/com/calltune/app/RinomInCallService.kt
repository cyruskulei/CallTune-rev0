package com.calltune.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.content.ContextCompat
import java.util.UUID

/** Bound only by Android Telecom. Calls and numbers are never sent to a server here. */
class RinomInCallService : InCallService() {
    private val callbacks = linkedMapOf<Call, Call.Callback>()
    private val callTokens = mutableMapOf<Call, String>()
    private val main = Handler(Looper.getMainLooper())
    private var shownRingingCall: Call? = null
    private var playbackStartRequested = false

    companion object {
        var current: RinomInCallService? = null
            private set
        val listeners = mutableSetOf<() -> Unit>()
        const val CALL_CHANNEL = "calls"
        private const val NOTIFICATION_ID = 200
        private const val CONTROL = "com.calltune.app.PRIVATE_CALL_CONTROL"
    }

    // Only this app's immutable PendingIntents can deliver these actions. A token belongs
    // to one call, so an old notification can never answer/reject a subsequent caller.
    private val controls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CONTROL) return
            val operation = intent.getStringExtra("operation")
            if (operation !in listOf("answer", "reject", "end")) { record("Unknown control ignored"); return }
            val token = intent.getStringExtra("call_token")
            val call = callTokens.entries.firstOrNull { it.value == token }?.key
            if (call == null) { record("$operation ignored: expired call control"); return }
            record("$operation received; state=${call.state}")
            try {
                when (operation) {
                    "answer" -> if (call.state == Call.STATE_RINGING) call.answer(0)
                    "reject" -> if (call.state == Call.STATE_RINGING) {
                        call.reject(false, null)
                        // If rejection has not completed, retry only for this exact,
                        // still-ringing call. Never disconnect
                        // an answered or replacement call because of an old notification.
                        main.postDelayed({
                            if (callTokens[call] == token && call.state == Call.STATE_RINGING) {
                                try { record("Reject fallback: disconnect requested"); call.disconnect() }
                                catch (e: Exception) { failure("Reject fallback", e) }
                            }
                        }, 1000)
                    }
                    "end" -> if (call.state != Call.STATE_DISCONNECTED) call.disconnect()
                }
                record("$operation command sent; awaiting Telecom state")
            } catch (e: Exception) { failure("Call control", e) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                // Telecom/CallTune playback already owns the ringtone. Do not add a second one.
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        ContextCompat.registerReceiver(this, controls, IntentFilter(CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)
        record("Phone service ready")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        callTokens[call] = UUID.randomUUID().toString()
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                record("Call state changed: $state")
                update()
            }
            override fun onDetailsChanged(call: Call, details: Call.Details) = update()
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = update()
        }
        callbacks[call] = callback
        call.registerCallback(callback, main)
        record("Call received by Phone service; state=${call.state}")
        update()
        if (call.state != Call.STATE_RINGING) showCallUi()
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        callTokens.remove(call)
        super.onCallRemoved(call)
        update()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        refreshListeners()
    }

    private fun actionIntent(call: Call, operation: String): PendingIntent {
        val token = requireNotNull(callTokens[call])
        val intent = Intent(CONTROL).setPackage(packageName)
            .setData(Uri.parse("rinom-call://control/$token/$operation"))
            .putExtra("call_token", token).putExtra("operation", operation)
        return PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun update() {
        if (CallService.instance != null) playbackStartRequested = false
        val active = callbacks.keys.filter { it.state != Call.STATE_DISCONNECTED }
        val call = active.firstOrNull { it.state == Call.STATE_RINGING } ?: active.firstOrNull()
        if (active.isEmpty()) playbackStartRequested = false
        val nm = getSystemService(NotificationManager::class.java)
        val ringing = call?.state == Call.STATE_RINGING
        val newRing = ringing && shownRingingCall !== call
        shownRingingCall = if (ringing) call else null
        // Publish essential call controls before optional tune/UI code. A playback error
        // must not prevent the user from seeing or answering an incoming call.
        if (call == null) {
            nm.cancel(NOTIFICATION_ID)
        } else {
            try {
                val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                    Intent(this, InCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val title = if (ringing) "Incoming call" else "Call in progress"
                val notification = Notification.Builder(this, CALL_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(title).setContentText("Tap for call controls")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(Notification.Builder(this, CALL_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_menu_call).setContentTitle(title).build())
                    .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(!newRing)
                val end = actionIntent(call, if (ringing) "reject" else "end")
                val fullScreen = ringing &&
                    (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
                // A native CallStyle posted outside a foreground service needs a
                // full-screen intent. Use ordinary controls for other call states
                // and when full-screen permission is unavailable.
                if (Build.VERSION.SDK_INT >= 31 && fullScreen) {
                    val person = Person.Builder().setName("Caller").setImportant(true).build()
                    notification.setStyle(
                        Notification.CallStyle.forIncomingCall(person, end, actionIntent(call, "answer")))
                } else {
                    if (ringing) notification.addAction(Notification.Action.Builder(
                        null, "Answer", actionIntent(call, "answer")).build())
                    notification.addAction(Notification.Action.Builder(
                        null, if (ringing) "Reject" else "End call", end).build())
                }
                if (fullScreen)
                    notification.setFullScreenIntent(open, true)
                try {
                    nm.notify(NOTIFICATION_ID, notification.build())
                } catch (e: IllegalArgumentException) {
                    failure("CallStyle notification; state=${call.state}", e)
                    // Keep controls usable if this device rejects a styled update.
                    postFallbackAlert(nm, call, ringing, newRing, open)
                }
                if (newRing) record("Incoming alert posted; notifications=${nm.areNotificationsEnabled()}; channel=${nm.getNotificationChannel(CALL_CHANNEL)?.importance}; fullScreen=${Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()}")
            } catch (e: Exception) { failure("Call notification; state=${call.state}", e) }
        }
        try {
            val playback = CallService.instance
            if (playback != null) playback.onTelecomCallsChanged(active)
            else if (active.isNotEmpty() && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autostart", false)) {
                // Telecom can bind before the boot-started player is ready, or after
                // its process was reclaimed. onStartCommand reconciles this snapshot.
                if (!playbackStartRequested) {
                    playbackStartRequested = true
                    startForegroundService(Intent(this, CallService::class.java))
                    record("Enabled playback start requested from Telecom")
                }
            }
        } catch (e: Exception) { playbackStartRequested = false; failure("Tune startup/update", e) }
        refreshListeners()
        // Telecom-bound services are allowed to bring their call UI forward. Keep this
        // fallback, and report failures instead of silently swallowing them.
        if (newRing) showCallUi()
    }

    private fun postFallbackAlert(nm: NotificationManager, call: Call, ringing: Boolean,
                                  newRing: Boolean, open: PendingIntent) {
        val fallback = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (ringing) "Incoming call" else "Call in progress")
            .setContentText("Tap for call controls").setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true)
            .setContentIntent(open).setOnlyAlertOnce(!newRing)
        if (ringing) fallback.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call,
            "Answer", actionIntent(call, "answer")).build())
        fallback.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call,
            if (ringing) "Reject" else "End call", actionIntent(call, if (ringing) "reject" else "end")).build())
        if (ringing && (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()))
            fallback.setFullScreenIntent(open, true)
        nm.notify(NOTIFICATION_ID, fallback.build())
        record("Fallback call controls posted")
    }

    private fun showCallUi() {
        try {
            startActivity(Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { record("Call screen blocked: ${e.javaClass.simpleName}") }
    }

    private fun failure(stage: String, error: Exception) {
        // Stack frames identify the failing operation without exposing intent extras,
        // phone numbers, call tokens or cloud credentials in the on-device report.
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.calltune.app") }
        record("$stage failed: ${error.javaClass.simpleName}; ${frame?.fileName}:${frame?.lineNumber}")
        android.util.Log.e("RinomTelecom", "$stage failed", error)
    }

    private fun record(message: String) {
        android.util.Log.i("RinomTelecom", message)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val previous = prefs.getString("phone_ui_log", "").orEmpty()
        prefs.edit().putString("phone_ui_log", "$previous\n$time $message".takeLast(2000)).apply()
    }

    private fun refreshListeners() {
        listeners.toList().forEach {
            try { it() } catch (e: Exception) { record("Call UI refresh failed: ${e.javaClass.simpleName}") }
        }
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) = refreshListeners()
    override fun onBringToForeground(showDialpad: Boolean) = showCallUi()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(controls) } catch (_: IllegalArgumentException) { }
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        callTokens.clear()
        try { CallService.instance?.onTelecomCallsChanged(emptyList()) }
        catch (e: Exception) { record("Tune cleanup failed: ${e.javaClass.simpleName}") }
        if (current === this) current = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        refreshListeners()
        super.onDestroy()
    }
}
