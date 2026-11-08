package com.calltune.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.widget.*

/** SIM calls are placed through Telecom; sign-in is never required to make a call. */
class DialerActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var status: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Rinom Calltune Phone"; textSize = 24f })
        status = TextView(this)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Choose as default Phone app"
            setOnClickListener { requestRole() }
        })
        number = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(state?.getString("number") ?: intent.data?.schemeSpecificPart.orEmpty())
        }
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(number, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@DialerActivity).apply {
                text = "Delete"
                isAllCaps = false
                contentDescription = "Delete digit. Hold to clear number"
                setOnClickListener { deleteNumberSelection() }
                setOnLongClickListener {
                    number.text.clear()
                    true
                }
            }, LinearLayout.LayoutParams(-2, -2))
        })
        for (row in listOf("123", "456", "789", "*0#")) {
            root.addView(LinearLayout(this).apply {
                row.forEach { digit -> addView(Button(this@DialerActivity).apply {
                    text = if (digit == '0') "0\n+" else digit.toString()
                    setOnClickListener { replaceNumberSelection(digit.toString()) }
                    if (digit == '0') {
                        contentDescription = "Zero. Hold for plus"
                        setOnLongClickListener {
                            replaceNumberSelection("+")
                            true
                        }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f)) }
            })
        }
        root.addView(Button(this).apply {
            text = "Call using SIM"
            setOnClickListener { placeCall() }
        })
        root.addView(Button(this).apply {
            text = "Return to ongoing call"
            setOnClickListener { startActivity(Intent(this@DialerActivity, InCallActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "CallTune settings"
            setOnClickListener { startActivity(Intent(this@DialerActivity, MainActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Show recent calls"
            setOnClickListener { showRecentCalls() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        number.setText(intent.data?.schemeSpecificPart.orEmpty())
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isDefault()) "Default Phone app. Calls use your mobile network."
        else "Choose CallTune as your default Phone app to enable answered-call detection."
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("number", number.text.toString())
        super.onSaveInstanceState(out)
    }

    private fun replaceNumberSelection(value: String) {
        val text = number.text
        val first = number.selectionStart.takeIf { it >= 0 } ?: text.length
        val second = number.selectionEnd.takeIf { it >= 0 } ?: first
        val start = minOf(first, second)
        text.replace(start, maxOf(first, second), value)
        number.setSelection((start + value.length).coerceAtMost(text.length))
    }

    private fun deleteNumberSelection() {
        val text = number.text
        val first = number.selectionStart.takeIf { it >= 0 } ?: text.length
        val second = number.selectionEnd.takeIf { it >= 0 } ?: first
        val start = minOf(first, second)
        val end = maxOf(first, second)
        when {
            start != end -> { text.delete(start, end); number.setSelection(start) }
            start > 0 -> { text.delete(start - 1, start); number.setSelection(start - 1) }
        }
    }

    private fun isDefault() = getSystemService(TelecomManager::class.java).defaultDialerPackage == packageName

    private fun requestRole() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            return
        }
        if (isDefault()) return
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_DIALER))
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), 40)
        } else {
            startActivityForResult(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName), 40)
        }
    }

    private fun placeCall() {
        val target = number.text.toString().trim()
        if (target.isBlank()) return
        if (!isDefault()) { requestRole(); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 41)
            return // Never place a call automatically after a permission dialog.
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", target, null), Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Could not place call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRecentCalls() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), 42)
            return
        }
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < 20) {
                    val target = cursor.getString(0).orEmpty()
                    val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cursor.getLong(1)))
                    val type = when (cursor.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }
                    root.addView(Button(this).apply {
                        text = "$type • $target\n$date"
                        setOnClickListener { number.setText(target) }
                    })
                }
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "Recent calls permission is unavailable.", Toast.LENGTH_LONG).show()
        }
    }
}
