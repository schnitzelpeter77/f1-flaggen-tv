package de.wolff.f1flaggen

/**
 * Liest die Spielstand-Einblendung (oberer Bildrand) und erkennt Werder-Tore.
 *
 * Erwartete Formen (Sky, DAZN, ARD/ZDF), z. B.:
 *   "SVW 1 : 0 BVB 23:45", "BVB 0-2 SVW 67:12", "SV WERDER BREMEN 1 0 FC BAYERN"
 * Werder wird an SVW / WERDER / BREMEN erkannt. Steht Werder links vom Ergebnis, gilt die erste Zahl,
 * steht Werder rechts, die zweite.
 *
 * Ein neuer Stand gilt erst, wenn er [BESTAETIGUNGEN]-mal hintereinander gleich gelesen wurde.
 * Der erste Stand nach dem Einschalten ist nur die Ausgangslage (kein Tor).
 */
class WerderErkenner {

    data class Stand(val werder: Int, val gegner: Int, val gegnerName: String)

    data class Ereignis(
        val art: String,          // stand | tor | gegentor | korrektur
        val stand: Stand,
        val minute: Int?,
    ) {
        fun alsText(): String =
            "${stand.werder}:${stand.gegner} gegen ${stand.gegnerName}" + (minute?.let { " | $it. Min" } ?: "")
    }

    companion object {
        const val BESTAETIGUNGEN = 3
        private const val HERZSCHLAG_MS = 20_000L
        private const val NEUSTART_NACH_MS = 10 * 60_000L
        private val WERDER = setOf("SVW", "WERDER", "BREMEN", "SVWERDER", "SVWB")
        private val KEIN_TEAM = setOf(
            // Senderlogos und Einblendungstexte – Vereinskürzel wie VFB, TSG, RBL bleiben erlaubt
            "LIVE", "SKY", "SPORT", "TOP", "DAZN", "ARD", "ZDF", "SAT", "HD", "UHD", "MIN", "HZ", "HALBZEIT",
            "CLOCK", "BUNDESLIGA", "POKAL", "DFB", "NACHSPIELZEIT", "ZEITLUPE", "WIEDERHOLUNG"
        )
    }

    /** Ein erkanntes Wort mit Position (Pixel im Ausschnitt). */
    data class Wort(val text: String, val links: Int, val oben: Int, val rechts: Int, val unten: Int) {
        val mitteX get() = (links + rechts) / 2
        val mitteY get() = (oben + unten) / 2
        val hoehe get() = unten - oben
    }

    /**
     * Zweizeilige Einblendung (Bundesliga-Grafik: oben "VFB SVW 24:01", darunter große Ziffern):
     * findet das Werder-Kürzel und das Gegnerkürzel in derselben Zeile.
     * Liefert (links, rechts) in Bildreihenfolge oder null.
     */
    fun kuerzelPaar(woerter: List<Wort>): Pair<Wort, Wort>? {
        val norm = { w: Wort -> w.text.uppercase().replace(Regex("[^A-Z]"), "") }
        val w = woerter.firstOrNull { norm(it) in WERDER } ?: return null
        val g = woerter
            .filter { it !== w }
            .filter { val n = norm(it); n.length in 2..5 && n == it.text.uppercase().trim() && n !in KEIN_TEAM && n !in WERDER }
            .filter { kotlin.math.abs(it.mitteY - w.mitteY) < w.hoehe * 0.7 }
            .minByOrNull { kotlin.math.abs(it.mitteX - w.mitteX) }
            ?: return null
        // Kürzel liegen nebeneinander (höchstens ~6 Kürzelbreiten auseinander)
        if (kotlin.math.abs(g.mitteX - w.mitteX) > (w.rechts - w.links) * 6) return null
        return if (w.mitteX < g.mitteX) w to g else g to w
    }

    /** Ziffernfeld unter einem Kürzel (Bereich im Ausschnitt). */
    fun ziffernFeld(k: Wort, breite: Int, hoehe: Int): IntArray {
        val b = k.rechts - k.links
        val l = (k.links - b * 0.25).toInt().coerceAtLeast(0)
        val r = (k.rechts + b * 0.25).toInt().coerceAtMost(breite)
        val o = (k.unten + k.hoehe * 0.15).toInt().coerceAtMost(hoehe - 1)
        val u = (k.unten + k.hoehe * 3.6).toInt().coerceAtMost(hoehe)
        return intArrayOf(l, o, r, u)
    }

