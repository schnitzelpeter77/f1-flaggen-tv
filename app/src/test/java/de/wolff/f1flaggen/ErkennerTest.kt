package de.wolff.f1flaggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErkennerTest {

    /** Spielt Bilder im 700-ms-Takt ab und sammelt die gemeldeten Ereignisse mit Zeitpunkt. */
    private fun abspielen(e: Erkenner, start: Long, bilder: List<String>): List<Pair<Long, String>> {
        val aus = mutableListOf<Pair<Long, String>>()
        bilder.forEachIndexed { i, txt ->
            val t = start + i * 700L
            e.verarbeite(txt, t)?.let { aus += t to it }
        }
        return aus
    }

    @Test
    fun gelbeFlaggeWirdNachZweiBildernGemeldet() {
        val e = Erkenner()
        assertNull(e.verarbeite("LAP 1/71 YELLOW FLAG TRACK HAZARD", 0))
        assertEquals("YELLOW", e.verarbeite("LAP 1/71 YELLOW FLAG TRACK HAZARD", 700))
    }

    @Test
    fun gelbEndetNachZehnSekundenOhneHinweis() {
        val e = Erkenner()
        val bilder = List(3) { "YELLOW FLAG" } + List(20) { "LAP 2/71 SKY SPORT" }
        val r = abspielen(e, 0, bilder)
        assertEquals("YELLOW", r[0].second)
        assertEquals("CLEAR", r[1].second)
        // Ende frühestens 10 s nach dem letzten Hinweis (bei 1400 ms)
        assert(r[1].first > 1400 + 10_000)
        assertEquals(2, r.size)
    }

    @Test
    fun safetyCarBleibtSolangeSCinDerZeitenlisteSteht() {
        val e = Erkenner()
        val bilder = List(2) { "SAFETY CAR DEPLOYED" } + List(60) { "SC LAP 12 VER HAM" }
        val r = abspielen(e, 0, bilder)
        assertEquals(listOf("SC"), r.map { it.second })
    }

    @Test
    fun safetyCarEndetNachZwanzigSekundenOhneHinweis() {
        val e = Erkenner()
        val bilder = List(2) { "SAFETY CAR DEPLOYED" } + List(40) { "LAP 13 VER HAM LEC" }
        val r = abspielen(e, 0, bilder)
        assertEquals(listOf("SC", "CLEAR"), r.map { it.second })
        assert(r[1].first > 700 + 20_000)
    }

    @Test
    fun kurzeWiederholungOhneEinblendungBeendetSCnicht() {
        val e = Erkenner()
        val bilder = List(2) { "SAFETY CAR" } + List(15) { "REPLAY" } + List(5) { "SC LAP 14" } + List(15) { "REPLAY" }
        val r = abspielen(e, 0, bilder)
        assertEquals(listOf("SC"), r.map { it.second })
    }

    @Test
    fun trackClearSchaltetSofortUm() {
        val e = Erkenner()
        val bilder = List(2) { "YELLOW FLAG" } + List(2) { "TRACK CLEAR" }
        val r = abspielen(e, 0, bilder)
        assertEquals(listOf("YELLOW", "CLEAR"), r.map { it.second })
    }

    @Test
    fun vscWirdNichtAlsSafetyCarErkannt() {
        val e = Erkenner()
        val r = abspielen(e, 0, List(2) { "VIRTUAL SAFETY CAR DEPLOYED" })
        assertEquals(listOf("VSC"), r.map { it.second })
    }
}
