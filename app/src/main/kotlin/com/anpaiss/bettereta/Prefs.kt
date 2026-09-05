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

    // MTA della route corrente, salvato perché l'estensione può ripartire a
    // metà giro. mtaRouteHash = hashCode della polyline a cui si riferisce
    // (anche quando l'utente ha risposto DISMISS: così non si richiede).
    var mtaEpochMs: Long
        get() = sp.getLong(KEY_MTA_EPOCH_MS, 0L)
        set(v) = sp.edit().putLong(KEY_MTA_EPOCH_MS, v).apply()

    var mtaRouteHash: Int
        get() = sp.getInt(KEY_MTA_ROUTE_HASH, 0)
        set(v) = sp.edit().putInt(KEY_MTA_ROUTE_HASH, v).apply()

    /** Quando il popup è stato mostrato per mtaRouteHash: la stessa route un altro giorno richiede. */
    var mtaAskedAtMs: Long
        get() = sp.getLong(KEY_MTA_ASKED_AT_MS, 0L)
        set(v) = sp.edit().putLong(KEY_MTA_ASKED_AT_MS, v).apply()

    /** Ultimo fix GPS (per la prova delle mappe offline nella schermata dell'app); 0 = mai. */
    var lastFixLat: Double
        get() = sp.getFloat(KEY_LAST_FIX_LAT, 0f).toDouble()
        set(v) = sp.edit().putFloat(KEY_LAST_FIX_LAT, v.toFloat()).apply()
    var lastFixLon: Double
        get() = sp.getFloat(KEY_LAST_FIX_LON, 0f).toDouble()
        set(v) = sp.edit().putFloat(KEY_LAST_FIX_LON, v.toFloat()).apply()

    companion object {
        private const val NAME = "better_eta_prefs"
        private const val KEY_LAST_FIX_LAT = "last_fix_lat"
        private const val KEY_LAST_FIX_LON = "last_fix_lon"
        const val KEY_TILE_STYLE = "tile_style"
        private const val KEY_MTA_EPOCH_MS   = "mta_epoch_ms"
        private const val KEY_MTA_ROUTE_HASH = "mta_route_hash"
        private const val KEY_MTA_ASKED_AT_MS = "mta_asked_at_ms"
    }
}
