package com.anpaiss.bettereta

import com.anpaiss.karooswitches.backup.ExtensionBackupProvider

/**
 * What RideStats backs up to the rider's Drive (KarooSwitches library): the
 * model, the way memory, the comparison history, the list of imported FITs
 * and the "don't count it" photo. Not surface_ways.txt, a leftover nobody reads.
 */
class BackupProvider : ExtensionBackupProvider() {
    override val prefsNames = listOf("better_eta_prefs", "better_eta_model", "better_eta_results")
    override val files = listOf("way_speeds.txt", "imported_fits.txt", "last_ride")
    override fun restoreRefusal() = BetterEtaExtension.instance?.restoreRefusal()
    override fun afterRestore() { BetterEtaExtension.instance?.reloadAfterRestore() }
}
