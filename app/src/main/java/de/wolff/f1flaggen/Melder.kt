package de.wolff.f1flaggen

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Schickt Ereignisse per Webhook an Home Assistant (lokal, ohne Cloud). */
object Melder {
    private val ausfuehrer = Executors.newSingleThreadExecutor()

    @Volatile
    var letzteAntwort: String = "–"

    fun sende(ctx: Context, ereignis: String, text: String = "", webhook: String? = null, extra: Map<String, Any?> = emptyMap()) {
        val e = Einstellungen(ctx.applicationContext)
        val ziel = e.haUrl.trimEnd('/') + "/api/webhook/" + (webhook ?: e.webhook)
        ausfuehrer.execute {
            try {
                val json = JSONObject()
                    .put("event", ereignis)
                    .put("text", text.take(400))
                    .put("ts", System.currentTimeMillis())
                    .also { j -> extra.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } }
                    .toString()
                val verbindung = URL(ziel).openConnection() as HttpURLConnection
                verbindung.requestMethod = "POST"
                verbindung.connectTimeout = 3000
                verbindung.readTimeout = 3000
                verbindung.doOutput = true
                verbindung.setRequestProperty("Content-Type", "application/json")
                verbindung.outputStream.use { it.write(json.toByteArray()) }
                val code = verbindung.responseCode
                verbindung.disconnect()
                letzteAntwort = "$ereignis → HTTP $code"
            } catch (ex: Exception) {
                letzteAntwort = "$ereignis → Fehler: ${ex.message}"
                Log.w("F1Flaggen", "Senden fehlgeschlagen", ex)
            }
        }
    }
}
