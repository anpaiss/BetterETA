package com.anpaiss.boschrange

import android.content.Context
import android.widget.RemoteViews
import com.anpaiss.bettereta.throttle
import com.anpaiss.bettereta.whileVisible
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the debug field shows; the extension publishes it every tick and on every event. */
data class DebugState(
    val rideOpen: Boolean = false,
    val startPct: Int = 100,
    val startAssumed: Boolean = true,
    val estimatedPct: Double = 100.0,
    val whMech: Double = 0.0,
    val whEstimated: Double = 0.0,
    val sUsed: Double = Prefs.S_INITIAL,
    val hasRoute: Boolean = false,
    val routeUnclassifiedPct: Double = -1.0,   // -1 = not classified yet / no route
    val surfaceComplete: Boolean? = null,
    val manualUpdates: Int = 0,
)

/**
 * Provisional, text-only data field for the test rides of step 2 (13/10):
 * estimated battery now (start % minus the integrated energy), Wh spent since
 * the start, the s in use and the state of the surface classification. No
 * arrival prediction: that is step 3. Appears as "boschRange Debug" in the
 * BOSCHRANGE group of the Karoo field picker.
 */
class DebugFieldDataType(
    extension: String,
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "debug") {

    companion object { private const val REDRAW_THROTTLE_MS = 5_000L }

    private data class Lines(val l1: String, val l2: String, val l3: String)

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            state.map { Math.round(it.estimatedPct).toDouble() }.distinctUntilChanged().collect { pct ->
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { lines ->
                    val views = RemoteViews(context.packageName, R.layout.view_debug)
                    views.setTextViewText(R.id.tv_debug_1, lines.l1)
                    views.setTextViewText(R.id.tv_debug_2, lines.l2)
                    views.setTextViewText(R.id.tv_debug_3, lines.l3)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(s: DebugState): Lines {
        if (!s.rideOpen) return Lines("no ride open", "s %.2f".format(s.sUsed), surfaceLine(s))
        val l1 = "now %.0f %%  (start %d%s)".format(s.estimatedPct, s.startPct, if (s.startAssumed) "?" else "")
        val l2 = "%.0f Wh spent (%.0f mech)  s %.2f%s".format(s.whEstimated, s.whMech, s.sUsed, if (s.manualUpdates > 0) "  upd ${s.manualUpdates}" else "")
        return Lines(l1, l2, surfaceLine(s))
    }

    private fun surfaceLine(s: DebugState): String = when {
        !s.hasRoute                    -> "surface: no route"
        s.routeUnclassifiedPct < 0.0   -> "surface: classifying…"
        s.surfaceComplete == null      -> "surface: NOT classified (100 % unknown)"
        else -> "surface: %s, %.0f %% unknown".format(if (s.surfaceComplete) "classified" else "partial", s.routeUnclassifiedPct)
    }
}
