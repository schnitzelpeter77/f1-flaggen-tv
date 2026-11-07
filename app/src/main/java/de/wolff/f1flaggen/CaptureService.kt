package de.wolff.f1flaggen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Nimmt das TV-Bild auf (ca. alle 0,7 s), liest den Text per Texterkennung
 * und meldet erkannte Rennleitungs-Ereignisse an Home Assistant.
 */
class CaptureService : Service() {

    companion object {
        const val AKTION_STOP = "de.wolff.f1flaggen.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATEN = "daten"
        private const val BREITE = 1280
        private const val HOEHE = 720
        private const val TAKT_MS = 700L
        private const val KANAL = "erkennung"

        @Volatile var laeuft = false
        @Volatile var status = "aus"
        @Volatile var letztesEreignis = "–"
    }

    private var projektion: MediaProjection? = null
    private var anzeige: VirtualDisplay? = null
    private var leser: ImageReader? = null
    private lateinit var faden: HandlerThread
    private lateinit var handler: Handler
    private val texterkennung = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val erkenner = Erkenner()
    private val werder = WerderErkenner()
    private var letztesWerderDebug = 0L
    @Volatile private var beschaeftigt = false
    private var letztesDebug = 0L
    private var schwarzeBilder = 0
    private var schwarzGemeldet = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AKTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        starteVordergrund()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val daten: Intent? = intent?.getParcelableExtra(EXTRA_DATEN)
        if (code != Activity.RESULT_OK || daten == null) {
            status = "keine Freigabe für Bildaufnahme"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!laeuft) starteAufnahme(code, daten)
        return START_NOT_STICKY
    }

    private fun starteVordergrund() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL, "F1 Flaggen-Erkennung", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, KANAL)
            .setContentTitle("F1 Flaggen-Erkennung läuft")
            .setContentText("Liest F1-Flaggen und den Werder-Spielstand aus dem TV-Bild")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun starteAufnahme(code: Int, daten: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, daten)
        projektion = p
        faden = HandlerThread("erkennung").also { it.start() }
        handler = Handler(faden.looper)

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                status = "Bildaufnahme vom System beendet"
                stopSelf()
            }
        }, handler)

        val r = ImageReader.newInstance(BREITE, HOEHE, PixelFormat.RGBA_8888, 2)
        leser = r
        anzeige = p.createVirtualDisplay(
            "F1Flaggen", BREITE, HOEHE, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        laeuft = true
        status = "läuft"
        Melder.sende(this, "started")
        handler.postDelayed(schleife, TAKT_MS)
    }

    private val schleife = object : Runnable {
        override fun run() {
            if (!laeuft) return
            try {
                pruefeBild()
            } catch (ex: Exception) {
                Log.w("F1Flaggen", "Fehler beim Auswerten", ex)
            }
            handler.postDelayed(this, TAKT_MS)
        }
    }

    private fun pruefeBild() {
        if (beschaeftigt) return
        val bild = leser?.acquireLatestImage() ?: return
        val bitmap: Bitmap
        try {
            val ebene = bild.planes[0]
            val pixelAbstand = ebene.pixelStride
            val zeilenAbstand = ebene.rowStride
            val rand = zeilenAbstand - pixelAbstand * BREITE
            val roh = Bitmap.createBitmap(BREITE + rand / pixelAbstand, HOEHE, Bitmap.Config.ARGB_8888)
            roh.copyPixelsFromBuffer(ebene.buffer)
            bitmap = if (rand == 0) roh else Bitmap.createBitmap(roh, 0, 0, BREITE, HOEHE)
        } finally {
            bild.close()
        }

        // Kopiergeschützte Apps liefern nur ein schwarzes Bild – einmalig melden
        if (istSchwarz(bitmap)) {
            schwarzeBilder++
            if (schwarzeBilder >= 15 && !schwarzGemeldet) {
                schwarzGemeldet = true
                status = "läuft – Bild ist schwarz (Kopierschutz?)"
                Melder.sende(this, "black_screen")
            }
            return
        } else {
            schwarzeBilder = 0
            if (schwarzGemeldet) {
                schwarzGemeldet = false
                status = "läuft"
            }
        }

        beschaeftigt = true
        texterkennung.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { ergebnis ->
                val ereignis = erkenner.verarbeite(ergebnis.text)
                if (ereignis != null) {
                    letztesEreignis = ereignis
                    Melder.sende(this, ereignis, erkenner.letzterText)
                }
                val jetzt = System.currentTimeMillis()
                if (Einstellungen(this).debug && jetzt - letztesDebug > 15_000) {
                    letztesDebug = jetzt
                    Melder.sende(this, "debug", erkenner.letzterText)
                }
            }
            .addOnCompleteListener { pruefeSpielstand(bitmap) }
    }

    /**
     * Werder: oberen Bildrand (Spielstand-Einblendung) doppelt vergrößert lesen.
     * Läuft nach der F1-Erkennung im selben Takt; danach ist der Durchlauf fertig.
     */
    private fun pruefeSpielstand(bitmap: Bitmap) {
        val ausschnitt = try {
            val h = (HOEHE * 0.22f).toInt()
            Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, 0, 0, BREITE, h), BREITE * 2, h * 2, true)
        } catch (ex: Exception) {
            beschaeftigt = false
            return
        }
        texterkennung.process(InputImage.fromBitmap(ausschnitt, 0))
            .addOnSuccessListener { ergebnis ->
                // Zeilen von oben nach unten, links nach rechts zu einem Text zusammensetzen
                val zeilen = ergebnis.textBlocks.flatMap { it.lines }
                    .sortedWith(compareBy({ (it.boundingBox?.top ?: 0) / 40 }, { it.boundingBox?.left ?: 0 }))
                val text = zeilen.joinToString(" ") { it.text }
                val ev = werder.verarbeite(text)
                val e = Einstellungen(this)
                if (ev != null) {
                    if (ev.art != "stand") letztesEreignis = "Werder ${ev.art} ${ev.alsText()}"
                    Melder.sende(
                        this, ev.art, ev.alsText(), webhook = e.werderWebhook,
                        extra = mapOf(
                            "werder" to ev.stand.werder, "gegner" to ev.stand.gegner,
                            "gegner_name" to ev.stand.gegnerName, "minute" to ev.minute
                        )
                    )
                }
                val jetzt = System.currentTimeMillis()
                if (e.debug && jetzt - letztesWerderDebug > 15_000) {
                    letztesWerderDebug = jetzt
                    Melder.sende(this, "debug", werder.letzterText, webhook = e.werderWebhook)
                }
            }
            .addOnCompleteListener { beschaeftigt = false }
    }

    private fun istSchwarz(b: Bitmap): Boolean {
        var hell = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                val summe = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                if (summe > 30) hell++
                x += 64
            }
            y += 48
        }
        return hell < 3
    }

    override fun onDestroy() {
        laeuft = false
        if (status.startsWith("läuft")) status = "aus"
        try { handler.removeCallbacks(schleife) } catch (_: Exception) {}
        anzeige?.release()
        leser?.close()
        projektion?.stop()
        texterkennung.close()
        if (::faden.isInitialized) faden.quitSafely()
        Melder.sende(this, "stopped")
        super.onDestroy()
    }
}
