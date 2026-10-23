package com.calltune.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Stores each person's tune in Firestore (via REST), keyed by phone number. */
object Cloud {
    private const val MAX_BYTES = 600_000

    private fun docUrl(num: String) =
        "https://firestore.googleapis.com/v1/projects/${Config.PROJECT_ID}" +
        "/databases/(default)/documents/tunes/$num?key=${Config.API_KEY}"

    fun cachedFile(ctx: Context, number: String): File? {
        val f = File(ctx.cacheDir, "tune_${TuneStore.norm(number)}.audio")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Returns null on success, or an error message. */
    fun upload(ctx: Context, number: String, uri: Uri): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "Could not read the file"
            if (bytes.size > MAX_BYTES) return "File too big. Use a tune under 600 KB."
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val body = JSONObject().put("fields",
                JSONObject().put("data", JSONObject().put("stringValue", b64)))
            val c = URL(docUrl(TuneStore.norm(number))).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code in 200..299) null else "Upload failed (error $code)"
        } catch (e: Exception) {
            "Upload failed: ${e.message}"
        }
    }

    /** Downloads the caller's tune into the cache. Returns the file, or null if none. */
    fun fetch(ctx: Context, number: String): File? {
        return try {
            val c = URL(docUrl(TuneStore.norm(number))).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) return cachedFile(ctx, number)
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val b64 = json.getJSONObject("fields").getJSONObject("data").getString("stringValue")
            val f = File(ctx.cacheDir, "tune_${TuneStore.norm(number)}.audio")
            f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            f
        } catch (e: Exception) {
            cachedFile(ctx, number)
        }
    }
}
