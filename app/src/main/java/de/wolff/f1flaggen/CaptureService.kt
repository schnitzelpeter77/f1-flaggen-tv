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
import com.google.android.gms.tasks.Tasks
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
    private val deutschland = WerderErkenner(WerderErkenner.DEUTSCHLAND)
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
            .setContentText("Liest F1-Flaggen und den Werder-/DFB-Spielstand aus dem TV-Bild")
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
     * Läuft nach der F1-Erkennung auf dem Hintergrund-Faden; danach ist der Durchlauf fertig.
     */
    private fun pruefeSpielstand(bitmap: Bitmap) {
        handler.post {
            try {
                werderDurchlauf(bitmap)
            } catch (ex: Exception) {
                Log.w("F1Flaggen", "Werder-Auswertung fehlgeschlagen", ex)
            } finally {
                beschaeftigt = false
            }
        }
    }

    private fun werderDurchlauf(bitmap: Bitmap) {
        val h = (HOEHE * 0.22f).toInt()
        val ausschnitt = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, 0, 0, BREITE, h), BREITE * 2, h * 2, true)
        val ergebnis = Tasks.await(texterkennung.process(InputImage.fromBitmap(ausschnitt, 0)))

        // Zeilen von oben nach unten, links nach rechts zu einem Text zusammensetzen
        val zeilen = ergebnis.textBlocks.flatMap { it.lines }
            .sortedWith(compareBy({ (it.boundingBox?.top ?: 0) / 40 }, { it.boundingBox?.left ?: 0 }))
        val text = zeilen.joinToString(" ") { it.text }
        val e = Einstellungen(this)
        val debugJetzt = e.debug && System.currentTimeMillis() - letztesWerderDebug > 15_000
        if (debugJetzt) letztesWerderDebug = System.currentTimeMillis()

        // Dieselbe Einblendung für Werder und die Nationalmannschaft auswerten (je eigener Webhook)
        for ((name, erk, hook) in listOf(
            Triple("Werder", werder, e.werderWebhook),
            Triple("DFB", deutschland, e.dfbWebhook),
        )) {
            var t = text
            var ziffernInfo = ""
            // Zweizeilige Grafik: Ziffern stehen unter den Kürzeln und fehlen oft im Text
            if (erk.lies(t).first == null) {
                val woerter = zeilen.flatMap { it.elements }.mapNotNull { el ->
                    el.boundingBox?.let { bx -> WerderErkenner.Wort(el.text, bx.left, bx.top, bx.right, bx.bottom) }
                }
                erk.kuerzelPaar(woerter)?.let { (links, rechts) ->
                    val (za, zb) = liesZiffernPaar(ausschnitt, links, rechts)
                    val uhr = Regex("\\b\\d{1,3}:[0-5]\\d\\b").find(t)?.value
                    ziffernInfo = " | Ziffern ${links.text}=${za ?: "?"} ${rechts.text}=${zb ?: "?"}"
                    if (za != null && zb != null) t = erk.synthetisch(links, rechts, za, zb, uhr)
                }
            }

            val ev = erk.verarbeite(t)
            if (ev != null) {
                if (ev.art != "stand") letztesEreignis = "$name ${ev.art} ${ev.alsText()}"
                Melder.sende(
                    this, ev.art, ev.alsText(), webhook = hook,
                    extra = mapOf(
                        "werder" to ev.stand.werder, "eigen" to ev.stand.werder, "gegner" to ev.stand.gegner,
                        "gegner_name" to ev.stand.gegnerName, "minute" to ev.minute
                    )
                )
            }
            if (debugJetzt) Melder.sende(this, "debug", erk.letzterText + ziffernInfo, webhook = hook)
        }
    }

    /** Ziffernfeld unter einem Kürzel als Schwarz-auf-Weiß-Bild (Höhe 120 px), oder null. */
    private fun ziffernBild(ausschnitt: Bitmap, k: WerderErkenner.Wort): Bitmap? {
        val f = werder.ziffernFeld(k, ausschnitt.width, ausschnitt.height)
        val w = f[2] - f[0]
        val hh = f[3] - f[1]
        if (w < 8 || hh < 8) return null
        val feld = Bitmap.createBitmap(ausschnitt, f[0], f[1], w, hh)
        val px = IntArray(w * hh)
        feld.getPixels(px, 0, w, 0, 0, w, hh)
        val hell = IntArray(px.size) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
        val schwelle = hell.average()
        // Hintergrund = Mehrheit der Pixel; Schrift wird schwarz, Hintergrund weiß
        val dunkelAnteil = hell.count { it < schwelle }.toDouble() / hell.size
        val schriftDunkel = dunkelAnteil < 0.5
        val aus = IntArray(px.size) {
            val istSchrift = if (schriftDunkel) hell[it] < schwelle else hell[it] >= schwelle
            if (istSchrift) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val sw = Bitmap.createBitmap(aus, w, hh, Bitmap.Config.ARGB_8888)
        val faktor = 120f / hh
        return Bitmap.createScaledBitmap(sw, (w * faktor).toInt().coerceAtLeast(1), 120, true)
    }

    /**
     * Liest beide Torzahlen: Ziffernfelder nebeneinander auf weißem Grund ("1 0"),
     * weil einzelne Ziffern (v. a. die 0) von der Texterkennung sonst oft übersehen werden.
     */
    private fun liesZiffernPaar(ausschnitt: Bitmap, links: WerderErkenner.Wort, rechts: WerderErkenner.Wort): Pair<Int?, Int?> {
        val a = ziffernBild(ausschnitt, links) ?: return null to null
        val b = ziffernBild(ausschnitt, rechts) ?: return null to null
        val rand = 50
        val luecke = 40
        val bild = Bitmap.createBitmap(a.width + b.width + rand * 2 + luecke, 120 + rand * 2, Bitmap.Config.ARGB_8888)
        val leinwand = android.graphics.Canvas(bild)
        leinwand.drawColor(android.graphics.Color.WHITE)
        leinwand.drawBitmap(a, rand.toFloat(), rand.toFloat(), null)
        leinwand.drawBitmap(b, (rand + a.width + luecke).toFloat(), rand.toFloat(), null)
        val r = Tasks.await(texterkennung.process(InputImage.fromBitmap(bild, 0)))
        // Wörter nach Position zuordnen: linke Hälfte = linkes Team
        val mitte = rand + a.width + luecke / 2
        val elemente = r.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        var za: Int? = null
        var zb: Int? = null
        for (el in elemente) {
            val box = el.boundingBox ?: continue
            if (box.right <= mitte + 10) za = za ?: werder.ziffer(el.text)
            else if (box.left >= mitte - 10) zb = zb ?: werder.ziffer(el.text)
            else werder.zifferPaar(el.text)?.let { (x, y) -> za = za ?: x; zb = zb ?: y } // "10" als ein Wort
        }
        return za to zb
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
