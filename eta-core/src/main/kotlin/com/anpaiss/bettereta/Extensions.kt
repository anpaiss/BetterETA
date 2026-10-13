package com.anpaiss.bettereta

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/**
 * Emit immediately, then swallow further updates for [timeoutMs].
 *
 * The engine publishes state once per second, but on an e-ink display every
 * redraw costs battery and leaves ghosting, and most fields simply do not
 * change that fast (an arrival time changes once a minute). Conflating means
 * we always render the newest value rather than a stale queued one.
 */
fun <T> Flow<T>.throttle(timeoutMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(timeoutMs)
}

/**
 * Suppress emissions while this data type is not on the ride page the user is
 * actually looking at. When it becomes visible again the latest value is
 * emitted straight away, so the field is never seen stale.
 *
 * Fails open by design: a null [visibleDataTypeIds] means "we don't know what
 * is on screen" and everything is drawn. A gate that wrongly filtered us out
 * would freeze a field for an entire ride and fail silently — far worse than a
 * handful of wasted redraws.
 *
 * A [preview] in the profile editor is never gated: there the Karoo still
 * reports the last ride page as visible, so the gate kept the preview blank
 * and the field looked broken while being added (25/09/2026).
 */
fun <T> Flow<T>.whileVisible(
    visibleDataTypeIds: StateFlow<Set<String>?>,
    dataTypeId: String,
    preview: Boolean = false,
): Flow<T> = if (preview) this else combine(visibleDataTypeIds) { value, visible ->
    value to (visible == null || dataTypeId in visible)
}.filter { it.second }.map { it.first }
