package de.wolff.f1flaggen

import android.content.Context

/** Gespeicherte Einstellungen der App. */
class Einstellungen(ctx: Context) {
    private val sp = ctx.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    var haUrl: String
        get() = sp.getString("ha_url", "http://192.168.50.98:8123") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim()).apply()

    var webhook: String
        get() = sp.getString("webhook", "f1_tv_bild") ?: ""
        set(v) = sp.edit().putString("webhook", v.trim()).apply()

    /** Wenn an: alle 15 s erkannten Text an Home Assistant schicken (zum Einstellen der Erkennung). */
    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = sp.edit().putBoolean("debug", v).apply()
}
