package com.anpaiss.bettereta

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
 * FIELD PREVIEW: every field drawn by its own startView, pixel for pixel, in the
 * cells the Karoo really hands out, down to the smallest one. It exists because
 * a page with ten fields is never what we ride with, so nobody sees whether the
 * fields stay readable there (Andrea, 10/11/2026).
 *
 * Nothing is redrawn by hand: each cell gets a real data type fed with a fixed
 * state, and a ViewEmitter whose handler applies the RemoteViews here instead
 * of sending them to the Karoo.
 */
class FieldPreviewActivity : AppCompatActivity() {

    /** A cell as the Karoo describes it in ViewConfig; sizes read from the VIEW log lines of the k24. */
    private class Cell(val w: Int, val h: Int, val grid: Pair<Int, Int>, val textSp: Int)

    private enum class Values(val label: String) { SAMPLE("SAMPLE"), LONGEST("LONGEST"), NO_ROUTE("NO ROUTE") }

    private companion object {
        val HALF  = Cell(238, 126, 30 to 12, 41)   // five rows of two: the smallest cell seen so far
        val ROW   = Cell(478, 126, 60 to 12, 54)
        // Only the dashboard is shown in these two and it ignores the text size (never logged for them)
        val THIRD = Cell(478, 212, 60 to 20, 54)
        val PAGE  = Cell(478, 642, 60 to 60, 54)
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

        val now = System.currentTimeMillis()
        val eta = MutableStateFlow(etaState(now))
        val singles: List<DataTypeImpl> = listOf(
            EtaAbsoluteDataType("preview", eta),
            EtaRemainingDataType("preview", eta),
            EtaFarthestDataType("preview", eta),
            EtaNextPoiDataType("preview", eta),
            WayHomeDataType("preview", MutableStateFlow(wayHomeState(now))),
            EtaPaceDataType("preview", eta),
            EtaMarginDataType("preview", eta),
            com.anpaiss.karooswitches.SwitchesDataType("preview"),
        )
        // The same context the fields get on the Karoo, with the theme forced: NativeField reads it
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

        caption("STYLE ${Prefs(this).tileStyle.label.uppercase()} · REAL SIZE · HALF CELLS ${HALF.w}×${HALF.h} (5 ROWS OF 2, THE SMALLEST)")
        singles.chunked(2).forEach { row(HALF, it) }
        caption("FULL-WIDTH ROWS ${ROW.w}×${ROW.h}")
        singles.forEach { row(ROW, listOf(it)) }
        caption("DASHBOARD IN A THIRD OF THE PAGE ${THIRD.w}×${THIRD.h}")
        row(THIRD, listOf(EtaMasterDataType("preview", eta)))
        caption("DASHBOARD, FULL PAGE ${PAGE.w}×${PAGE.h}")
        row(PAGE, listOf(EtaMasterDataType("preview", eta)))
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
        type.startView(ctx, ViewConfig(cell.grid, cell.w to cell.h, cell.textSp, ViewConfig.Alignment.CENTER), emitter)
    }

    private fun etaState(now: Long): EtaState = when (values) {
        Values.SAMPLE   -> EtaState.sample(now)
        Values.NO_ROUTE -> EtaState()
        // The widest texts each field can show: hours in every value, "~", a long POI name, a late MTA
        Values.LONGEST  -> {
            val remaining = 11 * 3600L + 48 * 60L
            val margin = -(2 * 3600L + 5 * 60L)
            EtaState(
                remainingSeconds = remaining,
                hasRoute = true,
                status = "",
                remainingDistanceM = 187_600.0,
                remainingElevationM = 4_350.0,
                deltaSeconds = -(3600L + 25 * 60L),
                farthestPointDistanceM = 123_400.0,
                mtaEpochMs = now + (remaining + margin) * 1000L,
                marginSeconds = margin,
                estimateApprox = true,
                uncertaintySeconds = 95 * 60L,
                nextPoiName = "Mountain hut with a long name",
                nextPoiSeconds = 10 * 3600L + 12 * 60L,
                nextPoiDistanceM = 98_700.0,
            )
        }
    }

    private fun wayHomeState(now: Long): WayHomeState = when (values) {
        Values.SAMPLE   -> WayHomeState.sample(now)
        Values.NO_ROUTE -> WayHomeState()
        Values.LONGEST  -> WayHomeState(
            returnSec = 3 * 3600L + 25 * 60L, returnDistM = 64_800.0, following = false,
            arrivalEpochMs = now + (3 * 3600L + 25 * 60L) * 1000L, limitEpochMs = now + 3600_000L,
            allowanceSec = -(2 * 3600L + 28 * 60L), growth = 1.0,
        )
    }
}
