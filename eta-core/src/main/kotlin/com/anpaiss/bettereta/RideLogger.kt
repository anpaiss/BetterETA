package com.anpaiss.bettereta

/**
 * Log sink the shared classes write to. Each app plugs in its own file
 * logger (BetterEtaLogger in BetterETA); eta-core itself never decides where
 * the lines go.
 */
interface RideLogger {
    fun log(tag: String, message: String)
    fun logError(tag: String, message: String, t: Throwable)
}
