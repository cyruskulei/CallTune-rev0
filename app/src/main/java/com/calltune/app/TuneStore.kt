package com.calltune.app

import android.content.Context
import android.net.Uri

/** Saves number -> tune mappings locally. Key "default" is used for any unmapped caller. */
object TuneStore {
    private fun prefs(c: Context) = c.getSharedPreferences("tunes", Context.MODE_PRIVATE)

    fun norm(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length > 9) d.takeLast(9) else d
    }

    fun save(c: Context, number: String, uri: Uri) {
        val key = if (number.isBlank()) "default" else norm(number)
        prefs(c).edit().putString(key, uri.toString()).apply()
    }

    fun remove(c: Context, key: String) = prefs(c).edit().remove(key).apply()

    fun find(c: Context, number: String?): Uri? {
        val p = prefs(c)
        val s = (number?.let { p.getString(norm(it), null) }) ?: p.getString("default", null)
        return s?.let { Uri.parse(it) }
    }

    fun all(c: Context): Map<String, String> =
        prefs(c).all.mapValues { it.value.toString() }
}