    /** Liest eine einzelne Torzahl aus dem Text eines Ziffernfelds (typische Verwechslungen werden korrigiert). */
    fun ziffer(roh: String): Int? {
        val t = roh.trim().replace(" ", "")
            .map { when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', '|', 'i', '!' -> '1'; 'Z', 'z' -> '2'; else -> it } }
            .joinToString("")
        return Regex("^(\\d{1,2})$").find(t)?.groupValues?.get(1)?.toInt()?.takeIf { it <= 15 }
    }

    /** Baut aus Kürzeln, Ziffern und Uhr einen Text, den [lies] versteht. */
    fun synthetisch(links: Wort, rechts: Wort, a: Int, b: Int, uhr: String?): String =
        "${links.text} $a $b ${rechts.text}" + (uhr?.let { " $it" } ?: "")

    private var kandidat: Stand? = null
    private var kandidatZaehler = 0
    private var bestaetigt: Stand? = null
    private var zuletztGelesen = 0L
    private var letzterHerzschlag = 0L
    var letzteMinute: Int? = null
        private set

    /** Normalisierter Text des letzten Ausschnitts (für Debug). */
    var letzterText: String = ""
        private set

    /** Liest Stand und Spielminute aus dem Text des oberen Bildrands. */
    fun lies(roh: String): Pair<Stand?, Int?> {
        var t = roh.uppercase()
            .replace(Regex("[^A-Z0-9:\\-]+"), " ")
            .trim()

        // Spieluhr (mm:ss, auch 90:00+) herausziehen, damit sie nicht als Ergebnis zählt
        var minute: Int? = null
        Regex("\\b(\\d{1,3}):([0-5]\\d)\\b").find(t)?.let {
            minute = it.groupValues[1].toInt()
            t = t.replaceRange(it.range, " CLOCK ")
        }
        // Ergebnis "1:0" / "1-0" in zwei Zahlen aufteilen
        t = t.replace(Regex("\\b(\\d{1,2})\\s*[:\\-]\\s*(\\d{1,2})\\b"), " $1 $2 ")
            .replace(Regex("[:\\-]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        letzterText = t

        val tok = t.split(" ").filter { it.isNotEmpty() }
        val werderIdx = tok.indices.filter { tok[it] in WERDER }
        if (werderIdx.isEmpty()) return null to minute
        val start = werderIdx.first()
        val ende = werderIdx.last()

        fun zahl(i: Int): Int? = tok.getOrNull(i)?.takeIf { it.length <= 2 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 15 }
        fun team(i: Int): String? = tok.getOrNull(i)?.takeIf { it.length in 2..14 && it.all(Char::isLetter) && it !in KEIN_TEAM && it !in WERDER }

        // Werder links: WERDER a b GEGNER
        val heim = zahl(ende + 1)?.let { a -> zahl(ende + 2)?.let { b ->
            val g = team(ende + 3) ?: team(ende + 4)
            if (g != null) Stand(a, b, g) else null
        } }
        // Werder rechts: GEGNER a b WERDER
        val gast = zahl(start - 1)?.let { b -> zahl(start - 2)?.let { a ->
            val g = team(start - 3) ?: team(start - 4)
            if (g != null) Stand(b, a, g) else null
        } }
        val stand = when {
            heim != null && gast == null -> heim
            gast != null && heim == null -> gast
            else -> null // nichts oder mehrdeutig
        }
        return stand to minute
    }

    /** Verarbeitet einen Bildausschnitt und liefert ggf. ein Ereignis zum Melden. */
    fun verarbeite(roh: String, jetzt: Long = System.currentTimeMillis()): Ereignis? {
        val (stand, minute) = lies(roh)
        if (minute != null) letzteMinute = minute

        // Lange nichts gelesen (anderer Sender, Spiel vorbei): neu anfangen
        if (bestaetigt != null && jetzt - zuletztGelesen > NEUSTART_NACH_MS) {
            bestaetigt = null
            kandidat = null
            kandidatZaehler = 0
        }
        if (stand == null) return null // z. B. Wiederholung ohne Einblendung – Kandidat bleibt erhalten
        zuletztGelesen = jetzt

        if (stand == kandidat) kandidatZaehler++ else {
            kandidat = stand
            kandidatZaehler = 1
        }
        if (kandidatZaehler < BESTAETIGUNGEN) return null

        val alt = bestaetigt
        if (alt == stand) {
            if (jetzt - letzterHerzschlag > HERZSCHLAG_MS) {
                letzterHerzschlag = jetzt
                return Ereignis("stand", stand, letzteMinute)
            }
            return null
        }
        bestaetigt = stand
        letzterHerzschlag = jetzt
        if (alt == null || alt.gegnerName != stand.gegnerName) {
            return Ereignis("stand", stand, letzteMinute) // Ausgangslage, kein Tor
        }
        val dw = stand.werder - alt.werder
        val dg = stand.gegner - alt.gegner
        return when {
            dw < 0 || dg < 0 -> Ereignis("korrektur", stand, letzteMinute) // z. B. VAR
            dw in 1..2 && dg == 0 -> Ereignis("tor", stand, letzteMinute)
            dg in 1..2 && dw == 0 -> Ereignis("gegentor", stand, letzteMinute)
            else -> Ereignis("stand", stand, letzteMinute) // unplausibler Sprung: nur übernehmen
        }
    }
}
