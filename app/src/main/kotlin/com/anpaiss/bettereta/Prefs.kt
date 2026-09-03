package com.anpaiss.bettereta

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Preferenze utente. Stesso pattern di Prefs.kt in AxsRatio. */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Default INK: è lo stile più vicino al look storico dei campi (valori
    // arancio su fondo scuro), quindi l'update non cambia l'aspetto a chi
    // non sceglie nulla.
    var tileStyle: EtaStyle
        get() {
            val raw = sp.getString(KEY_TILE_STYLE, null) ?: return EtaStyle.INK
            return runCatching { EtaStyle.valueOf(raw) }.getOrDefault(EtaStyle.INK)
        }
        set(v) = sp.edit().putString(KEY_TILE_STYLE, v.name).apply()

    /**
     * Stile corrente come Flow: emette subito il valore attuale e poi a ogni
     * cambio dalle impostazioni, così i campi si ridisegnano senza aspettare
     * il prossimo tick del motore.
     */
    fun styleFlow(): Flow<EtaStyle> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TILE_STYLE) trySend(tileStyle)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        send(tileStyle)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val NAME = "better_eta_prefs"
        const val KEY_TILE_STYLE = "tile_style"
    }
}
