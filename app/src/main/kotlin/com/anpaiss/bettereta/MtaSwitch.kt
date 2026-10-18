package com.anpaiss.bettereta

import android.content.Context
import com.anpaiss.karooswitches.TimeReceiver

/**
 * MTA sulla pagina Switches (libreria KarooSwitches, tipo "time"): interruttore
 * ON/OFF più, quando è ON, l'orario con −15/−5/+5/+15. Dal 18/10 è l'unico modo
 * di cambiare l'MTA dopo il popup di partenza (niente più riproposte dopo le
 * soste o al cambio di meta) e l'unico modo di spegnerlo a priori: OFF azzera
 * l'MTA e al via del giro non chiede nulla. Dichiarato nel manifest.
 */
class MtaSwitch : TimeReceiver() {

    override fun isOn(context: Context): Boolean = Prefs(context).mtaEnabled

    override fun setOn(context: Context, on: Boolean) {
        BetterEtaExtension.instance?.setMtaEnabled(on) ?: run { Prefs(context).mtaEnabled = on }
    }

    override fun text(context: Context): String? = BetterEtaExtension.instance?.mtaText()

    override fun adjust(context: Context, deltaMin: Int) {
        BetterEtaExtension.instance?.adjustMta(deltaMin)
    }
}
