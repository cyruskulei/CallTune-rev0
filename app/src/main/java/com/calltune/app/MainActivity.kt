
package com.calltune.app

import com.google.firebase.FirebaseException
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var phoneBox: EditText
    private lateinit var codeBox: EditText
    private lateinit var callerNumberBox: EditText
    private lateinit var authStatus: TextView
    private lateinit var verifiedNumber: TextView
    private lateinit var list: TextView
    private lateinit var modeBtn: Button
    private lateinit var callerBtn: Button
    private lateinit var sendCodeBtn: Button
    private lateinit var verifyBtn: Button

    private var verificationId: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        auth = FirebaseAuth.getInstance()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.rinom_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                (96 * resources.displayMetrics.density).toInt(),
                (96 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = pad / 2
            }
        })

        root.addView(TextView(this).apply {
            text = "Rinom Calltune"
            textSize = 22f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })

        root.addView(Button(this).apply {
            text = "Open Phone and default app setup"
            setOnClickListener { startActivity(Intent(this@MainActivity, DialerActivity::class.java)) }
        })

        root.addView(TextView(this).apply {
            text = "\nRegister or sign in with your mobile number"
            textSize = 18f
        })

        phoneBox = EditText(this).apply {
            hint = "International number, e.g. +254712345678"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(phoneBox)

        sendCodeBtn = Button(this).apply {
            text = "Send verification code"
            setOnClickListener { sendVerificationCode() }
        }
        root.addView(sendCodeBtn)

        codeBox = EditText(this).apply {
            hint = "Enter SMS verification code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            visibility = android.view.View.GONE
        }
        root.addView(codeBox)

        verifyBtn = Button(this).apply {
            text = "Verify code and sign in"
            visibility = android.view.View.GONE
            setOnClickListener { verifyCode() }
        }
        root.addView(verifyBtn)

        authStatus = TextView(this)
        root.addView(authStatus)

        verifiedNumber = TextView(this)
        root.addView(verifiedNumber)

        updateAuthDisplay()

        root.addView(TextView(this).apply {
            text = "\nChoose your incoming-call tune:"
            textSize = 18f
        })

        callerNumberBox = EditText(this).apply {
            hint = "Caller number (optional)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(
                getSharedPreferences("tune_input", MODE_PRIVATE)
                    .getString("caller_number", "")
            )
        }
        root.addView(callerNumberBox)

        root.addView(Button(this).apply {
            text = "Tune for a caller (this phone)"
            setOnClickListener {
                getSharedPreferences("tune_input", MODE_PRIVATE)
                    .edit()
                    .putString("caller_number", callerNumberBox.text.toString())
                    .apply()
                pickAudio(1)
            }
        })

        root.addView(TextView(this).apply {
            text = "\nYour cloud tune:"
        })

        root.addView(Button(this).apply {
            text = "Choose my tune for others (cloud)"
            setOnClickListener {
                if (auth.currentUser?.phoneNumber.isNullOrBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Verify your mobile number first.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Your number is verified. Secure cloud uploads will be connected in the next step. No upload has been made.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Sign out"
            setOnClickListener {
                auth.signOut()
                verificationId = null
                codeBox.text.clear()
                codeBox.visibility = android.view.View.GONE
                verifyBtn.visibility = android.view.View.GONE
                sendCodeBtn.isEnabled = true
                updateAuthDisplay()
                Toast.makeText(
                    this@MainActivity,
                    "You have signed out.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        modeBtn = Button(this).apply {
            setOnClickListener {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                val next =
                    if (sp.getString("mode", "free") == "free") "pro" else "free"
                sp.edit().putString("mode", next).apply()
                updateModeText()
            }
        }
        root.addView(modeBtn)
        updateModeText()

        callerBtn = Button(this).apply {
            setOnClickListener {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                sp.edit().putBoolean(
                    "caller_hears",
                    !sp.getBoolean("caller_hears", true)
                ).apply()
                updateCallerText()
            }
        }
        root.addView(callerBtn)
        updateCallerText()

        root.addView(Button(this).apply {
            text = "Test play my saved tune"
            setOnClickListener { testPlay() }
        })

        root.addView(Button(this).apply {
            text = "Start CallTune"
            setOnClickListener { askPermsThenStart() }
        })

        root.addView(Button(this).apply {
            text = "Stop CallTune"
            setOnClickListener {
                setAutostart(false)
                applyAudioProfile(false)
                stopService(Intent(this@MainActivity, CallService::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "Clear all tunes"
            setOnClickListener {
                TuneStore.all(this@MainActivity).keys.forEach {
                    TuneStore.remove(this@MainActivity, it)
                }
                refresh()
            }
        })

        list = TextView(this)
        root.addView(list)

        // Keep controls readable on smaller screens.
        for (i in 0 until root.childCount) {
            when (val child = root.getChildAt(i)) {
                is Button -> {
                    child.textSize = 14f
                    child.isAllCaps = false
                }
                is EditText -> child.textSize = 16f
            }
        }

        setContentView(ScrollView(this).apply {
            addView(root)
        })

        refresh()
    }

    private fun sendVerificationCode() {
        authStatus.text = "Send button tapped. Checking phone number..."

        val number = phoneBox.text.toString().trim()

        if (!number.startsWith("+") ||
            number.count { it.isDigit() } !in 8..15
        ) {
            authStatus.text =
                "Invalid number. Enter an international number starting with +."
            Toast.makeText(
                this,
                "Enter a valid international number, such as +254712345678.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        sendCodeBtn.isEnabled = false
        authStatus.text = "Contacting Firebase to request an SMS code..."

        Toast.makeText(
            this,
            "Requesting verification code...",
            Toast.LENGTH_SHORT
        ).show()

        val callbacks =
            object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(
                    credential: PhoneAuthCredential
                ) {
                    authStatus.text = "Phone verified automatically. Signing in..."
                    signInWithPhoneCredential(credential)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    sendCodeBtn.isEnabled = true
                    authStatus.text = "Verification failed: ${e.message}"
                    Toast.makeText(
                        this@MainActivity,
                        "Could not send verification code: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onCodeSent(
                    id: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    verificationId = id
                    codeBox.visibility = android.view.View.VISIBLE
                    verifyBtn.visibility = android.view.View.VISIBLE
                    sendCodeBtn.isEnabled = true
                    authStatus.text =
                        "SMS sent to $number. Enter the code you received."
                    Toast.makeText(
                        this@MainActivity,
                        "Verification code sent.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onCodeAutoRetrievalTimeOut(id: String) {
                    verificationId = id
                    sendCodeBtn.isEnabled = true
                    authStatus.text =
                        "Enter the SMS code manually to finish verification."
                }
            }

        try {
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(number)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)

        } catch (e: Exception) {
            sendCodeBtn.isEnabled = true
            authStatus.text =
                "Could not start verification: ${e.javaClass.simpleName}: ${e.message}"
            Toast.makeText(
                this,
                authStatus.text,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun verifyCode() {
        val id = verificationId
        val code = codeBox.text.toString().trim()

        if (id.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Please request an SMS code first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (code.length < 4) {
            Toast.makeText(
                this,
                "Enter the verification code from your SMS.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        authStatus.text = "Checking verification code..."

        val credential = PhoneAuthProvider.getCredential(id, code)
        signInWithPhoneCredential(credential)
    }

    private fun signInWithPhoneCredential(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val number = auth.currentUser?.phoneNumber

                    if (!number.isNullOrBlank()) {
                        getSharedPreferences("me", MODE_PRIVATE)
                            .edit()
                            .putString("verified_number", number)
                            .putString("number", number)
                            .apply()
                    }

                    verificationId = null
                    codeBox.text.clear()
                    codeBox.visibility = android.view.View.GONE
                    verifyBtn.visibility = android.view.View.GONE
                    sendCodeBtn.isEnabled = true

                    updateAuthDisplay()

                    Toast.makeText(
                        this,
                        "Your phone number has been verified. Welcome to CallTune!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    authStatus.text =
                        "Sign-in failed: ${task.exception?.message}"
                    Toast.makeText(
                        this,
                        "Verification failed. Check the code and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun updateAuthDisplay() {
        val user = auth.currentUser
        val number = user?.phoneNumber

        if (number.isNullOrBlank()) {
            authStatus.text = "Not signed in. Verify your mobile number to register."
            verifiedNumber.text = "Verified number: none"
        } else {
            authStatus.text = "Signed in successfully."
            verifiedNumber.text = "Verified number: $number"
            phoneBox.setText(number)
        }
    }

    private fun pickAudio(code: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            },
            code
        )
    }

    private fun trim(uri: Uri, code: Int) {
        startActivityForResult(
            Intent(this, TrimActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            code
        )
    }

    override fun onActivityResult(
        req: Int,
        res: Int,
        data: Intent?
    ) {
        super.onActivityResult(req, res, data)

        val uri = data?.data

        if (res != RESULT_OK || uri == null) {
            if (req == 11 || req == 13) {
                Toast.makeText(
                    this,
                    "No trimmed tune came back (code $res)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        when (req) {
            1 -> trim(uri, 11)

            3 -> trim(uri, 13)

            11 -> {
                val who = getSharedPreferences("tune_input", MODE_PRIVATE)
                    .getString("caller_number", "") ?: ""

                TuneStore.save(this, who, uri)
                refresh()

                Toast.makeText(
                    this,
                    "Tune saved for " +
                        if (who.isBlank()) "all callers" else who,
                    Toast.LENGTH_LONG
                ).show()
            }

            13 -> {
                Toast.makeText(
                    this,
                    "Secure cloud upload will be enabled in the next step. No upload has been made.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateCallerText() {
        val on = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("caller_hears", true)

        callerBtn.text =
            if (on) "When I call: I hear the receiver's tune (ON). Tap to switch"
            else "When I call: I hear the receiver's tune (OFF). Tap to switch"
    }

    private fun updateModeText() {
        val free = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("mode", "free") == "free"

        modeBtn.text =
            if (free) "Mode: FREE (plays my own tune). Tap to switch"
            else "Mode: PRO (incoming caller tune). Tap to switch"
    }

    private fun testPlay() {
        val uri = TuneStore.findDefault(this)
            ?: TuneStore.all(this).values.firstOrNull()?.let {
                Uri.parse(it)
            }

        if (uri == null) {
            Toast.makeText(
                this,
                "No saved tune. Save one first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val mp = android.media.MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                start()
            }

            Toast.makeText(
                this,
                "Playing for 6 seconds...",
                Toast.LENGTH_SHORT
            ).show()

            android.os.Handler(mainLooper).postDelayed({
                try {
                    mp.stop()
                } catch (_: Exception) {
                }
                mp.release()
            }, 6000)

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Cannot play: ${e.javaClass.simpleName} ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refresh() {
        val tunes = TuneStore.all(this)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val boot = sp.getString(
            "boot_log",
            "none yet (restart the phone to test)"
        )

        list.text =
            (if (tunes.isEmpty()) "\nNo tunes saved yet." else
                "\nSaved tunes:\n" + tunes.entries.joinToString("\n") {
                    "• ${if (it.key == "default") "All callers" else it.key}"
                }) +
            "\n\nLast boot check: $boot" +
            "\nLast ring: " + sp.getString("ring_log", "none yet") +
            "\nLast play: " + sp.getString("play_log", "none yet") +
            "\nService: " + sp.getString("svc_log", "none yet") +
            "\nRinger: " + sp.getString("ringer_log", "none yet") +
            "\nStop: " + sp.getString("stop_log", "none yet") +
            "\nWhen I call: " + sp.getString("out_log", "none yet")
    }

    private fun askPermsThenStart() {
        val need = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS
        )

        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = need.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) start()
        else requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(
        c: Int,
        p: Array<out String>,
        r: IntArray
    ) {
        super.onRequestPermissionsResult(c, p, r)

        val ok =
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

        if (ok) start()
        else Toast.makeText(
            this,
            "Permissions are required",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setAutostart(on: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit()
            .putBoolean("autostart", on)
            .apply()
    }

    private fun applyAudioProfile(on: Boolean) {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val am = getSystemService(android.media.AudioManager::class.java)
        val nm = getSystemService(android.app.NotificationManager::class.java)

        try {
            if (on) {
                if (!nm.isNotificationPolicyAccessGranted) {
                    if (!sp.getBoolean("asked_dnd", false)) {
                        sp.edit().putBoolean("asked_dnd", true).apply()

                        Toast.makeText(
                            this,
                            "To silence the normal ringtone automatically: switch CallTune ON in this list, then come back and tap Start again.",
                            Toast.LENGTH_LONG
                        ).show()

                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                            )
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "CallTune started. Set your phone ringtone to silent_ringtone (or ringtone volume to zero) so only the tune plays.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }

                if (!sp.getBoolean("audio_profile_on", false)) {
                    sp.edit()
                        .putInt("saved_ringer", am.ringerMode)
                        .putBoolean("audio_profile_on", true)
                        .apply()
                }

                am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT

                val max = am.getStreamMaxVolume(
                    android.media.AudioManager.STREAM_MUSIC
                )

                if (am.getStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC
                    ) < max / 2
                ) {
                    am.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        (max * 0.7).toInt(),
                        0
                    )
                }

                Toast.makeText(
                    this,
                    "CallTune started. Ringer silenced, media on.",
                    Toast.LENGTH_LONG
                ).show()

            } else if (sp.getBoolean("audio_profile_on", false)) {
                am.ringerMode = sp.getInt(
                    "saved_ringer",
                    android.media.AudioManager.RINGER_MODE_NORMAL
                )
                sp.edit().putBoolean("audio_profile_on", false).apply()

                Toast.makeText(
                    this,
                    "CallTune stopped. Ringer restored.",
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "CallTune started, but the ringer could not be changed: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun start() {
        setAutostart(true)
        applyAudioProfile(true)
        startForegroundService(
            Intent(this, CallService::class.java)
        )
    }
}
