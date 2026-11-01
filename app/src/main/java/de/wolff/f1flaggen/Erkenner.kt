package de.wolff.f1flaggen

/**
 * Wertet den erkannten Bildschirmtext aus und erkennt F1-Rennleitungs-Einblendungen.
 * Ein Ereignis wird erst gemeldet, wenn es in zwei aufeinanderfolgenden Bildern erkannt wurde
 * (verhindert Fehlauslösungen durch einzelne Lesefehler).
 */
class Erkenner {
    private var kandidat: String? = null
    private var treffer = 0
    private var gemeldet: String? = null
    private var gemeldetUm = 0L

    /** Normalisierter Text des letzten Bildes (für Debug). */
    var letzterText: String = ""
        private set

    fun normalisiere(roh: String): String =
        roh.uppercase()
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()

    fun klassifiziere(t: String): String? = when {
        "RED FLAG" in t -> "RED"
        "VIRTUAL SAFETY CAR" in t -> "VSC"
        Regex("\\bVSC\\b").containsMatchIn(t) && ("DEPLOYED" in t || "ENDING" in t) -> "VSC"
        "SAFETY CAR" in t -> "SC"
        "CHEQUERED FLAG" in t || "CHECKERED FLAG" in t -> "FINISH"
        "DOUBLE YELLOW" in t || "YELLOW FLAG" in t -> "YELLOW"
        "TRACK CLEAR" in t || "GREEN FLAG" in t -> "CLEAR"
        else -> null
    }

    /** Liefert ein neues Ereignis zum Melden oder null. */
    fun verarbeite(roh: String, jetzt: Long = System.currentTimeMillis()): String? {
        val t = normalisiere(roh)
        letzterText = t
        val e = klassifiziere(t)
        if (e == null) {
            kandidat = null
            treffer = 0
            return null
        }
        if (e == kandidat) treffer++ else {
            kandidat = e
            treffer = 1
        }
        // Gleiches Ereignis frühestens nach 60 s erneut melden (Einblendung steht oft länger)
        if (treffer >= 2 && (e != gemeldet || jetzt - gemeldetUm > 60_000)) {
            gemeldet = e
            gemeldetUm = jetzt
            return e
        }
        return null
    }
}
