package de.wolff.f1flaggen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Einstellungen + Start/Stopp der Erkennung.
 * Per ADB steuerbar:
 *   am start -n de.wolff.f1flaggen/.MainActivity --ez autostart true
 *   am start -n de.wolff.f1flaggen/.MainActivity --es ha_url http://IP:8123 --es webhook f1_tv_bild
 *   am start -n de.wolff.f1flaggen/.MainActivity --ez stop true
 */
class MainActivity : Activity() {

    private val ANFRAGE_AUFNAHME = 42
    private lateinit var e: Einstellungen
    private lateinit var statusText: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        e = Einstellungen(this)

        val wurzel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 50, 80, 50)
            setBackgroundColor(Color.parseColor("#15151E"))
        }
        fun titel(t: String, groesse: Float = 18f) = TextView(this).apply {
            text = t; textSize = groesse; setTextColor(Color.WHITE); setPadding(0, 16, 0, 4)
        }
        wurzel.addView(titel("F1 Flaggen-Erkennung", 30f))
        wurzel.addView(titel("Home-Assistant-Adresse"))
        val urlFeld = EditText(this).apply {
            setText(e.haUrl); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        wurzel.addView(urlFeld)
        wurzel.addView(titel("Webhook-ID"))
        val hookFeld = EditText(this).apply { setText(e.webhook); setTextColor(Color.WHITE) }
        wurzel.addView(hookFeld)
        val debugBox = CheckBox(this).apply {
            text = "Debug: erkannten Text alle 15 s an Home Assistant senden"
            setTextColor(Color.WHITE); isChecked = e.debug
        }
        wurzel.addView(debugBox)

        val knoepfe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        fun knopf(t: String, aktion: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener {
                e.haUrl = urlFeld.text.toString()
                e.webhook = hookFeld.text.toString()
                e.debug = debugBox.isChecked
                aktion()
            }
        }
        val start = knopf("Erkennung starten") { fordereAufnahmeAn() }
        knoepfe.addView(start)
        knoepfe.addView(knopf("Stoppen") { stoppe() })
        knoepfe.addView(knopf("Test senden") { Melder.sende(this, "test", "Testmeldung vom TV") })
        wurzel.addView(knoepfe)

        statusText = titel("", 16f)
        wurzel.addView(statusText)
        setContentView(wurzel)
        start.requestFocus()

        verarbeiteIntent(intent)
        ui.post(aktualisiere)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        verarbeiteIntent(intent)
    }

    private fun verarbeiteIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("ha_url")?.let { e.haUrl = it }
        i.getStringExtra("webhook")?.let { e.webhook = it }
        if (i.hasExtra("debug")) e.debug = i.getBooleanExtra("debug", false)
        if (i.getBooleanExtra("stop", false)) stoppe()
        if (i.getBooleanExtra("autostart", false) && !CaptureService.laeuft) fordereAufnahmeAn()
    }

    private fun fordereAufnahmeAn() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), ANFRAGE_AUFNAHME)
    }

    private fun stoppe() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.AKTION_STOP))
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ANFRAGE_AUFNAHME) return
        if (resultCode != RESULT_OK || data == null) {
            CaptureService.status = "Bildaufnahme abgelehnt"
            return
        }
        val dienst = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATEN, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(dienst) else startService(dienst)
        // App in den Hintergrund, damit wieder das TV-Bild zu sehen ist
        ui.postDelayed({ moveTaskToBack(true) }, 1500)
    }

    private val aktualisiere = object : Runnable {
        override fun run() {
            statusText.text = "Status: ${CaptureService.status}\n" +
                "Letztes Ereignis: ${CaptureService.letztesEreignis}\n" +
                "Letzte Meldung an HA: ${Melder.letzteAntwort}\n" +
                "Ziel: ${e.haUrl}/api/webhook/${e.webhook}"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(aktualisiere)
        super.onDestroy()
    }
}
