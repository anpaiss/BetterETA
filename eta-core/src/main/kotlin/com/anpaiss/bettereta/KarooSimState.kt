package com.anpaiss.bettereta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lettura di [SimState] dal ContentProvider di KarooSim (contratto in KarooSim/PLAN.md)
 * e dal suo broadcast. [active] non blocca mai: torna la cache e, se vecchia, rilegge
 * in background. KarooSim non installato o qualsiasi eccezione = non attiva.
 * Nel manifest dell'app serve <queries><provider android:authorities="com.anpaiss.karoosim.state"/>.
 */
class KarooSimState(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val state = SimState()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            state.onBroadcast(intent?.getBooleanExtra("active", false) == true, System.currentTimeMillis())
        }
    }

    fun start() {
        runCatching {
            val filter = IntentFilter(SimState.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else appContext.registerReceiver(receiver, filter)
        }
        active()
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    fun active(): Boolean {
        val now = System.currentTimeMillis()
        if (state.claimRefresh(now)) scope.launch(Dispatchers.IO) { state.onRead(query(), System.currentTimeMillis()) }
        return state.active
    }

    private fun query(): Boolean? = runCatching {
        // Prima il PackageManager: senza KarooSim la query scriverebbe un errore nel logcat ogni 10 s
        if (appContext.packageManager.resolveContentProvider(SimState.AUTHORITY, 0) == null) return@runCatching null
        appContext.contentResolver.query(Uri.parse(SimState.STATE_URI), null, null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("active")) == 1
        }
    }.getOrNull()
}
