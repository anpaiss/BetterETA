package com.anpaiss.boschrange

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FIELD PREVIEW, the same screen as in BetterETA: every field drawn by its own
 * startView at real size in the cells the Karoo hands out, down to the smallest
 * (a data page holds at most ten fields, five rows of two, 238x126 px each), so
 * a crowded page can be checked without building one (Andrea, 10/11/2026).
 *
 * Each cell gets a real data type fed with a fixed state and a ViewEmitter whose
 * handler applies the RemoteViews here instead of sending them to the Karoo.
 */
class FieldPreviewActivity : AppCompatActivity() {

    /** A cell as the Karoo describes it in ViewConfig; sizes read from the VIEW log lines of the k24. */
    private class Cell(val w: Int, val h: Int, val grid: Pair<Int, Int>, val textSp: Int)

    private enum class Values(val label: String) { SAMPLE("SAMPLE"), LONGEST("LONGEST"), OUT_OF_RANGE("OUT OF RANGE"), NO_ROUTE("NO ROUTE") }

    private companion object {
        val HALF = Cell(238, 126, 30 to 12, 41)
        val ROW  = Cell(478, 126, 60 to 12, 54)
        val PAGE = Cell(478, 642, 60 to 60, 54)   // the text size of a full page was never logged; these fields ignore it
    }

    private val emitters = ArrayList<ViewEmitter>()
    private lateinit var list: LinearLayout
    private var values = Values.SAMPLE
    private var dark = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onStart() {
        super.onStart()
        rebuild()
    }

    override fun onStop() {
        stopViews()
        super.onStop()
    }

    private fun stopViews() {
        emitters.forEach { it.cancel() }
        emitters.clear()
    }

    private fun rebuild() {
        stopViews()
        list.removeAllViews()
        val page = if (dark) Color.BLACK else Color.WHITE
        val ink  = if (dark) Color.WHITE else Color.BLACK
        list.setBackgroundColor(page)

        list.addView(LinearLayout(this).apply {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(Button(context).apply {
                text = "VALUES: ${values.label}"
                setOnClickListener { values = Values.values()[(values.ordinal + 1) % Values.values().size]; rebuild() }
            }, lp)
            addView(Button(context).apply {
                text = if (dark) "THEME: DARK" else "THEME: LIGHT"
                setOnClickListener { dark = !dark; rebuild() }
            }, lp)
        })
        fun caption(text: String) = list.addView(TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(ink); alpha = 0.6f
            setPadding(8, 16, 8, 6)
        })

        val state = MutableStateFlow(state())
        val visible = MutableStateFlow<Set<String>?>(null)
        val singles: List<DataTypeImpl> = listOf(
            RangeFieldDataType("preview", "compact", RangeFieldDataType.Kind.COMPACT, state, visible),
            RangeFieldDataType("preview", "margin", RangeFieldDataType.Kind.MARGIN, state, visible),
            RangeFieldDataType("preview", "extra_climb", RangeFieldDataType.Kind.EXTRA_CLIMB, state, visible),
        )
        // The same context the fields get on the Karoo, with the theme forced
        val ctx = applicationContext.createConfigurationContext(Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        })
        // Cells sit on a grey sheet two pixels apart, like the Karoo's own cell borders
        fun row(cell: Cell, types: List<DataTypeImpl>) = list.addView(LinearLayout(this).apply {
            setBackgroundColor(Color.GRAY)
            setPadding(0, 1, 0, 1)
            for (type in types) addView(FrameLayout(context).apply {
                setBackgroundColor(page)
                start(ctx, type, cell, this)
            }, LinearLayout.LayoutParams(cell.w, cell.h).apply { setMargins(1, 0, 1, 0) })
        })

        caption("REAL SIZE · HALF CELLS ${HALF.w}×${HALF.h} (5 ROWS OF 2, THE SMALLEST)")
        singles.chunked(2).forEach { row(HALF, it) }
        caption("FULL-WIDTH ROWS ${ROW.w}×${ROW.h}")
        singles.forEach { row(ROW, listOf(it)) }
        caption("BOSCHRANGE PAGE, FULL PAGE ${PAGE.w}×${PAGE.h}")
        row(PAGE, listOf(RangePageDataType("preview", state, visible)))
    }

    /** Runs the field's own startView and shows what it emits inside [host]. */
    private fun start(ctx: Context, type: DataTypeImpl, cell: Cell, host: FrameLayout) {
        val handler = object : IHandler.Stub() {
            override fun onNext(bundle: Bundle) {
                // Anything else is a ViewEvent for the Karoo (header on/off): nothing to draw
                @Suppress("DEPRECATION")
                val views = bundle.getParcelable<RemoteViews>("view") ?: return
                runOnUiThread {
                    host.removeAllViews()
                    host.addView(views.apply(ctx, host), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            override fun onError(msg: String?) {}
            override fun onComplete() {}
        }
        val emitter = ViewEmitter(packageName, handler)
        emitters += emitter
        // preview = true: the fields leave out their tap, which here would open "Battery now?"
        type.startView(ctx, ViewConfig(cell.grid, cell.w to cell.h, cell.textSp, ViewConfig.Alignment.CENTER, preview = true), emitter)
    }

    private fun state(): DebugState {
        val riding = DebugState(
            rideOpen = true, startPct = 100, startAssumed = false, estimatedPct = 62.0,
            whEstimated = 380.0, capacityWh = 1000.0, manualUpdates = 1, lastUpdateKm = 12.4, lastUpdatePct = 68, lastUpdateAgeMin = 25,
            hasRoute = true, routeUnclassifiedPct = 0.0, surfaceComplete = true,
            arrivalPct = 31.0, halfBandPct = 6.0, marginPct = 21.0, extraClimbM = 420.0,
            remainingKm = 21.5, remainingAscentM = 640.0, thresholdPct = 10,
            historyRides = 9, historyValid = RangeForecast.CALIBRATION_RIDES,
        )
        return when (values) {
            Values.SAMPLE -> riding
            // The widest texts in range: three digits on both sides of the arrow, the calibration note in the band
            Values.LONGEST -> riding.copy(
                estimatedPct = 100.0, arrivalPct = 100.0, halfBandPct = 12.0, marginPct = 100.0, extraClimbM = 2340.0,
                thresholdPct = 30, remainingKm = 121.5, remainingAscentM = 3240.0, whEstimated = 987.0,
                bikeConnected = true, liveSoc = 100, routeUnclassifiedPct = 35.0, historyValid = 2,
            )
            Values.OUT_OF_RANGE -> riding.copy(
                estimatedPct = 100.0, arrivalPct = -129.0, halfBandPct = 12.0, marginPct = -139.0, extraClimbM = -2340.0,
                remainingKm = 121.5, remainingAscentM = 3240.0, historyValid = 2,
            )
            Values.NO_ROUTE -> DebugState(rideOpen = true, estimatedPct = 100.0)
        }
    }
}
