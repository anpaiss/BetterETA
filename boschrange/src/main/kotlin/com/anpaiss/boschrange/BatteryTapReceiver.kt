package com.anpaiss.boschrange

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Target of the tap on any boschRange field or on the page: the extension opens
 * "Battery now?" prefilled with the estimate (or, before the ride, the start %
 * the forecast plans from). Same mechanism as the Switches field of
 * KarooSwitches: RemoteViews.setOnClickPendingIntent + a broadcast into our
 * own process.
 */
class BatteryTapReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.anpaiss.boschrange.FIELD_TAP"

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, BatteryTapReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        BoschRangeExtension.instance?.onFieldTap()
    }
}
