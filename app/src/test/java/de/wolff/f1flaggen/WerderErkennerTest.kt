package de.wolff.f1flaggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WerderErkennerTest {

    private fun stand(roh: String) = WerderErkenner().lies(roh).first

    @Test
    fun werderHeimMitDoppelpunkt() {
        assertEquals(WerderErkenner.Stand(1, 0, "BVB"), stand("SVW 1 : 0 BVB 23:45"))
    }

    @Test
    fun werderAuswaertsMitBindestrich() {
        assertEquals(WerderErkenner.Stand(2, 0, "BVB"), stand("BVB 0-2 SVW 67:12"))
    }

    @Test
    fun langerName() {
        assertEquals(WerderErkenner.Stand(1, 0, "BAYERN"), stand("SV WERDER BREMEN 1 0 FC BAYERN"))
    }

    @Test
    fun spieluhrWirdGelesen() {
        assertEquals(23, WerderErkenner().lies("SVW 1:0 BVB 23:45").second)
    }

    @Test
    fun ohneWerderKeinStand() {
        assertNull(stand("BVB 1 0 S04 12:00"))
    }

    @Test
    fun torErstNachBestaetigungUndNichtBeimEinschalten() {
        val e = WerderErkenner()
        var t = 0L
        fun bild(x: String) = e.verarbeite(x, t).also { t += 700 }
        // Ausgangslage 0:0 -> nur "stand"
        assertNull(bild("SVW 0 : 0 BVB 10:00"))
        assertNull(bild("SVW 0 : 0 BVB 10:01"))
        assertEquals("stand", bild("SVW 0 : 0 BVB 10:02")?.art)
        // Lesefehler einmalig 8:0 -> nichts
        assertNull(bild("SVW 8 : 0 BVB 10:03"))
        // Tor: 1:0 dreimal gelesen
        assertNull(bild("SVW 1 : 0 BVB 11:00"))
        assertNull(bild("SVW 1 : 0 BVB 11:01"))
        val tor = bild("SVW 1 : 0 BVB 11:02")
        assertEquals("tor", tor?.art)
        assertEquals(1, tor?.stand?.werder)
        assertEquals(11, tor?.minute)
    }

    @Test
    fun wiederholungOhneEinblendungStoertNicht() {
        val e = WerderErkenner()
        var t = 0L
        fun bild(x: String) = e.verarbeite(x, t).also { t += 700 }
        repeat(3) { bild("BVB 0-0 SVW 30:00") }
        bild("SVW 1")             // Teilstück
        bild("ZEITLUPE")          // keine Einblendung
        bild("BVB 0-1 SVW 31:00")
        bild("WIEDERHOLUNG")
        bild("BVB 0-1 SVW 31:01")
        assertEquals("tor", bild("BVB 0-1 SVW 31:02")?.art)
    }

    @Test
    fun gegentorUndKorrektur() {
        val e = WerderErkenner()
        var t = 0L
        fun bild(x: String) = e.verarbeite(x, t).also { t += 700 }
        repeat(3) { bild("SVW 1 1 BVB") }
        repeat(2) { bild("SVW 1 2 BVB") }
        assertEquals("gegentor", bild("SVW 1 2 BVB")?.art)
        repeat(2) { bild("SVW 1 1 BVB") }
        assertEquals("korrektur", bild("SVW 1 1 BVB")?.art)
    }
}
